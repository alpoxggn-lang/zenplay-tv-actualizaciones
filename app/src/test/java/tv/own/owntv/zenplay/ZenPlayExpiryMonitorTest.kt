package tv.own.owntv.zenplay

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient

class ZenPlayExpiryMonitorTest {
    private val zone = ZoneId.of("America/Tijuana")

    @Test
    fun warningWindowUsesCalendarDaysNotTwentyFourHourBuckets() {
        val now = LocalDate.of(2026, 10, 14).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val tomorrow = LocalDate.of(2026, 10, 15).atTime(0, 5).atZone(zone).toInstant().toEpochMilli()

        assertEquals(1L, daysUntilExpiry(now, tomorrow, zone))
    }

    @Test
    fun expiryDayIsDayZero() {
        val now = LocalDate.of(2026, 10, 14).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val later = LocalDate.of(2026, 10, 14).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(0L, daysUntilExpiry(now, later, zone))
    }

    @Test
    fun datesOutsideWarningWindowRemainOutside() {
        val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val later = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(3L, daysUntilExpiry(now, later, zone))
    }

    @Test
    fun revokedAccountWinsOverFutureDate() {
        val result = classifyZenPlaySubscription(
            hasAccount = true,
            responses = listOf(
                Result.success(
                    XtreamClient.XtAccountDetails(
                        status = "Banned",
                        authOk = true,
                        expiryMs = Long.MAX_VALUE,
                    ),
                ),
            ),
        )

        assertEquals(ZenPlaySubscriptionState.Revoked, result)
    }

