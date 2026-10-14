package tv.own.owntv

import android.os.Build
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPipPolicyTest {
    @Test
    fun `manual pip requires api touch shell system feature and manifest declaration`() {
        assertTrue(manualPipSupported(Build.VERSION_CODES.O, true, true, true))
        assertFalse(manualPipSupported(Build.VERSION_CODES.O - 1, true, true, true))
        assertFalse(manualPipSupported(Build.VERSION_CODES.O, false, true, true))
        assertFalse(manualPipSupported(Build.VERSION_CODES.O, true, false, true))
        assertFalse(manualPipSupported(Build.VERSION_CODES.O, true, true, false))
    }

    @Test
    fun `every launcher activity declares picture in picture support`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val launchers = Regex(
            """<activity\s+.*?</activity>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(manifest).map { it.value }.filter {
            it.contains("android.intent.category.LAUNCHER")
        }.toList()

        assertTrue(launchers.isNotEmpty())
        launchers.forEach { activity ->
            assertTrue(activity.contains("android:supportsPictureInPicture=\"true\""))
        }
    }
}
