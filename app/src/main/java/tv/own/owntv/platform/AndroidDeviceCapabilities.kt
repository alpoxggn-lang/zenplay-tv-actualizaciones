package tv.own.owntv.platform

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Reads only stable platform capabilities; window dimensions are supplied by the Compose host. */
fun Context.resolveDeviceCapabilities(
    widthDp: Int,
    heightDp: Int,
    legacyBuild: Boolean,
): DeviceCapabilities {
    val packageManager = packageManager
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val televisionUi =
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val touchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    return DeviceCapabilities(
        widthDp = widthDp,
        heightDp = heightDp,
        televisionUi = televisionUi,
        touchscreen = touchscreen,
        dpad = televisionUi || !touchscreen,
        lowRam = activityManager?.isLowRamDevice == true,
        legacyBuild = legacyBuild,
    )
}

