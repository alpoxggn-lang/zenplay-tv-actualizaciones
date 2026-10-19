package tv.own.owntv.mobile.ui.screens.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards for the mobile VOD orchestration around catalogue-compatible fallback. */
class VodTunerCompatibleVariantContractTest {
    private val tuner = File(
        "src/main/java/tv/own/owntv/mobile/ui/screens/library/VodTuner.kt",
    ).readText()
    private val nav = File(
        "src/main/java/tv/own/owntv/mobile/ui/nav/MobileNavHost.kt",
    ).readText()

    @Test
    fun `resolver is limited to terminal movie and episode failures`() {
        val collector = tuner
            .substringAfter("// Catalogue lookup is deliberately armed only")
            .substringBefore("// The player advances a queue by itself")

        assertTrue(collector.contains("isCompatibleVariantRecoveryFailure"))
        assertTrue(collector.contains("setOf(MediaType.MOVIE, MediaType.EPISODE)"))
        assertTrue(collector.contains("playingProtectedDownload"))
        assertTrue(collector.contains("compatibleVariants.findMovie"))
        assertTrue(collector.contains("compatibleVariants.findEpisode"))
        assertFalse(collector.contains("MediaType.LIVE ->"))
    }

    @Test
    fun `switch preserves logical identity and authoritative position`() {
        val switch = tuner
            .substringAfter("suspend fun playCompatibleVersion(): Boolean")
            .substringBefore("suspend fun playDownload")

        assertTrue(switch.contains("player.replacementResumePositionMs(replacementDurationMs)"))
        assertTrue(switch.contains("saveProgress()"))
        assertTrue(switch.contains("itemId = selected.original.id"))
        assertTrue(switch.contains("itemId = selected.originalEpisode.id"))
        assertTrue(switch.contains("trackKey = logicalTrackKey"))
        assertTrue(switch.contains("recordHistory(profileId, MediaType.MOVIE, selected.original.id)"))
        assertTrue(
            switch.contains(
                "recordHistory(profileId, MediaType.EPISODE, selected.originalEpisode.id)",
            ),
        )
    }

    @Test
    fun `suspended fallback cannot replace a newer playback or profile`() {
        val movieSwitch = tuner
            .substringAfter("private suspend fun playCompatibleMovie(")
            .substringBefore("private suspend fun playCompatibleEpisode(")
        val episodeSwitch = tuner
            .substringAfter("private suspend fun playCompatibleEpisode(")
            .substringBefore("suspend fun playDownload")
        val stableGuard = tuner
            .substringAfter("private fun captureCompatiblePlaybackGuard(")
            .substringBefore("private fun compatibleMovieLabel")

        assertTrue(tuner.contains("val guard = captureCompatiblePlaybackGuard(current)"))
        assertTrue(stableGuard.contains("playbackIntentGeneration == guard.generation"))
        assertTrue(stableGuard.contains("_playing.value == guard.playback"))
        assertTrue(stableGuard.contains("playingProfileId == guard.profileId"))
        assertTrue(stableGuard.contains("player.currentMediaUrl == guard.mediaUrl"))
        assertTrue(stableGuard.contains("player.currentMediaContentKey == guard.mediaContentKey"))
        assertTrue(stableGuard.contains("activeProfileId == guard.profileId"))
        assertTrue(movieSwitch.contains("compatiblePlaybackStillCurrent(guard)"))
        assertTrue(episodeSwitch.contains("compatiblePlaybackStillCurrent(guard)"))
        assertTrue(
            movieSwitch.indexOf("compatiblePlaybackStillCurrent(guard)") <
                movieSwitch.indexOf("player.play("),
        )
        assertTrue(
            episodeSwitch.indexOf("compatiblePlaybackStillCurrent(guard)") <
                episodeSwitch.indexOf("player.playEpisodes("),
        )
        assertTrue(
            tuner.substringAfter("private fun resetCompatibleAlternatives()")
                .substringBefore("private fun captureCompatiblePlaybackGuard")
                .contains("playbackIntentGeneration += 1L"),
        )
        assertTrue(
            tuner.substringAfter("private fun beginPlaybackState(")
                .substringBefore("private suspend fun recordHistory")
                .contains("playbackIntentGeneration += 1L"),
        )
    }

    @Test
    fun `episode replacement is paired exactly and failed rows cannot loop`() {
        val switch = tuner
            .substringAfter("private suspend fun playCompatibleEpisode(")
            .substringBefore("suspend fun playDownload")

        assertTrue(switch.contains("replacement.seasonNumber to replacement.episodeNumber"))
        assertTrue(switch.contains("logical.seasonNumber to logical.episodeNumber"))
        assertTrue(switch.contains("replacementsByNumber[number] ?: logical"))
        assertTrue(switch.contains("if (startIndex < 0) return false"))
        assertFalse(switch.contains("filter { it.seasonNumber == selected.originalEpisode.seasonNumber }"))
        assertTrue(switch.contains("playbackContentKeys = playbackKeys"))
        assertTrue(switch.contains("playbackEpisodeIds = paired.map { it.second.id }"))
        assertTrue(tuner.contains("attemptedMovieIds + it.id"))
        assertTrue(tuner.contains("attemptedEpisodeIds + originalEpisode.id"))
    }

    @Test
    fun `player route supplies the alternative and confirmed callback`() {
        assertTrue(nav.contains("vodTuner.compatibleAlternative.collectAsStateWithLifecycle()"))
        assertTrue(nav.contains("compatibleAlternativeLabel = compatibleAlternative?.label"))
        assertTrue(nav.contains("vodTuner.playCompatibleVersion()"))
    }
}
