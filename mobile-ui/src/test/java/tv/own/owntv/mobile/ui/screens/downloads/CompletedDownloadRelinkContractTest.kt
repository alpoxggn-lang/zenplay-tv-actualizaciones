package tv.own.owntv.mobile.ui.screens.downloads

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedDownloadRelinkContractTest {
    private fun source(relative: String) = File("src/main/java/$relative").readText()

    @Test
    fun `downloads list and playback resolve stable identity before access checks`() {
        val downloads = source("tv/own/owntv/mobile/ui/screens/downloads/DownloadsViewModel.kt")
        val tuner = source("tv/own/owntv/mobile/ui/screens/library/VodTuner.kt")

        assertTrue(downloads.contains("val resolved = itemResolver.resolve(d) ?: return false"))
        assertTrue(downloads.contains("getById(resolved.itemId)"))
        assertTrue(tuner.contains("downloadItemResolver.resolve(storedDownload)"))
        assertTrue(tuner.contains("downloadAllowed(download.mediaType, download.itemId, pid)"))
    }

    @Test
    fun `resolver pins relink to exact ownership and rejects ambiguous stream recovery`() {
        val resolver = File(
            "../OwnTV_Core/core/src/main/java/tv/own/owntv/core/download/DownloadItemResolver.kt",
        ).readText()

        assertTrue(resolver.contains("download.profileId < 0L || download.sourceId < 0L"))
        assertTrue(resolver.contains("movieDao.findByStreamUrl(download.sourceId, download.streamUrl)"))
        assertTrue(resolver.contains("seriesDao.findEpisodesByStreamUrl(download.sourceId, download.streamUrl)"))
        assertTrue(resolver.contains(".singleOrNull()"))
        assertTrue(resolver.contains("profileId = download.profileId"))
        assertTrue(resolver.contains("sourceId = download.sourceId"))
        assertTrue(resolver.contains("contentKey = download.contentKey"))
    }

    @Test
    fun `episode offline playback includes its current series id`() {
        val tuner = source("tv/own/owntv/mobile/ui/screens/library/VodTuner.kt")
        val method = tuner
            .substringAfter("suspend fun playDownload(downloadId: Long): Boolean")
            .substringBefore("suspend fun playRecording")

        assertTrue(method.contains("seriesDao.getEpisodeById(download.itemId)?.seriesId"))
        assertTrue(method.contains("seriesId = seriesId"))
    }
}
