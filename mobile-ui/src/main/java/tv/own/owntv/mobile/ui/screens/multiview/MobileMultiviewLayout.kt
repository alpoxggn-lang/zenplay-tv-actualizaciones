package tv.own.owntv.mobile.ui.screens.multiview

import kotlin.math.ceil
import kotlin.math.sqrt

/** Every Multiview slot is a television frame, never a box that stretches to the window. */
internal const val MOBILE_MULTIVIEW_FRAME_ASPECT = 16f / 9f

internal data class MobileMultiviewGridSpec(
    val columns: Int,
    val rows: Int,
)

internal data class MobileMultiviewCellSize(
    val width: Float,
    val height: Float,
)

/**
 * Pick a grid for the current orientation.
 *
 * Two streams use the whole useful axis: stacked on a phone held upright and side by side when it
 * is turned. Three and four streams use a stable 2 x 2 grid, so rotating the device never changes
 * channel order or produces one oversized, distorted tile. The generic tail is defensive for a
 * future device profile that permits more than four streams.
 */
internal fun mobileMultiviewGridSpec(tileCount: Int, landscape: Boolean): MobileMultiviewGridSpec {
    val count = tileCount.coerceAtLeast(1)
    return when {
        count == 1 -> MobileMultiviewGridSpec(columns = 1, rows = 1)
        count == 2 && landscape -> MobileMultiviewGridSpec(columns = 2, rows = 1)
        count == 2 -> MobileMultiviewGridSpec(columns = 1, rows = 2)
        count <= 4 -> MobileMultiviewGridSpec(columns = 2, rows = 2)
        else -> {
            val preferredColumns = if (landscape) ceil(sqrt(count.toFloat())).toInt() else 2
            MobileMultiviewGridSpec(
                columns = preferredColumns,
                rows = ceil(count.toFloat() / preferredColumns).toInt(),
            )
        }
    }
}

/**
 * Largest 16:9 cell that fits the whole grid inside the available window.
 *
 * Returning the actual cell size instead of applying weights is important: weighted rows consume
 * spare height and silently turn a 16:9 stream into a tall rectangle on portrait phones.
 */
internal fun mobileMultiviewCellSize(
    availableWidth: Float,
    availableHeight: Float,
    spec: MobileMultiviewGridSpec,
    gap: Float,
): MobileMultiviewCellSize {
    val usableWidth = (availableWidth - gap * (spec.columns - 1)).coerceAtLeast(0f)
    val usableHeight = (availableHeight - gap * (spec.rows - 1)).coerceAtLeast(0f)
    val widthFromColumns = usableWidth / spec.columns
    val heightFromRows = usableHeight / spec.rows
    val width = minOf(widthFromColumns, heightFromRows * MOBILE_MULTIVIEW_FRAME_ASPECT)
        .coerceAtLeast(0f)
    return MobileMultiviewCellSize(width = width, height = width / MOBILE_MULTIVIEW_FRAME_ASPECT)
}

/**
 * Contain the decoded picture inside a cell without crop or stretch.
 *
 * [videoAspect] is already pixel-aspect-ratio corrected by [LivePreviewEngine]. Until the first
 * video-size callback arrives the 16:9 frame itself is the safe fallback, avoiding a resize flash.
 */
internal fun mobileMultiviewVideoSize(
    containerWidth: Float,
    containerHeight: Float,
    videoAspect: Float?,
): MobileMultiviewCellSize {
    if (containerWidth <= 0f || containerHeight <= 0f) {
        return MobileMultiviewCellSize(width = 0f, height = 0f)
    }
    val aspect = videoAspect?.takeIf { it > 0f } ?: MOBILE_MULTIVIEW_FRAME_ASPECT
    val containerAspect = containerWidth / containerHeight
    return if (aspect >= containerAspect) {
        MobileMultiviewCellSize(width = containerWidth, height = containerWidth / aspect)
    } else {
        MobileMultiviewCellSize(width = containerHeight * aspect, height = containerHeight)
    }
}
