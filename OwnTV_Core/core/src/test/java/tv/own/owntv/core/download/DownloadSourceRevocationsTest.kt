package tv.own.owntv.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceRevocationsTest {
    @Test
    fun `rotation hold is released without a permanent tombstone`() {
        val revocations = DownloadSourceRevocations()

        revocations.holdForRotation(17L)
        assertFalse(revocations.accepts(17L))

        revocations.releaseRotation(17L)
        assertTrue(revocations.accepts(17L))
    }

    @Test
    fun `permanent deletion survives release of a concurrent rotation`() {
        val revocations = DownloadSourceRevocations()

        revocations.holdForRotation(17L)
        revocations.revokePermanently(17L)
        revocations.releaseRotation(17L)

        assertFalse(revocations.accepts(17L))
    }

    @Test
    fun `overlapping rotations require every hold to finish`() {
        val revocations = DownloadSourceRevocations()

        revocations.holdForRotation(17L)
        revocations.holdForRotation(17L)
        revocations.releaseRotation(17L)
        assertFalse(revocations.accepts(17L))

        revocations.releaseRotation(17L)
        assertTrue(revocations.accepts(17L))
    }

    @Test
    fun `entitlement gate reopens after confirmed renewal`() {
        val revocations = DownloadSourceRevocations()

        revocations.revokeEntitlement(17L)
        assertFalse(revocations.accepts(17L))

        revocations.restoreEntitlement(17L)
        assertTrue(revocations.accepts(17L))
    }

    @Test
    fun `entitlement renewal cannot undo permanent source deletion`() {
        val revocations = DownloadSourceRevocations()

        revocations.revokeEntitlement(17L)
        revocations.revokePermanently(17L)
        revocations.restoreEntitlement(17L)

        assertFalse(revocations.accepts(17L))
    }
}
