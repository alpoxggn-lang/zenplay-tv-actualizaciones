package tv.own.owntv.mobile.zenplay

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.parser.XtreamClient

class ZenPlayAccountTest {
    @Test
    fun allNetworkFailuresBecomeExplicitUnavailableState() {
        val result = classifyZenPlayExpiry(
            hasAccount = true,
            responses = listOf(Result.failure(IllegalStateException("offline"))),
        )

        assertEquals(ZenPlayExpiryState.Unavailable, result)
    }

    @Test
    fun missingAccountDoesNotRemainLoading() {
        assertEquals(
            ZenPlayExpiryState.NoAccount,
            classifyZenPlayExpiry(hasAccount = false, responses = emptyList()),
        )
    }

    @Test
    fun validExpiryWinsOverUnlimitedResponses() {
        val dated = XtreamClient.XtAccountDetails(
            status = "Active",
            expiryMs = 1_800_000L,
            maxConnections = 1,
        )
        val unlimited = XtreamClient.XtAccountDetails(
            status = "Active",
            expiryMs = null,
            maxConnections = 1,
        )

        assertEquals(
            ZenPlayExpiryState.Dated(1_800_000L),
            classifyZenPlayExpiry(
                hasAccount = true,
                responses = listOf(Result.success(unlimited), Result.success(dated)),
            ),
        )
    }

    @Test
    fun rejectedAuthWinsOverFutureExpiry() {
        val rejected = XtreamClient.XtAccountDetails(
            status = "Active",
            authOk = false,
            expiryMs = Long.MAX_VALUE,
        )

        assertEquals(
            ZenPlayExpiryState.Revoked,
            classifyZenPlayExpiry(true, listOf(Result.success(rejected))),
        )
    }

    @Test
    fun expiredStatusWinsOverFutureExpiry() {
        val expired = XtreamClient.XtAccountDetails(
            status = "Expired",
            expiryMs = Long.MAX_VALUE,
        )

        assertEquals(
            ZenPlayExpiryState.ExpiredWithoutDate,
            classifyZenPlayExpiry(true, listOf(Result.success(expired))),
        )
    }
}
