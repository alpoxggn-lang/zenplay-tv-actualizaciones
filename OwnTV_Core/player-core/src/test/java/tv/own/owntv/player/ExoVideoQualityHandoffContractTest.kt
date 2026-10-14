package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the manual-quality state while a VOD changes renderer from mpv to ExoPlayer. */
class ExoVideoQualityHandoffContractTest {
    @Test
    fun `same item quality is handed to Exo after start and an unavailable track returns HUD to Auto`() {
        val player = File("src/main/java/tv/own/owntv/player/OwnTVPlayer.kt").readText()
        val startExo = player
            .substringAfter("private fun startExo(")
            .substringBefore("private fun restartExoOnFreshSurface(")

        val start = startExo.indexOf("engine.start(")
        val handoff = startExo.indexOf("engine.selectVideoQuality(qualityForItem.height)")
        assertTrue("quality must be applied after Exo has the current URL", start >= 0 && handoff > start)
        assertTrue(startExo.contains("qualityPickUrl = qualityForItem.itemUrl"))
        assertTrue(startExo.contains("_videoQualityPick.value = qualityForItem.height"))

        val exo = File("src/main/java/tv/own/owntv/player/ExoSubtitleEngine.kt").readText()
        val tracksChanged = exo
            .substringAfter("override fun onTracksChanged(tracks: Tracks)")
            .substringBefore("override fun onPlayerError")
        assertTrue(tracksChanged.contains("tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }"))
        assertTrue(tracksChanged.contains("VideoQuality.overrideFor(tracks, requested) == null"))
        assertTrue(tracksChanged.contains("applyVideoQuality()"))
        assertTrue(tracksChanged.contains("callbacks.onVideoQualityPick(null)"))

        val callback = player
            .substringAfter("override fun onVideoQualityPick(height: Int?)")
            .substringBefore("override fun onAudioOnlyMedia")
        assertTrue(callback.contains("_videoQualityPick.value = height"))
    }

    @Test
    fun `opening title B after a manual quality on title A resets renderer and HUD to Auto`() {
        val titleA = VideoQualityPickState(itemUrl = "https://provider/movie/A", height = 720)

        val sameTitle = titleA.forItem("https://provider/movie/A")
        assertEquals(720, sameTitle.height)

        val titleB = sameTitle.forItem("https://provider/movie/B")
        assertEquals("https://provider/movie/B", titleB.itemUrl)
        assertNull(titleB.height)
    }
}
