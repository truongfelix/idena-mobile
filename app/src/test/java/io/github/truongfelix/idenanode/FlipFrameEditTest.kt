package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlipFrameEditTest {
    private val w = 480f
    private val h = 360f

    private fun assertNear(message: String, expected: Float, actual: Float) = assertEquals(message, expected, actual, 0.01f)

    /** The frame's box covered by the mapped picture: its four corners' bounds hold the frame. */
    private fun covers(srcW: Int, srcH: Int, edit: FrameEdit): Boolean {
        val m = frameTransform(srcW, srcH, w, h, edit)
        val corners = listOf(0f to 0f, srcW.toFloat() to 0f, 0f to srcH.toFloat(), srcW.toFloat() to srcH.toFloat())
        val xs = corners.map { (x, y) -> m.mapX(x, y) }
        val ys = corners.map { (x, y) -> m.mapY(x, y) }
        val e = 0.01f
        return xs.min() <= e && xs.max() >= w - e && ys.min() <= e && ys.max() >= h - e
    }

    @Test
    fun aPlainEditCutsTheMiddleAsBefore() {
        // 0.3.0 cut a 1600 x 900 picture to 1200 x 900 around its middle (x 200 to 1400), scaled to the frame.
        val m = frameTransform(1600, 900, w, h, FrameEdit())
        assertNear("left edge of the cut", 0f, m.mapX(200f, 0f))
        assertNear("right edge of the cut", w, m.mapX(1400f, 900f))
        assertNear("top", 0f, m.mapY(200f, 0f))
        assertNear("bottom", h, m.mapY(200f, 900f))
    }

    @Test
    fun withoutProportionsTheWholePictureFillsTheFrame() {
        val m = frameTransform(1600, 900, w, h, FrameEdit(keepProportions = false))
        assertNear("top left x", 0f, m.mapX(0f, 0f))
        assertNear("top left y", 0f, m.mapY(0f, 0f))
        assertNear("bottom right x", w, m.mapX(1600f, 900f))
        assertNear("bottom right y", h, m.mapY(1600f, 900f))
        val (roomX, roomY) = frameRoom(1600, 900, w, h, FrameEdit(keepProportions = false))
        assertNear("no room to move across", 0f, roomX)
        assertNear("no room to move down", 0f, roomY)
    }

    @Test
    fun aPanOfOnePutsThePictureEdgeOnTheFrameEdge() {
        val m = frameTransform(1600, 900, w, h, FrameEdit(panX = 1f))
        assertNear("left edge of the picture on the frame's", 0f, m.mapX(0f, 0f))
        val n = frameTransform(1600, 900, w, h, FrameEdit(panX = -1f))
        assertNear("right edge of the picture on the frame's", w, n.mapX(1600f, 0f))
    }

    @Test
    fun aQuarterTurnTurnsClockwise() {
        // A portrait picture turned a quarter: its top left corner goes to the top right.
        val m = frameTransform(900, 1200, w, h, FrameEdit(turns = 1, keepProportions = false))
        assertNear("x", w, m.mapX(0f, 0f))
        assertNear("y", 0f, m.mapY(0f, 0f))
        assertNear("bottom right goes to the bottom left x", 0f, m.mapX(900f, 1200f))
        assertNear("bottom right goes to the bottom left y", h, m.mapY(900f, 1200f))
        // Upside down: the top left corner at the bottom right.
        val upside = frameTransform(480, 360, w, h, FrameEdit(turns = 2))
        assertNear("x", w, upside.mapX(0f, 0f))
        assertNear("y", h, upside.mapY(0f, 0f))
    }

    @Test
    fun theFrameIsAlwaysCovered() {
        val random = Random(7)
        repeat(2000) {
            val srcW = 50 + random.nextInt(3000)
            val srcH = 50 + random.nextInt(3000)
            val edit = FrameEdit(
                zoom = random.nextFloat() * 6f - 1f,
                panX = random.nextFloat() * 4f - 2f,
                panY = random.nextFloat() * 4f - 2f,
                turns = random.nextInt(-5, 9),
                keepProportions = random.nextBoolean(),
            )
            assertTrue("$srcW x $srcH $edit", covers(srcW, srcH, edit))
        }
    }

    @Test
    fun valuesOutOfRangeAreBroughtBack() {
        val e = FrameEdit(zoom = 9f, panX = -3f, panY = Float.NaN, turns = -1, brightness = 5f).normalized()
        assertEquals(FrameEdit(zoom = MAX_FRAME_ZOOM, panX = -1f, panY = 0f, turns = 3, brightness = MAX_FRAME_BRIGHTNESS), e)
        assertEquals(MIN_FRAME_BRIGHTNESS, FrameEdit(brightness = 0f).normalized().brightness)
        assertTrue(FrameEdit(turns = 4).isPlain)
        assertFalse(FrameEdit(brightness = 1.2f).isPlain)
    }

    @Test
    fun anEditSurvivesItsJson() {
        val e = FrameEdit(zoom = 2.5f, panX = 0.25f, panY = -0.5f, turns = 3, keepProportions = false, brightness = 0.6f)
        assertEquals(e, FrameEdit.fromJson(org.json.JSONObject(e.toJson().toString())))
        assertEquals("no edit saved", FrameEdit(), FrameEdit.fromJson(null))
    }

    @Test
    fun theMatrixValuesAreInAndroidOrder() {
        val m = Affine(a = 1f, b = 2f, c = 3f, d = 4f, e = 5f, f = 6f)
        // Android: MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, then the perspective row.
        assertEquals(listOf(1f, 3f, 5f, 2f, 4f, 6f, 0f, 0f, 1f), m.matrixValues().toList())
    }
}
