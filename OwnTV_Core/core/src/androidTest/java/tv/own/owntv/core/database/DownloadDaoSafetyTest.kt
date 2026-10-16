package tv.own.owntv.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType

@RunWith(AndroidJUnit4::class)
class DownloadDaoSafetyTest {
    private lateinit var db: OwnTVDatabase

    @Before
    fun open() {
        db = ownTVTestDatabase()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun duplicateStableIdentityKeepsOriginalRowAndProgress() = runBlocking {
        val profileId = db.profileDao().insert(profile("Primary"))
        val original = download(profileId, status = DownloadStatus.RUNNING, downloaded = 4_096)

        val firstId = db.downloadDao().insertIfAbsent(original)
        val duplicateId = db.downloadDao().insertIfAbsent(
            original.copy(status = DownloadStatus.QUEUED, downloadedBytes = 0),
        )

        assertTrue(firstId > 0)
        assertEquals(-1L, duplicateId)
        val stored = db.downloadDao().getByIdentity(profileId, original.sourceId, original.contentKey)!!
        assertEquals(firstId, stored.id)
        assertEquals(DownloadStatus.RUNNING, stored.status)
        assertEquals(4_096L, stored.downloadedBytes)
    }

    @Test
    fun sharedLegacyPathCountsEveryOwnerUntilItsRowIsRemoved() = runBlocking {
        val firstProfile = db.profileDao().insert(profile("First"))
        val secondProfile = db.profileDao().insert(profile("Second"))
        val path = "/legacy/Movies/Movie.mp4"
        val firstId = db.downloadDao().insertIfAbsent(
            download(firstProfile, key = "legacy:first", path = path),
        )
        val secondId = db.downloadDao().insertIfAbsent(
            download(secondProfile, key = "legacy:second", path = path),
        )

        assertEquals(2, db.downloadDao().countForFilePath(path))
        assertEquals(1, db.downloadDao().deleteByIdForProfile(firstId, firstProfile))
        assertEquals(1, db.downloadDao().countForFilePath(path))
        assertEquals(1, db.downloadDao().deleteByIdForProfile(secondId, secondProfile))
        assertEquals(0, db.downloadDao().countForFilePath(path))
    }

    private fun profile(name: String) = ProfileEntity(name = name, avatarColor = 0)

    private fun download(
        profileId: Long,
        key: String = "d1:10:MOVIE:r.bW92aWUtMQ",
        path: String = "/downloads/Movie.mp4",
        status: DownloadStatus = DownloadStatus.QUEUED,
        downloaded: Long = 0,
    ) = DownloadEntity(
        profileId = profileId,
        sourceId = 10,
        contentKey = key,
        mediaType = MediaType.MOVIE,
        itemId = 1,
        title = "Movie",
        streamUrl = "https://example.test/movie.mp4",
        filePath = path,
        status = status,
        downloadedBytes = downloaded,
    )
}
