package tv.own.owntv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDialogDecisionGateTest {

    @Test
    fun `only the first resume choice can claim the dialog`() {
        val gate = ResumeDialogDecisionGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }
}
