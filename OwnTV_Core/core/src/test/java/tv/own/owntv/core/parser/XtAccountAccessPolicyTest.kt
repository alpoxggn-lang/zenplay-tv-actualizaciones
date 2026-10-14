package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class XtAccountAccessPolicyTest {
    @Test
    fun rejectedAuthWinsOverFutureExpiry() {
        val details = XtreamClient.XtAccountDetails(
            expiryMs = 9_999_999L,
            status = "Active",
            authOk = false,
        )

        assertEquals(XtAccountAccess.REVOKED, details.accountAccess())
    }

    @Test
    fun revokedAndExpiredStatusesAreNotActive() {
        listOf("Banned", "Disabled", "Invalid", "Suspended").forEach { status ->
            assertEquals(
                status,
                XtAccountAccess.REVOKED,
                XtreamClient.XtAccountDetails(status = status).accountAccess(),
            )
        }
        assertEquals(
            XtAccountAccess.EXPIRED,
            XtreamClient.XtAccountDetails(status = "Expired").accountAccess(),
        )
    }

    @Test
    fun activeOrOmittedStatusRemainsUsable() {
        assertEquals(
            XtAccountAccess.ACTIVE,
            XtreamClient.XtAccountDetails(status = "Active").accountAccess(),
        )
        assertEquals(XtAccountAccess.ACTIVE, XtreamClient.XtAccountDetails().accountAccess())
    }
}

