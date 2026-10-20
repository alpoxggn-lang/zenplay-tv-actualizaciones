package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MpvAvSyncPolicyTest {
    @Test
    fun `direct rendering always keeps video locked to audio`() {
        listOf(false, true).forEach { live ->
            listOf(false, true).forEach { broken ->
                val profile = MpvAvSyncPolicy.resolve(live, broken, directRender = true)
                assertEquals("audio", profile.videoSync)
                assertEquals("decoder+vo", profile.frameDrop)
                assertNotEquals("desync", profile.videoSync)
            }
        }
    }

    @Test
    fun `gpu rendering uses display clock until nominal timestamp fallback is needed`() {
        val healthy = MpvAvSyncPolicy.resolve(isLive = true, brokenVideoTimestamps = false, directRender = false)
        val repaired = MpvAvSyncPolicy.resolve(isLive = true, brokenVideoTimestamps = true, directRender = false)
        assertEquals("display-resample", healthy.videoSync)
        assertEquals("audio", repaired.videoSync)
        assertEquals("decoder+vo", repaired.frameDrop)
    }

    @Test
    fun `nominal timestamp fallback is limited to repeatedly broken live playback`() {
        assertEquals(
            "no",
            MpvAvSyncPolicy.resolve(isLive = true, brokenVideoTimestamps = true, directRender = true).correctPts,
        )
        assertEquals(
            "yes",
            MpvAvSyncPolicy.resolve(isLive = false, brokenVideoTimestamps = true, directRender = true).correctPts,
        )
    }

    @Test
    fun `no playback profile can free run picture away from sound`() {
        listOf(false, true).forEach { live ->
            listOf(false, true).forEach { broken ->
                listOf(false, true).forEach { direct ->
                    val profile = MpvAvSyncPolicy.resolve(live, broken, direct)
                    assertNotEquals("desync", profile.videoSync)
                    assertNotEquals("no", profile.frameDrop)
                    if (!(live && broken)) assertEquals("yes", profile.correctPts)
                }
            }
        }
    }

    @Test
    fun `audio clock smoothing uses the documented stable window`() {
        assertEquals(30, MpvAvSyncPolicy.AUTO_SYNC_SAMPLES)
    }
}
