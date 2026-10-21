package tv.own.owntv.core.games

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Safe bridge to the separately distributed games application.
 *
 * Emulator engines, BIOS files and game images deliberately stay out of the streaming APK. This
 * keeps their licensing and storage lifecycle isolated while presenting one clear Games door in
 * every ZenPlay shell.
 */
object GamesCompanion {
    const val PACKAGE_NAME = "com.zenplay.games"
    const val RELEASES_URL = "https://github.com/alpoxggn-lang/zenplay-juegos/releases/latest"

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) != null

    fun open(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    fun openInstallPage(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
