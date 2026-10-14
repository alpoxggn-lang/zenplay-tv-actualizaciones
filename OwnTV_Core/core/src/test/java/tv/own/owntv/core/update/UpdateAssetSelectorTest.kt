package tv.own.owntv.core.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetSelectorTest {

    @Test
    fun normalTrackDoesNotSelectLegacyEvenWhenLegacyIsFirst() {
        val selected = selectReleaseApk(releaseAssets(), "ZenPlay.apk", wantX86 = false)

        assertEquals("ZenPlay.apk", selected?.getString("name"))
    }

    @Test
    fun legacyTrackSelectsOnlyLegacy() {
        val selected = selectReleaseApk(releaseAssets(), "ZenPlayLegacy.apk", wantX86 = false)

        assertEquals("ZenPlayLegacy.apk", selected?.getString("name"))
    }

    @Test
    fun missingExactTrackFailsClosedInsteadOfFallingBack() {
        val selected = selectReleaseApk(releaseAssets(), "Missing.apk", wantX86 = false)

        assertNull(selected)
    }

    @Test
    fun legacyAbiSelectionRemainsAvailableForUnconfiguredHosts() {
        val selected = selectReleaseApk(releaseAssets(), "", wantX86 = true)

        assertEquals("ZenPlay-x86_64.apk", selected?.getString("name"))
    }

    private fun releaseAssets(): JSONArray = JSONArray()
        .put(asset("ZenPlayLegacy.apk"))
        .put(asset("ZenPlay.apk"))
        .put(asset("ZenPlay-x86_64.apk"))

    private fun asset(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://example.invalid/$name")
}
