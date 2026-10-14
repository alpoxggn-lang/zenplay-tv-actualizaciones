package tv.own.owntv.mobile.playback

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipControllerTest {
    @Test
    fun `manual entry request is rejected until host advertises support`() = runBlocking {
        val controller = PipController()

        assertFalse(controller.requestManualEntry())

        controller.manualEntryAvailable.value = true
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            controller.manualEntryRequests.first()
        }
        assertTrue(controller.requestManualEntry())
        received.await()
    }
}
