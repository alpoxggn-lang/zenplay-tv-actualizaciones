package tv.own.owntv.mobile.zenplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.live.GuideReader
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import java.util.Calendar

/** One programme on today's sports agenda. */
data class ZenAgendaItem(
    val channel: ChannelEntity,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
)

/** Today's agenda: guide programmes on sports channels, plus event channels that publish no guide. */
data class ZenAgenda(
    val items: List<ZenAgendaItem>,
    val events: List<ChannelEntity>,
    /** Every sports channel, in agenda order — the zap list when one is played. */
    val channels: List<ChannelEntity>,
)

/**
 * ZenPlay "Agenda deportiva": what is on today on the playlist's sports channels.
 *
 * Sports categories are found by name (Deportes, Sports, NFL, NBA, Liga MX, PPV, DAZN, ESPN…).
 * Their channels' guide is read for the rest of the day; event channels with no guide — the
 * "PPV | EVENTS" style categories whose channel name *is* the event — are listed on their own.
 */
class ZenSportsAgenda(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
    epgDao: EpgDao,
    epgSourceStore: EpgSourceStore,
    liveEpgReader: LiveEpgReader,
) {
    private val guideReader = GuideReader(epgDao, epgSourceStore, sourceDao, liveEpgReader)

    suspend fun load(): ZenAgenda = withContext(Dispatchers.IO) {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return@withContext ZenAgenda(emptyList(), emptyList(), emptyList())
        val sourceIds = activeSourceIds(settings, sourceDao, pid, MediaType.LIVE)
        if (sourceIds.isEmpty()) return@withContext ZenAgenda(emptyList(), emptyList(), emptyList())
        val cust = customize.observe(pid, MediaType.LIVE).first()
        val kids = profileDao.getById(pid)?.isKids == true
        val categories = categoryDao.observe(sourceIds, MediaType.LIVE).first()
        val hidden = tv.own.owntv.core.content.AdultCategoryClassifier
            .hiddenCategoryIds(categories, cust.hiddenCategories, kids)
        val sports = categories.filter { it.id !in hidden && SPORTS.containsMatchIn(it.name) }
        val eventCats = sports.filter { EVENTS.containsMatchIn(it.name) }.map { it.id }.toSet()

        val channels = sports.flatMap { cat ->
            channelDao.channelsAfterCategory(cat.id, Int.MIN_VALUE, Long.MIN_VALUE, PER_CATEGORY)
        }
            .filter { tv.own.owntv.core.customize.CustomizeKeys.channel(it) !in cust.hiddenItems }
            .distinctBy { it.id }
            .take(MAX_CHANNELS)

        val now = System.currentTimeMillis()
        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }.timeInMillis
        val offset = settings.epgOffsetMinutes.first()
        val gate = Semaphore(6)
        val rows = coroutineScope {
            channels.map { ch ->
                async {
                    gate.withPermit {
                        ch to runCatching { guideReader.row(ch, cust, offset, now - 4 * HOUR, endOfDay) }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        val items = rows.flatMap { (ch, programmes) ->
            programmes
                .filter { it.stopMs > now && it.startMs <= endOfDay && it.title.isNotBlank() }
                .map { ZenAgendaItem(ch, it.title, it.startMs, it.stopMs) }
        }
            .distinctBy { Triple(it.channel.id, it.startMs, it.title) }
            .sortedWith(compareBy({ it.startMs }, { it.channel.name.lowercase() }))
            .take(MAX_ITEMS)
        val withGuide = items.map { it.channel.id }.toSet()
        // Event channels name their kick-off ("… | 06:00 PM | …"): list them in that order.
        val events = channels.filter { it.categoryId in eventCats && it.id !in withGuide }
            .sortedWith(compareBy<ChannelEntity, Int?>(nullsLast()) { kickOffMinutes(it.name) })
            .take(MAX_EVENTS)
        ZenAgenda(items, events, channels)
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val PER_CATEGORY = 150
        private const val MAX_CHANNELS = 300
        private const val MAX_ITEMS = 400
        private const val MAX_EVENTS = 80

        private val SPORTS = Regex(
            "(SPORT|DEPORT|FUTBOL|FÚTBOL|SOCCER|\\bNFL\\b|\\bNBA\\b|\\bMLB\\b|\\bNHL\\b|\\bUFC\\b|\\bWWE\\b|BOXEO|\\bBOX\\b|" +
                "\\bF1\\b|FORMULA|LIGA|PREMIER|CHAMPIONS|ESPN|TUDN|DAZN|TIGO|\\bPPV\\b|EVENT|FIFA|GOLF|TENIS|TENNIS|" +
                "NASCAR|BEISBOL|BÉISBOL|BASEBALL|BASKET|HOCKEY|MOTOR|\\bAFIZZIONADOS\\b|FOX ONE|SKY SPORTS|\\bCUP\\b|COPA)",
            RegexOption.IGNORE_CASE,
        )
        private val EVENTS = Regex("(EVENT|\\bPPV\\b|PASS\\b|GAMES)", RegexOption.IGNORE_CASE)

        private val KICK_OFF = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp])\\.?\\s*[Mm]")

        /** "06:00 PM" in a channel name → minutes after midnight, or null. */
        fun kickOffMinutes(name: String): Int? {
            val m = KICK_OFF.find(name) ?: return null
            val h = m.groupValues[1].toInt() % 12
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            return (h + if (pm) 12 else 0) * 60 + m.groupValues[2].toInt()
        }

        /** Built from Koin's singletons (no module entry needed). */
        val shared: ZenSportsAgenda by lazy {
            val k = org.koin.core.context.GlobalContext.get()
            ZenSportsAgenda(k.get(), k.get(), k.get(), k.get(), k.get(), k.get(), k.get(), k.get(), k.get())
        }
    }
}
