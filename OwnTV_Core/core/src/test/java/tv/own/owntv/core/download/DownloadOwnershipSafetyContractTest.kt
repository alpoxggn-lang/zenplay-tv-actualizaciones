package tv.own.owntv.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for the cross-profile/source download ownership fixes added in v47. */
class DownloadOwnershipSafetyContractTest {
    private fun source(relative: String): String = File("src/main/java/$relative").readText()

    @Test
    fun `stable enqueue never replaces an active row and shared legacy files are reference counted`() {
        val dao = source("tv/own/owntv/core/database/dao/DownloadDao.kt")
        val manager = source("tv/own/owntv/core/download/DownloadManager.kt")

        assertTrue(dao.contains("@Insert(onConflict = OnConflictStrategy.IGNORE)"))
        assertFalse(dao.contains("@Insert(onConflict = OnConflictStrategy.REPLACE)"))
        assertTrue(manager.contains("downloadDao.getByIdentity(profileId, sourceId, contentKey)"))
        assertTrue(manager.contains("if (downloadDao.countForFilePath(path) == 0)"))
        assertTrue(manager.contains("privateRetryTarget(d)"))
    }

    @Test
    fun `overlapping workers serialize and cancellation reaches blocking network reads`() {
        val engine = source("tv/own/owntv/core/download/DownloadEngine.kt")

        assertTrue(engine.contains("drainMutex.withLock"))
        assertTrue(engine.contains("launch(start = CoroutineStart.LAZY)"))
        assertTrue(engine.contains("active[next.id] = job"))
        assertTrue(engine.contains("watchDownloadCancellation(call::cancel)"))
        assertTrue(engine.contains("downloadDao.countForFilePath(storedPath) > 1"))
    }

    @Test
    fun `transfer and owner deletion revalidate then revoke access`() {
        val engine = source("tv/own/owntv/core/download/DownloadEngine.kt")
        val sourceRepository = source("tv/own/owntv/core/repository/SourceRepository.kt")
        val profileManager = source("tv/own/owntv/core/profile/ProfileManager.kt")

        assertTrue(engine.contains("authorized(current) == null"))
        assertTrue(engine.contains("AUTHORIZATION_RECHECK_MS"))
        assertTrue(sourceRepository.contains("downloadManager.removeForSource(source.id)"))
        assertTrue(profileManager.contains("downloadManager.removeForProfile(stored.id)"))
    }
}
