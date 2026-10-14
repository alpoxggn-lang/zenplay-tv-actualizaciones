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
    val twoPane: Boolean,
    val reducedEffects: Boolean,
    val maxMultiviewTiles: Int,
)

fun DeviceCapabilities.toAdaptiveDeviceProfile(): AdaptiveDeviceProfile {
    val safeWidth = widthDp.coerceAtLeast(0)
    val windowClass = when {
        safeWidth < MEDIUM_WIDTH_DP -> ZenWindowClass.COMPACT
        safeWidth < EXPANDED_WIDTH_DP -> ZenWindowClass.MEDIUM
        else -> ZenWindowClass.EXPANDED
    }

    // A TV declaration wins even when an OEM incorrectly reports a touchscreen. For unclassified
    // boxes, a D-pad without touch is enough to select the ten-foot experience.
    val remoteFirst = televisionUi || (dpad && !touchscreen)
    val navigation = when {
        remoteFirst -> ZenNavigation.REMOTE
        windowClass == ZenWindowClass.COMPACT -> ZenNavigation.BOTTOM_BAR
        else -> ZenNavigation.RAIL
    }
    val reducedEffects = lowRam || legacyBuild

    return AdaptiveDeviceProfile(
        navigation = navigation,
        windowClass = windowClass,
        remoteFirst = remoteFirst,
        twoPane = !remoteFirst && windowClass == ZenWindowClass.EXPANDED,
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

