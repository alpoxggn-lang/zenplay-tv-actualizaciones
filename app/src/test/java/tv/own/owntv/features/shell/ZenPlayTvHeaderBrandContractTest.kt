package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the TV/Fire header branding without changing its D-pad navigation contract. */
class ZenPlayTvHeaderBrandContractTest {
    @Test
    fun `tv header displays the horizontal ZenPlay wordmark as a bounded decorative image`() {
        val source = File(
            "src/main/java/tv/own/owntv/features/shell/components/TopBar.kt",
        ).readText()
        val brand = source
            .substringAfter("// The internal header uses the wide ZenPlay signature")
            .substringBefore("        }")

        assertTrue(source.contains(".data(R.drawable.zenplay_wordmark_horizontal)"))
        assertTrue(brand.contains("AsyncImage("))
        assertTrue(source.contains("ImageRequest.Builder(context)"))
        assertTrue(source.contains(".crossfade(false)"))
        assertTrue(brand.contains("contentScale = ContentScale.Fit"))
        assertTrue(brand.contains(".widthIn("))
        assertTrue(brand.contains(".height("))
        assertTrue(brand.contains(".focusProperties { canFocus = false }"))
        assertTrue(brand.contains(".testTag(\"zenplay_header_wordmark\")"))
        assertFalse(brand.contains("BrandMark("))
        assertFalse(brand.contains("painterResource("))
    }

    @Test
    fun `wordmark stays after the flexible spacer so navigation keeps its position`() {
        val source = File(
            "src/main/java/tv/own/owntv/features/shell/components/TopBar.kt",
        ).readText()
        val rowTail = source
            .substringAfter("Spacer(Modifier.weight(1f))")
            .substringBefore("private fun NowPlayingNavButton")

        assertTrue(rowTail.indexOf("audioBar?.invoke()") < rowTail.indexOf("AsyncImage("))
        assertFalse(rowTail.contains("FocusableSurface("))
    }
}
