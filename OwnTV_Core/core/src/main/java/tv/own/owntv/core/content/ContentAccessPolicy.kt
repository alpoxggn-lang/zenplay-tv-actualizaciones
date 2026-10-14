package tv.own.owntv.core.content

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

/**
 * Final, shared authorization check for content reached outside the visible catalogue.
 *
 * Search, launch intents and playback must all respect the same manual hides. Kids profiles are
 * deliberately fail-closed when a provider omitted (or a stale link lost) the category: without a
 * category there is no reliable signal with which to classify the content as safe.
 */
class ContentAccessPolicy(
    private val profileDao: ProfileDao,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val seriesDao: SeriesDao,
    private val customize: CustomizationStore,
) {
    suspend fun allows(profileId: Long, channel: ChannelEntity): Boolean = allows(
        profileId,
        MediaType.LIVE,
        CustomizeKeys.channel(channel),
        channel.categoryId,
        channel.sourceId,
    )

    suspend fun allows(profileId: Long, movie: MovieEntity): Boolean = allows(
        profileId,
        MediaType.MOVIE,
        CustomizeKeys.movie(movie),
        movie.categoryId,
        movie.sourceId,
    )

    /** Episodes authorize against their parent series, including that series' manual hide. */
    suspend fun allows(profileId: Long, series: SeriesEntity): Boolean = allows(
        profileId,
        MediaType.SERIES,
        CustomizeKeys.series(series),
        series.categoryId,
        series.sourceId,
    )

    /** An episode has no source/category of its own; both are inherited from its stored parent. */
    suspend fun allows(profileId: Long, episode: EpisodeEntity): Boolean {
        val series = seriesDao.getSeriesById(episode.seriesId) ?: return false
        return allows(profileId, series)
    }

    suspend fun allows(
        profileId: Long,
        type: MediaType,
        itemKey: String,
        categoryId: Long?,
        sourceId: Long,
    ): Boolean {
        if (profileId < 0L) return false
        // A raw episode key cannot prove which series owns it. Call the EpisodeEntity overload so
        // the parent is resolved from Room and the series hide/category/source rules are applied.
        if (type == MediaType.EPISODE) return false
        val profile = profileDao.getById(profileId) ?: return false
        val section = sectionFor(type)
        val customizations = customize.observe(profileId, section).first()
        val category = categoryId?.let { categoryDao.getById(it) }
            ?.takeIf { it.mediaType == section && it.sourceId == sourceId }
        val sourceIds = sourceDao.sourceIdsForProfile(profileId).toSet()
        return allowsSnapshot(
            profile = profile,
            itemKey = itemKey,
            categoryId = categoryId,
            category = category,
            customizations = customizations,
            sourceId = sourceId,
            allowedSourceIds = sourceIds,
        )
    }

    companion object {
        /** Episodes inherit their show's visibility/customization section. */
        fun sectionFor(type: MediaType): MediaType = when (type) {
            MediaType.EPISODE -> MediaType.SERIES
            else -> type
        }

        fun allowsSnapshot(
            profile: ProfileEntity?,
            itemKey: String,
            categoryId: Long?,
            category: CategoryEntity?,
            customizations: SectionCustomizations,
            sourceId: Long,
            allowedSourceIds: Set<Long>,
        ): Boolean {
            profile ?: return false
            if (sourceId !in allowedSourceIds) return false
            if (category != null && category.sourceId != sourceId) return false
            if (itemKey in customizations.hiddenItems) return false
            if (category != null && CustomizeKeys.category(category) in customizations.hiddenCategories) return false
            if (!profile.isKids) return true
            if (categoryId == null || category == null) return false
            return !AdultCategoryClassifier.isAdult(category.name)
        }
    }
}
