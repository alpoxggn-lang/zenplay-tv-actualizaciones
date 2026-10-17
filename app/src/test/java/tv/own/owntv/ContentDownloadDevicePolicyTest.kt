package tv.own.owntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDownloadDevicePolicyTest {
    @Test
    fun `phone and tablet touch shells run the real download worker`() {
        assertTrue(contentDownloadsEnabled(televisionUi = false, touchscreen = true))
    }

    @Test
    fun `android tv and fire tv never enable offline content downloads`() {
        assertFalse(contentDownloadsEnabled(televisionUi = true, touchscreen = false))
        // Some TV OEMs incorrectly advertise touch; an explicit television declaration still wins.
        assertFalse(contentDownloadsEnabled(televisionUi = true, touchscreen = true))
    }

    @Test
    fun `unclassified remote boxes stay on the television policy`() {
        assertFalse(contentDownloadsEnabled(televisionUi = false, touchscreen = false))
    }

    @Test
    fun `download worker is delegated on mobile and swallowed only on television`() {
        val downloadWorker = "tv.own.owntv.core.download.DownloadWorker"
        assertFalse(shouldDisableContentDownloadWorker(downloadWorker, downloadsEnabled = true))
        assertTrue(shouldDisableContentDownloadWorker(downloadWorker, downloadsEnabled = false))
        assertFalse(
            shouldDisableContentDownloadWorker(
                "tv.own.owntv.core.sync.work.CatalogSyncWorker",
                downloadsEnabled = false,
            ),
        )
    }
}
