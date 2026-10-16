package tv.own.owntv.mobile.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps direct mobile actions behind the same final content authorization policy as catalogues. */
class ContentAccessGateContractTest {
    private fun source(relative: String) = File("src/main/java/$relative").readText()

    @Test
    fun `vod playback external handoff and downloads use the final policy`() {
        val tuner = source("tv/own/owntv/mobile/ui/screens/library/VodTuner.kt")
        val detail = source("tv/own/owntv/mobile/ui/screens/library/DetailViewModel.kt")
        val actions = source("tv/own/owntv/mobile/ui/screens/ContentActions.kt")

        assertTrue(tuner.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(tuner.contains("if (!contentAccess.allows(pid, movie)) return false"))
        assertTrue(tuner.contains("if (!contentAccess.allows(pid, episode)) return false"))
        assertTrue(tuner.contains("if (episode.seriesId != show.id) return false"))
        assertTrue(detail.contains("if (episode.seriesId != current.id) return@launch"))
        assertTrue(detail.contains("if (!contentAccess.allows(pid, episode)) return@launch"))
        assertTrue(actions.contains("if (!contentAccess.allows(pid, movie)) return"))
        assertTrue(actions.contains("if (!contentAccess.allows(pid, show)) return"))
    }

    @Test
    fun `live tune preview catchup multiview direct number and recording use the final policy`() {
        val tuner = source("tv/own/owntv/mobile/ui/screens/live/LiveTuner.kt")
        val viewModel = source("tv/own/owntv/mobile/ui/screens/live/LiveViewModel.kt")
        val guide = source("tv/own/owntv/mobile/ui/screens/guide/GuideViewModel.kt")

        assertTrue(tuner.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(tuner.contains("if (!contentAccess.allows(profileId, ch)) return null"))
        assertTrue(tuner.contains("if (!contentAccess.allows(pid, channel)) return refuse()"))
        assertTrue(tuner.contains("filter { contentAccess.allows(c.profileId, it) }"))
        assertTrue(tuner.contains("candidates.filter { contentAccess.allows(ctx.value.profileId, it) }"))
        assertTrue(viewModel.contains("if (!contentAccess.allows(pid, channel)) return@launch"))
        assertTrue(guide.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(guide.contains("val storedChannel = authorizedStoredChannel(pid, channel) ?: return@launch"))
        assertTrue(guide.contains("channel = storedChannel"))
        assertTrue(guide.contains("recordings.addSeriesRule(pid, storedChannel, programme.title)"))
    }

    @Test
    fun `download list retry resume and export reauthorize each row`() {
        val downloads = source("tv/own/owntv/mobile/ui/screens/downloads/DownloadsViewModel.kt")

        assertTrue(downloads.contains("list.filter { canAccess(it, pid) }"))
        assertTrue(downloads.contains("val owned = downloadDao.getByIdForProfile(download.id, pid) ?: return@launch"))
        assertTrue(downloads.contains("if (!canAccess(owned, pid)) return@launch"))
        assertTrue(downloads.contains("if (canAccess(download, pid)) downloadManager.retry(download)"))
        assertTrue(downloads.contains("if (canAccess(download, pid)) downloadManager.resume(download)"))
    }
}
