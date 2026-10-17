package tv.own.owntv.mobile.ui.screens.multiview

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileMultiviewLayoutTest {
    @Test
    fun `two streams stack in portrait and sit side by side in landscape`() {
        assertEquals(
            MobileMultiviewGridSpec(columns = 1, rows = 2),
            mobileMultiviewGridSpec(tileCount = 2, landscape = false),
        )
        assertEquals(
            MobileMultiviewGridSpec(columns = 2, rows = 1),
            mobileMultiviewGridSpec(tileCount = 2, landscape = true),
        )
    }

    @Test
    fun `three and four streams keep a balanced two by two grid`() {
        listOf(false, true).forEach { landscape ->
            assertEquals(
                MobileMultiviewGridSpec(columns = 2, rows = 2),
                mobileMultiviewGridSpec(tileCount = 3, landscape = landscape),
            )
            assertEquals(
                MobileMultiviewGridSpec(columns = 2, rows = 2),
                mobileMultiviewGridSpec(tileCount = 4, landscape = landscape),
            )
        }
    }

    @Test
    fun `portrait cells fit the screen and remain sixteen by nine`() {
        val spec = mobileMultiviewGridSpec(tileCount = 2, landscape = false)
        val cell = mobileMultiviewCellSize(
            availableWidth = 720f,
            availableHeight = 1_600f,
            spec = spec,
            gap = 6f,
        )

        assertEquals(720f, cell.width, 0.01f)
        assertEquals(405f, cell.height, 0.01f)
        assertTrue(cell.height * spec.rows + 6f <= 1_600f)
        assertTrue(abs(cell.width / cell.height - MOBILE_MULTIVIEW_FRAME_ASPECT) < 0.001f)
    }

    @Test
    fun `landscape cells shrink by height without overflowing or stretching`() {
        val spec = mobileMultiviewGridSpec(tileCount = 2, landscape = true)
        val cell = mobileMultiviewCellSize(
            availableWidth = 1_600f,
            availableHeight = 720f,
            spec = spec,
            gap = 6f,
        )

        assertTrue(cell.width * spec.columns + 6f <= 1_600f)
        assertTrue(cell.height <= 720f)
        assertTrue(abs(cell.width / cell.height - MOBILE_MULTIVIEW_FRAME_ASPECT) < 0.001f)
    }

    @Test
    fun `four stream grid respects both dimensions on a portrait tablet`() {
        val spec = mobileMultiviewGridSpec(tileCount = 4, landscape = false)
        val cell = mobileMultiviewCellSize(
            availableWidth = 1_200f,
            availableHeight = 1_920f,
            spec = spec,
            gap = 6f,
        )

        assertTrue(cell.width * spec.columns + 6f <= 1_200f)
        assertTrue(cell.height * spec.rows + 6f <= 1_920f)
        assertTrue(abs(cell.width / cell.height - MOBILE_MULTIVIEW_FRAME_ASPECT) < 0.001f)
    }

    @Test
    fun `unknown stream aspect falls back to the full sixteen by nine frame`() {
        val picture = mobileMultiviewVideoSize(
            containerWidth = 1_280f,
            containerHeight = 720f,
            videoAspect = null,
        )

        assertEquals(1_280f, picture.width, 0.01f)
        assertEquals(720f, picture.height, 0.01f)
    }

    @Test
    fun `four by three stream is pillarboxed instead of stretched or cropped`() {
        val picture = mobileMultiviewVideoSize(
            containerWidth = 1_280f,
            containerHeight = 720f,
            videoAspect = 4f / 3f,
        )

        assertEquals(960f, picture.width, 0.01f)
        assertEquals(720f, picture.height, 0.01f)
        assertEquals(4f / 3f, picture.width / picture.height, 0.001f)
    }

    @Test
    fun `par corrected wide stream is letterboxed using the reported display aspect`() {
        val displayAspect = 64f / 27f
        val picture = mobileMultiviewVideoSize(
            containerWidth = 1_280f,
            containerHeight = 720f,
            videoAspect = displayAspect,
        )

        assertEquals(1_280f, picture.width, 0.01f)
        assertTrue(picture.height < 720f)
        assertEquals(displayAspect, picture.width / picture.height, 0.001f)
    }
}
