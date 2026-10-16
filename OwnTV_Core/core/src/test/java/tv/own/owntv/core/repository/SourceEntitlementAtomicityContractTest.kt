package tv.own.owntv.core.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the common identity gate that prevents an old provider response mutating a new account. */
class SourceEntitlementAtomicityContractTest {
    private val source = File(
        "src/main/java/tv/own/owntv/core/repository/SourceRepository.kt",
    ).readText()

    @Test
    fun `credential rotation and conditional entitlement action share one mutex`() {
        val update = source
            .substringAfter("suspend fun updateSource(")
            .substringBefore("suspend fun withStableSourceIdentity(")
        val conditionalAction = source
            .substringAfter("suspend fun withStableSourceIdentity(")
            .substringBefore("suspend fun sync(")

        assertTrue(update.contains("sourceUpdateMutex.withLock"))
        assertTrue(conditionalAction.contains("sourceUpdateMutex.withLock"))
        assertTrue(conditionalAction.indexOf("validate(current)") < conditionalAction.indexOf("action()"))
    }

    @Test
    fun `replacement xtream identity begins revoked before credentials are committed`() {
        val update = source
            .substringAfter("suspend fun updateSource(")
            .substringBefore("suspend fun withStableSourceIdentity(")

        assertTrue(update.contains("if (update.type == SourceType.XTREAM)"))
        assertTrue(
            update.indexOf("downloadManager.revokeEntitlementForSource(source.id)") <
                update.indexOf("downloadManager.rotateSourceIdentity("),
        )
    }
}
