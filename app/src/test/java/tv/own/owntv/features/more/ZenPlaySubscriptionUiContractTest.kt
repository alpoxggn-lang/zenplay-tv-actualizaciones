package tv.own.owntv.features.more

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenPlaySubscriptionUiContractTest {
    @Test
    fun `tv more screen labels a dated subscription in the past as expired`() {
        val source = File("src/main/java/tv/own/owntv/features/more/MoreScreen.kt").readText()
        val datedBranch = source
            .substringAfter("is ZenPlaySubscriptionState.Dated ->")
            .substringBefore("ZenPlaySubscriptionState.Unlimited")

        assertTrue(datedBranch.contains("expiry.expiryMs <= System.currentTimeMillis()"))
        assertTrue(datedBranch.contains("R.string.zenplay_expiry_expired"))
        assertTrue(datedBranch.contains("R.string.zenplay_expiry_until"))
    }

    @Test
    fun `tv more screen reports revoked subscriptions explicitly`() {
        val source = File("src/main/java/tv/own/owntv/features/more/MoreScreen.kt").readText()

        assertTrue(
            source.contains(
                "ZenPlaySubscriptionState.Revoked -> " +
                    "stringResource(R.string.zenplay_expiry_revoked)",
            ),
        )
    }
}
