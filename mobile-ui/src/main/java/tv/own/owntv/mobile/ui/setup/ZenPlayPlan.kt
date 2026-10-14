package tv.own.owntv.mobile.ui.setup

import androidx.annotation.StringRes
import tv.own.owntv.mobile.R

/** The single ZenPlay service. Account credentials are entered by the viewer, never bundled. */
enum class ZenPlayPlan(@param:StringRes val labelRes: Int, val serverUrl: String) {
    ZENPLAY(R.string.zenplay_plan_zenplay, "https://dns.ruta5.xyz:8443/"),
}
