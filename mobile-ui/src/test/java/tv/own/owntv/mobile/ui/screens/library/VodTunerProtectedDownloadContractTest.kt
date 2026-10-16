package tv.own.owntv.mobile.ui.screens.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards for the profile and entitlement boundary around decrypted offline playback. */
class VodTunerProtectedDownloadContractTest {
    private val source = File(
        "src/main/java/tv/own/owntv/mobile/ui/screens/library/VodTuner.kt",
    ).readText()
    private val accountSource = File(
        "src/main/java/tv/own/owntv/mobile/zenplay/ZenPlayAccount.kt",
    ).readText()

    @Test
    fun `protected playback is stopped when active profile changes`() {
        assertTrue(source.contains("private var playingProtectedDownload = false"))
        assertTrue(source.contains("settings.activeProfileId.distinctUntilChanged().collect"))
        assertTrue(source.contains("protectedPlaybackProfileGate.activeProfileChanged(activeProfileId)"))
        assertTrue(source.contains("private var protectedDownloadEntitlement: ZenPlayExpiryState? = null"))
        assertTrue(source.contains("!allowsProtectedPlayback(entitlement, System.currentTimeMillis())"))
        assertTrue(source.contains("private fun revokeProtectedPlayback()"))
        assertTrue(source.contains("protectedDownload = true"))
        assertTrue(source.contains("playingProtectedDownload = protectedDownload"))
        assertTrue(source.contains("playingProtectedDownload = false"))
    }

    @Test
    fun `download playback remains internal and verifies owner plus entitlement`() {
        val method = source
            .substringAfter("suspend fun playDownload(downloadId: Long): Boolean")
            .substringBefore("suspend fun playRecording")

        assertTrue(method.contains("downloadDao.getByIdForProfile(downloadId, pid)"))
        assertTrue(
            method.indexOf("downloadDao.getByIdForProfile(downloadId, pid)") <
                method.indexOf("zenPlayAccount.protectedPlaybackEntitlement(pid, source)"),
        )
        assertTrue(method.contains("sourceDao.observeForProfile(pid)"))
        assertTrue(method.contains("it.id == download.sourceId"))
        assertTrue(method.contains("zenPlayAccount.protectedPlaybackEntitlement(pid, source)"))
        assertTrue(method.contains("allowsProtectedPlayback(entitlement"))
        assertTrue(method.contains("protectedDownloads.open(fileId, owner)"))
        assertTrue(method.contains("protectedDownloads.contentUri(fileId)"))
        assertTrue(method.contains("cast.release(this)"))
        assertTrue(method.contains("val freshlyReadProfileId = settings.activeProfileId.first()"))
        assertTrue(method.contains("protectedPlaybackProfileGate.startIfCurrent(pid, freshlyReadProfileId)"))
        assertTrue(
            method.indexOf("protectedPlaybackProfileGate.startIfCurrent(pid, freshlyReadProfileId)") <
                method.indexOf("player.play("),
        )
    }

    @Test
    fun `valid exact source cache returns before any network wait`() {
        val method = accountSource
            .substringAfter("suspend fun protectedPlaybackEntitlement(")
            .substringBefore("private suspend fun loadExpiry")

        assertTrue(method.contains("readSourceCachedExpiry(binding)"))
        assertTrue(method.contains("if (cached != null && allowsProtectedPlayback(cached"))
        assertTrue(
            method.indexOf("if (cached != null && allowsProtectedPlayback(cached") <
                method.indexOf("withTimeoutOrNull(15_000L)"),
        )
    }
}
