package tv.own.owntv.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the boundaries that keep decrypted media inside the ZenPlay process. */
class ProtectedDownloadExposureContractTest {
    private fun source(relative: String): String = File("src/main/java/$relative").readText()

    @Test
    fun `decryption provider is private read only and cannot grant uri access`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = manifest
            .substringAfter("android:name=\"tv.own.owntv.core.download.secure.ProtectedDownloadProvider\"")
            .substringBefore("/>")

        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"false\""))

        val implementation = source("tv/own/owntv/core/download/secure/ProtectedDownloadProvider.kt")
        assertTrue(implementation.contains("if (mode != READ_MODE) throw FileNotFoundException()"))
        assertTrue(implementation.contains("override fun insert") && implementation.contains("UnsupportedOperationException"))
        assertTrue(implementation.contains("override fun update") && implementation.contains("UnsupportedOperationException"))
        assertTrue(implementation.contains("override fun delete") && implementation.contains("UnsupportedOperationException"))
        assertFalse(implementation.contains("grantUriPermission"))
    }

    @Test
    fun `encrypted containers live only below no backup private storage`() {
        val store = source("tv/own/owntv/core/download/secure/ProtectedDownloadStore.kt")

        assertTrue(store.contains("File(appContext.noBackupFilesDir, DIRECTORY)"))
        assertFalse(store.contains("getExternalFilesDir"))
        assertFalse(store.contains("Environment.getExternalStorage"))
        assertFalse(store.contains("MediaStore"))
    }
}
