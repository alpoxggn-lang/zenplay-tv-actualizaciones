package tv.own.owntv.core.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

class SourceProtectedDownloadIdentityTest {
    private val source = SourceEntity(
        id = 17L,
        name = "Living room",
        type = SourceType.XTREAM,
        url = "https://provider.example",
        username = "first-user",
        password = "first-password",
    )

    @Test
    fun `cosmetic and playback preference edits retain protected downloads`() {
        val cosmeticEdits = listOf(
            source.copy(name = "Bedroom"),
            source.copy(epgUrl = "https://provider.example/guide.xml"),
            source.copy(syncLive = false, syncMovies = false, syncSeries = false),
            source.copy(preferHls = true),
            source.copy(livePrerollSecs = 12),
            source.copy(liveEnginePreference = "MPV"),
            source.copy(vodEnginePreference = "MEDIA3"),
            source.copy(lastSyncAt = 1234L),
        )

        cosmeticEdits.forEach { edited ->
            assertFalse(source.hasProtectedDownloadIdentityChange(edited))
        }
    }

    @Test
    fun `provider account identity edits revoke protected downloads`() {
        val identityEdits = listOf(
            source.copy(type = SourceType.M3U),
            source.copy(url = "https://another-provider.example"),
            source.copy(username = "second-user"),
            source.copy(password = "second-password"),
            source.copy(userAgent = "provider-specific-agent"),
            source.copy(httpReferer = "https://provider.example/watch"),
            source.copy(mac = "00:11:22:33:44:55"),
            source.copy(stalkerSerialNumber = "serial-2"),
            source.copy(stalkerDeviceId = "device-2"),
            source.copy(stalkerDeviceId2 = "device-2b"),
            source.copy(stalkerSignature = "signature-2"),
        )

        identityEdits.forEach { edited ->
            assertTrue(source.hasProtectedDownloadIdentityChange(edited))
        }
    }

    @Test
    fun `stale cosmetic update keeps the current provider identity`() {
        val current = source.copy(
            url = "https://new-provider.example",
            username = "second-user",
            password = "second-password",
            userAgent = "provider-specific-agent",
            httpReferer = "https://provider.example/watch",
            mac = "00:11:22:33:44:55",
            stalkerSerialNumber = "serial-2",
            stalkerDeviceId = "device-2",
            stalkerDeviceId2 = "device-2b",
            stalkerSignature = "signature-2",
        )
        val staleRequest = source.copy(name = "Bedroom")

        val merged = staleRequest.withProtectedDownloadIdentityFrom(current)

        assertEquals("Bedroom", merged.name)
        assertFalse(current.hasProtectedDownloadIdentityChange(merged))
        assertEquals(current.url, merged.url)
        assertEquals(current.username, merged.username)
        assertEquals(current.password, merged.password)
        assertEquals(current.userAgent, merged.userAgent)
        assertEquals(current.httpReferer, merged.httpReferer)
        assertEquals(current.mac, merged.mac)
        assertEquals(current.stalkerSerialNumber, merged.stalkerSerialNumber)
        assertEquals(current.stalkerDeviceId, merged.stalkerDeviceId)
        assertEquals(current.stalkerDeviceId2, merged.stalkerDeviceId2)
        assertEquals(current.stalkerSignature, merged.stalkerSignature)
    }
}