    @Test
    fun renewedFutureDateCancelsObsoleteWarning() {
        val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val renewed = LocalDate.of(2027, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(
            false,
            shouldKeepExpiryNotification(ZenPlaySubscriptionState.Dated(renewed), now, zone),
        )
    }

    @Test
    fun tomorrowStillKeepsExpiryWarning() {
        val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = LocalDate.of(2026, 10, 15).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(
            true,
            shouldKeepExpiryNotification(ZenPlaySubscriptionState.Dated(tomorrow), now, zone),
        )
    }

    @Test
    fun expiredSourceDoesNotRemoveAnotherProfilesValidSource() = runBlocking {
        val now = 10_000L
        val revoked = mutableListOf<Long>()
        val restored = mutableListOf<Long>()
        val sources = listOf(
            SourceSubscriptionResult(11L, mapOf(1L to "a"), ZenPlaySubscriptionState.Dated(now)),
            SourceSubscriptionResult(22L, mapOf(2L to "b"), ZenPlaySubscriptionState.Dated(now + 1L)),
        )

        enforceProtectedDownloadEntitlements(
            sources = sources,
            nowMs = now,
            applyIfBindingCurrent = { _, action -> action(); true },
            revokeForSource = { revoked += it },
            restoreForSource = { restored += it },
        )

        assertEquals(listOf(11L), revoked)
        assertEquals(listOf(22L), restored)
    }

    @Test
    fun unavailableStateNeitherPurgesNorReopensEntitlementGate() = runBlocking {
        val now = 10_000L
        val revoked = mutableListOf<Long>()
        val restored = mutableListOf<Long>()
        val sources = listOf(
            SourceSubscriptionResult(11L, mapOf(1L to "a"), ZenPlaySubscriptionState.Unavailable),
            SourceSubscriptionResult(22L, mapOf(2L to "b"), ZenPlaySubscriptionState.NoAccount),
        )

        enforceProtectedDownloadEntitlements(
            sources = sources,
            nowMs = now,
            applyIfBindingCurrent = { _, action -> action(); true },
            revokeForSource = { revoked += it },
            restoreForSource = { restored += it },
        )

        assertEquals(emptyList<Long>(), revoked)
        assertEquals(emptyList<Long>(), restored)
    }

    @Test
    fun staleCredentialResponseCannotRevokeOrRestoreReplacementAccount() = runBlocking {
        val actions = mutableListOf<String>()
        val stale = SourceSubscriptionResult(
            sourceId = 11L,
            profileBindings = mapOf(1L to "old-binding"),
            state = ZenPlaySubscriptionState.Unlimited,
        )

        enforceProtectedDownloadEntitlements(
            sources = listOf(stale),
            nowMs = 10_000L,
            applyIfBindingCurrent = { _, _ -> false },
            revokeForSource = { actions += "revoke:$it" },
            restoreForSource = { actions += "restore:$it" },
        )

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun atomicIdentityGateSkipsRestoreWhenRotationWins() = runBlocking {
        val actions = mutableListOf<String>()
        val renewed = SourceSubscriptionResult(
            sourceId = 11L,
            profileBindings = mapOf(1L to "old-binding"),
            state = ZenPlaySubscriptionState.Unlimited,
        )

        enforceProtectedDownloadEntitlements(
            sources = listOf(renewed),
            nowMs = 10_000L,
            applyIfBindingCurrent = { _, _ -> false },
            revokeForSource = { actions += "revoke:$it" },
            restoreForSource = { actions += "restore:$it" },
        )

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun inactiveProfilesAreIncludedWhileSharedSourcesAreQueriedOnce() {
        val shared = SourceEntity(
            id = 11L,
            name = "Shared",
            type = SourceType.XTREAM,
            url = "https://panel.invalid",
            username = "client",
            password = "secret",
        )
        val inactiveOnly = shared.copy(id = 22L, name = "Inactive", username = "other")

        val snapshots = zenPlaySubscriptionSnapshots(
            mapOf(
                1L to listOf(shared),
                2L to listOf(shared, inactiveOnly),
            ),
        )

        assertEquals(listOf(11L, 22L), snapshots.map { it.source.id })
        assertEquals(setOf(1L, 2L), snapshots.first().profileBindings.keys)
    }

    @Test
    fun sharedSourceBindingsAlwaysMatchTheSingleQueriedCredentialSnapshot() {
        val accountA = SourceEntity(
            id = 11L,
            name = "Shared",
            type = SourceType.XTREAM,
            url = "https://panel.invalid",
            username = "client",
            password = "account-a",
        )
        // Separate profile flows can straddle a credential update. The monitor must never attach
        // B's binding to a request that actually sent A's credentials.
        val accountB = accountA.copy(password = "account-b")

        val snapshot = zenPlaySubscriptionSnapshots(
            mapOf(1L to listOf(accountA), 2L to listOf(accountB)),
        ).single()

        assertEquals(accountA, snapshot.source)
        assertEquals(
            zenPlaySubscriptionBinding(1L, accountA),
            snapshot.profileBindings.getValue(1L),
        )
        assertEquals(
            zenPlaySubscriptionBinding(2L, accountA),
            snapshot.profileBindings.getValue(2L),
        )
        assertNotEquals(
            zenPlaySubscriptionBinding(2L, accountB),
            snapshot.profileBindings.getValue(2L),
        )
    }

    @Test
    fun sensitiveCredentialChangeProducesDifferentMonitorBinding() {
        val source = SourceEntity(
            id = 11L,
            name = "Account",
            type = SourceType.XTREAM,
            url = "https://panel.invalid",
            username = "client",
            password = "old",
        )

        assertNotEquals(
            zenPlaySubscriptionBinding(1L, source),
            zenPlaySubscriptionBinding(1L, source.copy(password = "new")),
        )
        assertNotEquals(
            zenPlaySubscriptionBinding(1L, source),
            zenPlaySubscriptionBinding(1L, source.copy(userAgent = "new-agent")),
        )
        assertNotEquals(
            zenPlaySubscriptionBinding(1L, source),
            zenPlaySubscriptionBinding(1L, source.copy(httpReferer = "https://new.invalid")),
        )
    }
}
