package tv.own.owntv.platform

/**
 * Capabilities that affect layout and feature cost.
 *
 * ZenPlay deliberately does not decide its UI from a marketing device name. A television with a
 * touch screen, a phone connected to a remote and a tablet in split screen are all possible. The
 * shell resolves the actual window, input and memory characteristics instead.
 */
data class DeviceCapabilities(
    val widthDp: Int,
    val heightDp: Int,
    /** Stable smallest window width: distinguishes a rotated phone from a landscape tablet. */
    val smallestWidthDp: Int,
    val televisionUi: Boolean,
    val touchscreen: Boolean,
    val dpad: Boolean,
    val lowRam: Boolean,
    val legacyBuild: Boolean,
)

enum class ZenNavigation {
    REMOTE,
    BOTTOM_BAR,
    RAIL,
}

enum class ZenWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

data class AdaptiveDeviceProfile(
    val navigation: ZenNavigation,
    val windowClass: ZenWindowClass,
    val remoteFirst: Boolean,
    val compactHandset: Boolean,
    val twoPane: Boolean,
    val reducedEffects: Boolean,
    val maxMultiviewTiles: Int,
)

fun DeviceCapabilities.toAdaptiveDeviceProfile(): AdaptiveDeviceProfile {
    val safeWidth = widthDp.coerceAtLeast(0)
    val safeHeight = heightDp.coerceAtLeast(0)
    val windowClass = when {
        safeWidth < MEDIUM_WIDTH_DP -> ZenWindowClass.COMPACT
        safeWidth < EXPANDED_WIDTH_DP -> ZenWindowClass.MEDIUM
        else -> ZenWindowClass.EXPANDED
    }

    // A TV declaration wins even when an OEM incorrectly reports a touchscreen. For unclassified
    // boxes, a D-pad without touch is enough to select the ten-foot experience.
    val remoteFirst = televisionUi || (dpad && !touchscreen)
    val compactHandset = !remoteFirst && smallestWidthDp.coerceAtLeast(0) < MEDIUM_WIDTH_DP
    val navigation = when {
        remoteFirst -> ZenNavigation.REMOTE
        // A phone's browse experience stays portrait. During the short rotation transition it must
        // not turn into the tablet rail merely because its current width is temporarily > 600 dp.
        compactHandset -> ZenNavigation.BOTTOM_BAR
        // ZenPlay's tablet portrait layout keeps the same reachable bottom navigation as a phone.
        // Rotating the same window to landscape moves those destinations into the compact rail,
        // leaving the wider content area available for two-pane screens.
        windowClass == ZenWindowClass.COMPACT || safeHeight > safeWidth -> ZenNavigation.BOTTOM_BAR
        else -> ZenNavigation.RAIL
    }
    val reducedEffects = lowRam || legacyBuild

    return AdaptiveDeviceProfile(
        navigation = navigation,
        windowClass = windowClass,
        remoteFirst = remoteFirst,
        compactHandset = compactHandset,
        twoPane = !remoteFirst && !compactHandset && windowClass == ZenWindowClass.EXPANDED,
        reducedEffects = reducedEffects,
        maxMultiviewTiles = when {
            lowRam -> 1
            legacyBuild -> 2
            else -> 4
        },
    )
}

private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840

