package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlipImageTest {
    private fun color(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun twoValleysMakeTwoRegionsAndALine() {
        // A dark left half and a dark right half with a bright ridge in the middle column.
        val width = 9
        val height = 5
        val levels = IntArray(width * height) { p -> if (p % width == 4) 200 else 10 + kotlin.math.abs(p % width - 4) }
        val labels = watershed(levels, width, height)
        val left = (0 until height).map { labels[it * width + 1] }.toSet()
        val right = (0 until height).map { labels[it * width + 7] }.toSet()
        assertEquals(1, left.size)
        assertEquals(1, right.size)
        assertNotEquals(left, right)
        assertTrue("regions are numbered from 1", left.single() > 0 && right.single() > 0)
        assertTrue("the ridge is a border", (0 until height).all { isBorder(labels, width, it * width + 3) || isBorder(labels, width, it * width + 4) })
    }

    @Test
    fun aFlatImageIsOneRegion() {
        val labels = watershed(IntArray(30) { 50 }, 6, 5)
        assertEquals(setOf(1), labels.toSet())
    }

    @Test
    fun blurKeepsAFlatImageAndSoftensAnEdge() {
        val width = 10
        val flat = IntArray(50) { 80 }
        assertEquals(flat.toList(), blurLevels(flat, width, 5, 2).toList())
        val edge = IntArray(50) { if (it % width < 5) 0 else 255 }
        val blurred = blurLevels(edge, width, 5, 2)
        assertTrue(blurred[4] in 1..254 && blurred[5] in 1..254)
    }

    @Test
    fun paletteHasTheMainColorsFirst() {
        val pixels = IntArray(100) { if (it < 70) color(250, 10, 10) else color(10, 10, 250) }
        val colors = palette(pixels)
        assertEquals(2, colors.size)
        assertEquals(color(250, 10, 10), colors[0])
    }

    @Test
    fun hueTurnsAndGreyStays() {
        assertEquals(color(0, 255, 0), turnHue(color(255, 0, 0), 120f))
        assertEquals(color(128, 128, 128), turnHue(color(128, 128, 128), 40f))
    }

    @Test
    fun protectionChangesTheFrameButKeepsItsSize() {
        val width = 24
        val height = 18
        val pixels = IntArray(width * height) { p -> color((p * 13) % 256, (p * 7) % 256, (p * 3) % 256) }
        val out = protectFrame(pixels, width, height, Random(1))
        assertEquals(pixels.size, out.size)
        val changed = pixels.indices.count { pixels[it] != out[it] }
        assertTrue("most pixels change (hue)", changed > pixels.size / 2)
        assertTrue("all opaque", out.all { (it ushr 24) == 0xff })
    }

    @Test
    fun theNonsenseFrameMixesTheFourPictures() {
        val width = 160
        val height = 120
        // A picture with many regions (waves wider than the blur), and three plain colors.
        val base = IntArray(width * height) { p -> val v = ((kotlin.math.sin(p % width / 8.0) + kotlin.math.cos(p / width / 8.0)) * 60 + 128).toInt(); color(v, v, v) }
        val plains = listOf(color(255, 0, 0), color(0, 255, 0), color(0, 0, 255)).map { c -> IntArray(width * height) { c } }
        val out = adversarialFrame(listOf(base) + plains, width, height, Random(3))
        assertEquals(width * height, out.size)
        val fromPlains = plains.count { plain -> out.indices.any { out[it] == plain[it] } }
        assertTrue("at least two of the plain pictures show", fromPlains >= 2)
    }
}
