package tv.own.owntv.mobile.zenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
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

    @Test
    fun protectedPlaybackStopsAtTheExactStoredExpiry() {
        val expiry = ZenPlayExpiryState.Dated(2_000L)

        assertEquals(true, allowsProtectedPlayback(expiry, 1_999L))
        assertEquals(false, allowsProtectedPlayback(expiry, 2_000L))
        assertEquals(false, allowsProtectedPlayback(expiry, 2_001L))
    }

    @Test
    fun onlyCurrentDatedOrUnlimitedEntitlementsOpenProtectedMedia() {
        assertEquals(true, allowsProtectedPlayback(ZenPlayExpiryState.Unlimited, 10L))
        assertEquals(false, allowsProtectedPlayback(ZenPlayExpiryState.Revoked, 10L))
        assertEquals(false, allowsProtectedPlayback(ZenPlayExpiryState.ExpiredWithoutDate, 10L))
        assertEquals(false, allowsProtectedPlayback(ZenPlayExpiryState.Unavailable, 10L))
        assertEquals(false, allowsProtectedPlayback(ZenPlayExpiryState.NoAccount, 10L))
    }

    @Test
    fun protectedEntitlementCacheIsScopedToBothProfileAndSource() {
        val first = SourceEntity(
            id = 11L,
            name = "First",
            type = SourceType.XTREAM,
            url = "https://one.invalid",
            username = "client",
            password = "secret",
        )
        val second = first.copy(id = 22L, name = "Second", url = "https://two.invalid")

        assertNotEquals(zenPlaySourceBinding(1L, first), zenPlaySourceBinding(1L, second))
        assertNotEquals(zenPlaySourceBinding(1L, first), zenPlaySourceBinding(2L, first))
    }

    @Test
    fun credentialRotationChangesTheProtectedEntitlementBinding() {
        val original = SourceEntity(
            id = 11L,
            name = "Original",
            type = SourceType.XTREAM,
            url = "https://panel.invalid",
            username = "client",
            password = "old-secret",
        )

        assertNotEquals(
            zenPlaySourceBinding(1L, original),
            zenPlaySourceBinding(1L, original.copy(password = "new-secret")),
        )
        assertNotEquals(
            zenPlaySourceBinding(1L, original),
            zenPlaySourceBinding(1L, original.copy(userAgent = "new-agent")),
        )
        assertNotEquals(
            zenPlaySourceBinding(1L, original),
            zenPlaySourceBinding(1L, original.copy(httpReferer = "https://new.invalid")),
        )
    }

    @Test
    fun accountSnapshotKeepsBindingAndQueriedSourcesFromOneSelection() {
        val first = SourceEntity(
            id = 11L,
            name = "First",
            type = SourceType.XTREAM,
            url = "https://one.invalid",
            username = "first",
            password = "secret-one",
        )
        val second = first.copy(
            id = 22L,
            name = "Second",
            url = "https://two.invalid",
            username = "second",
            password = "secret-two",
        )

        val snapshot = zenPlayAccountSnapshot(7L, listOf(first, second), activeSourceId = 22L)!!

        assertEquals(listOf(second), snapshot.sources)
        assertEquals(zenPlaySourceBinding(7L, second), snapshot.binding)
        assertNotEquals(
            snapshot.binding,
            zenPlayAccountSnapshot(7L, listOf(first, second.copy(password = "rotated")), 22L)?.binding,
        )
    }

    @Test
    fun m3uAndStalkerPlaybackDoNotDependOnXtreamExpiryEndpoint() {
        assertEquals(false, requiresXtreamEntitlement(SourceType.M3U))
        assertEquals(false, requiresXtreamEntitlement(SourceType.STALKER))
        assertEquals(true, requiresXtreamEntitlement(SourceType.XTREAM))
    }
}
