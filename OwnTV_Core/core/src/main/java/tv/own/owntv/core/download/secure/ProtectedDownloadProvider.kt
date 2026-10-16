package tv.own.owntv.core.download.secure

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Private, read-only seekable bridge from encrypted chunks to Android's file-descriptor consumers.
 * No plaintext file is created: each read decrypts only the requested chunk(s) into memory.
 */
class ProtectedDownloadProvider : ContentProvider() {
    private lateinit var store: ProtectedDownloadStore

    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        store = ProtectedDownloadStore(app)
        return true
    }

    override fun getType(uri: Uri): String {
        fileId(uri)
        return "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val id = fileId(uri)
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(requested)
        val size = runCatching { store.open(id).use { it.plaintextLength } }
            .getOrElse { throw FileNotFoundException() }
        cursor.addRow(requested.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> "$id.zpd"
                OpenableColumns.SIZE -> size
                else -> null
            }
        })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != READ_MODE) throw FileNotFoundException()
        val id = fileId(uri)
        val reader = runCatching { store.open(id) }
            .getOrElse { throw FileNotFoundException() }
        val callback = ReaderCallback(reader) { store.exists(id) }
        return try {
            val manager = requireNotNull(context).getSystemService(StorageManager::class.java)
            manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, proxyHandler)
        } catch (failure: Throwable) {
            reader.close()
            throw FileNotFoundException().apply { initCause(failure) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun fileId(uri: Uri) = store.fileId(uri) ?: throw FileNotFoundException()

    private class ReaderCallback(
        private val reader: SecureDownloadReader,
        private val isReadable: () -> Boolean,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.plaintextLength

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            if (!isReadable()) throw SecurityException("Protected download access was revoked")
            reader.readAt(offset, data, 0, size).coerceAtLeast(0)
        } catch (failure: Throwable) {
            throw ErrnoException("protected-download-read", OsConstants.EIO).apply { initCause(failure) }
        }

        override fun onRelease() = reader.close()
    }

    private companion object {
        const val READ_MODE = "r"
        val proxyThread = HandlerThread("protected-download-reader").apply { start() }
        val proxyHandler = Handler(proxyThread.looper)
    }
}
