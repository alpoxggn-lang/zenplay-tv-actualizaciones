package tv.own.owntv.core.catalog

import java.net.URI
import java.util.Locale
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/**
 * A catalogue row as the presentation layer needs to see it. Provider identity stays untouched in
 * the database; this class only supplies a readable suffix when two loaded rows share a title.
 */
data class CatalogVariantCandidate<K>(
    val key: K,
    val title: String,
    val sourceId: Long,
    val remoteId: String?,
    val qualifiers: List<String> = emptyList(),
    val sourceLabel: String? = null,
)

/**
 * Builds display names without merging rows. Unique titles remain byte-for-byte unchanged. For a
 * repeated title, available language/quality/channel metadata is used first, provider/source next,
 * and a safe remote-id fragment (or stable option number) only when the descriptive labels still
 * collide. This makes same-name variants distinguishable without polluting search or stored names.
 */
fun <K> catalogVariantDisplayNames(candidates: List<CatalogVariantCandidate<K>>): Map<K, String> {
    if (candidates.isEmpty()) return emptyMap()
    val result = LinkedHashMap<K, String>(candidates.size)
    candidates.groupBy { normalizeVariantTitle(it.title) }.values.forEach { group ->
        if (group.size == 1) {
            val only = group.first()
            result[only.key] = only.title
            return@forEach
        }

        val multipleSources = group.map { it.sourceId }.distinct().size > 1
        val baseSuffixes = group.map { candidate ->
            buildList {
                candidate.qualifiers.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .forEach(::add)
                if (multipleSources) add(candidate.sourceLabel?.trim()?.takeIf(String::isNotEmpty) ?: "S${candidate.sourceId}")
            }.joinToString(" · ")
        }
        val repeatedSuffixes = baseSuffixes.withIndex()
            .groupBy { it.value.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys

        val provisional = group.mapIndexed { index, candidate ->
            val base = baseSuffixes[index]
            val needsIdentity = base.isBlank() || base.lowercase(Locale.ROOT) in repeatedSuffixes
            val identity = if (needsIdentity) safeRemoteIdentity(candidate.remoteId) ?: "#${index + 1}" else null
            listOfNotNull(base.takeIf(String::isNotBlank), identity).joinToString(" · ")
        }
        val repeatedFinal = provisional.withIndex()
            .groupBy { it.value.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys

        group.forEachIndexed { index, candidate ->
            val suffix = provisional[index].let {
                if (it.lowercase(Locale.ROOT) in repeatedFinal) "$it · #${index + 1}" else it
            }
            result[candidate.key] = "${candidate.title} · $suffix"
        }
    }
    return result
}

fun MovieEntity.catalogVariantCandidate(
    displayTitle: String = name,
    sourceLabel: String? = null,
) = CatalogVariantCandidate(
    key = id,
    title = displayTitle,
    sourceId = sourceId,
    remoteId = remoteId,
    qualifiers = listOfNotNull(
        providerLanguage,
        qualityLabel(qualityRank),
        advertisedCapabilities,
        containerExt?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty),
        (year ?: parsedYear)?.takeIf { it > 0 }?.toString(),
    ),
    sourceLabel = sourceLabel,
)

fun SeriesEntity.catalogVariantCandidate(
    displayTitle: String = name,
    sourceLabel: String? = null,
) = CatalogVariantCandidate(
    key = id,
    title = displayTitle,
    sourceId = sourceId,
    remoteId = remoteId,
    qualifiers = listOfNotNull(
        providerLanguage,
        qualityLabel(qualityRank),
        advertisedCapabilities,
        (year ?: parsedYear)?.takeIf { it > 0 }?.toString(),
    ),
    sourceLabel = sourceLabel,
)

fun ChannelEntity.catalogVariantCandidate(
    displayTitle: String = name,
    sourceLabel: String? = null,
) = CatalogVariantCandidate(
    key = id,
    title = displayTitle,
    sourceId = sourceId,
    remoteId = remoteId,
    qualifiers = listOfNotNull(
        number?.takeIf { it > 0 }?.let { "#$it" },
        safeStreamKind(),
        epgChannelId?.trim()?.takeIf(String::isNotEmpty),
    ),
    sourceLabel = sourceLabel,
)

fun qualityLabel(rank: Int): String? = when (rank) {
    5 -> "8K"
    4 -> "4K"
    3 -> "1080p"
    2 -> "HD"
    1 -> "SD"
    else -> null
}

private fun normalizeVariantTitle(title: String): String = title.trim().lowercase(Locale.ROOT)

private fun safeRemoteIdentity(remoteId: String?): String? = remoteId
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { "ID ${if (it.length <= 12) it else it.takeLast(12)}" }

/** Never show the stream URL itself: it may contain credentials. Only its declared/path format. */
private fun ChannelEntity.safeStreamKind(): String? {
    manifestType?.trim()?.takeIf(String::isNotEmpty)?.let { return it.uppercase(Locale.ROOT) }
    val path = runCatching { URI(streamUrl).path.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> "HLS"
        path.endsWith(".mpd") -> "DASH"
        path.endsWith(".ts") -> "TS"
        else -> null
    }
}
