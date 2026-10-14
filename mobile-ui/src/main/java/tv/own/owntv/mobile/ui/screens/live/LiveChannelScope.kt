package tv.own.owntv.mobile.ui.screens.live

import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.customize.railCategories
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * One coherent channel-list input. Compose uses its identity with the requested category to create
 * a fresh Paging presenter, so items from an earlier category/profile are never replayed into it.
 */
data class LiveChannelScope internal constructor(
    val profileId: Long,
    val sourceIds: List<Long>,
    val allowedKeys: Set<LiveKey>,
    internal val custom: SectionCustomizations,
    internal val hiddenCategoryIds: Set<Long>,
    internal val folderContextKeys: Map<Long, String>,
    internal val sort: SettingsRepository.SortMode,
    internal val orderedContexts: Set<String>,
) {
    /** Defense in depth: core's Folder query only filters categoryId, not the active source set. */
    internal fun includes(key: LiveKey, channel: ChannelEntity): Boolean {
        if (key !in allowedKeys || channel.sourceId !in sourceIds) return false
        if (channel.categoryId != null && channel.categoryId in hiddenCategoryIds) return false
        val itemKey = CustomizeKeys.channel(channel)
        if (itemKey in custom.hiddenItems) return false
        if (key is LiveKey.Folder) {
            if (channel.categoryId != key.id) return false
            val origin = custom.movedFromOrigin[itemKey]
            if (origin != null && origin == folderContextKeys[key.id]) return false
        }
        return key != LiveKey.Catchup || channel.catchup
    }
}

/** Uses only categories and customizations belonging to the same already-loaded profile. */
internal fun buildLiveChannelScope(
    profileId: Long,
    sourceIds: List<Long>,
    categories: List<CategoryEntity>,
    custom: SectionCustomizations,
    isKids: Boolean,
    sort: SettingsRepository.SortMode,
    orderedContexts: Set<String>,
): LiveChannelScope {
    val ids = sourceIds.distinct()
    val scopedCategories = categories.filter { it.sourceId in ids && it.mediaType == MediaType.LIVE }
    val folders = scopedCategories.railCategories(custom, kids = isKids)
    return LiveChannelScope(
        profileId = profileId,
        sourceIds = ids,
        allowedKeys = buildSet {
            add(LiveKey.All)
            add(LiveKey.Favorites)
            add(LiveKey.History)
            add(LiveKey.Catchup)
            folders.forEach { row ->
                row.categoryId?.let { add(LiveKey.Folder(it)) }
                    ?: row.customId?.let { add(LiveKey.Custom(it)) }
            }
        },
        custom = custom,
        hiddenCategoryIds = AdultCategoryClassifier.hiddenCategoryIds(scopedCategories, custom.hiddenCategories, isKids),
        folderContextKeys = scopedCategories.associate { it.id to CustomizeKeys.category(it) },
        sort = sort,
        orderedContexts = orderedContexts,
    )
}
