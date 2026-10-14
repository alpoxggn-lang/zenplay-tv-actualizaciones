package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps a late SurfaceView callback from detaching the replacement TV video surface. */
class MpvVideoSurfaceContractTest {
    @Test
    fun `destroy passes the exact outgoing surface to the player`() {
        val source = File("src/main/java/tv/own/owntv/player/MpvVideoSurface.kt").readText()
        val view = source.substringAfter("private class MpvSurfaceView")
            .substringBefore("/**\n * Hosts the mpv video output")
        val destroyed = view.substringAfter("override fun surfaceDestroyed")
            .substringBefore("}\n}")

        assertTrue(destroyed.contains("player.detachSurface(holder.surface)"))
        assertFalse(destroyed.contains("player.detachSurface()"))
    }
}
