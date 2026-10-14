package tv.own.owntv.mobile.ui.screens.home

import androidx.paging.PagingSource
import kotlinx.coroutines.CancellationException

/**
 * Reuses core's indexed date-added query, retaining its newest-first order while removing hidden
 * content. Loads small pages until the visible rail is full instead of copying an entire playlist
 * into memory. The Room source is disposed after each snapshot, including errors and cancellation.
 */
internal suspend fun <T : Any> readRecentlyAdded(
    sourceFactory: () -> PagingSource<Int, T>,
    include: (T) -> Boolean,
    limit: Int = 24,
): List<T> {
    require(limit > 0)
    // A provider sync can invalidate the first source halfway through reading. Start over once;
    // never combine pages belonging to different database snapshots.
    repeat(2) {
        val source = sourceFactory()
        try {
            val result = ArrayList<T>(limit)
            var params: PagingSource.LoadParams<Int> = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 64,
                placeholdersEnabled = false,
            )
            while (!source.invalid) {
                when (val page = source.load(params)) {
                    is PagingSource.LoadResult.Page -> {
                        result.addAll(page.data.asSequence().filter(include).take(limit - result.size))
                        if (source.invalid) break
                        val nextKey = page.nextKey
                        if (result.size >= limit || nextKey == null) return result
                        params = PagingSource.LoadParams.Append(
                            key = nextKey,
                            loadSize = 64,
                            placeholdersEnabled = false,
                        )
                    }
                    is PagingSource.LoadResult.Invalid -> break
                    is PagingSource.LoadResult.Error -> throw page.throwable
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            source.invalidate()
        }
    }
    return emptyList()
}
