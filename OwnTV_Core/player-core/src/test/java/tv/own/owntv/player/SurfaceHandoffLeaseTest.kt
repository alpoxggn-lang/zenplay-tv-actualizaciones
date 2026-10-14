package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceHandoffLeaseTest {
    @Test
    fun `incoming surface cancels outgoing delayed detach`() {
        val lease = SurfaceHandoffLease<Any>()
        val old = Any()
        val replacement = Any()

        lease.attach(old)
        val ticket = lease.requestDetach(old)!!
        lease.attach(replacement)

        assertFalse(lease.commit(ticket))
        assertSame(replacement, lease.current)
    }

    @Test
    fun `surface is detached when no replacement arrives`() {
        val lease = SurfaceHandoffLease<Any>()
        val surface = Any()

        lease.attach(surface)
        val ticket = lease.requestDetach(surface)!!

        assertTrue(lease.commit(ticket))
        assertNull(lease.current)
    }

    @Test
    fun `stale view cannot detach the current surface`() {
        val lease = SurfaceHandoffLease<Any>()
        val stale = Any()
        val current = Any()

        lease.attach(stale)
        lease.attach(current)

        assertNull(lease.requestDetach(stale))
        assertSame(current, lease.current)
    }

    @Test
    fun `attach S1 then S2 and late destroy S1 preserves S2`() {
        val lease = SurfaceHandoffLease<Any>()
        val s1 = Any()
        val s2 = Any()

        lease.attach(s1)
        lease.attach(s2)
        val lateDestroyS1 = lease.requestDetach(s1)

        assertNull(lateDestroyS1)
        assertSame(s2, lease.current)
    }
}
