package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The noise effect by effect (B-215): the effects make the old noise, the automatic one draws the new ranges, each effect at its bounds; the brightness is the picture editor's. */
class FlipNoiseTest {
    private fun color(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    // The noise before B-215, copied verbatim (FlipImage.kt at idena-mobile 9340f1c), and its constant: the
    // differential's reference.
    private val NOISE_SHARE = 0.2

    private fun oldProtectFrame(pixels: IntArray, width: Int, height: Int, random: Random = Random.Default): IntArray {
        val labels = watershed(blurLevels(grayLevels(pixels), width, height, 2), width, height)
        val colors = palette(pixels)
        val out = pixels.copyOf()
        for (p in out.indices) {
            if (colors.isEmpty() || random.nextDouble() >= NOISE_SHARE || !isBorder(labels, width, p)) continue
            out[p] = if (colors.size == 1) colors[0] else colors[1 + random.nextInt(colors.size - 1)]
        }
        val degrees = (20 + random.nextInt(30)).toFloat()
        for (p in out.indices) out[p] = turnHue(out[p], degrees)
        return if (random.nextBoolean()) mirrored(out, width) else out
    }

    /** Pictures of every kind the noise meets: random, gradients, stripes, blocks, two colors, one color, a photo-like one. */
    private fun corpus(): List<Triple<String, Int, IntArray>> {
        val out = mutableListOf<Triple<String, Int, IntArray>>()
        val random = Random(42)
        for ((w, h) in listOf(16 to 12, 48 to 36, 64 to 48, 33 to 17)) {
            val n = w * h
            out += Triple("random $w", w, IntArray(n) { color(random.nextInt(256), random.nextInt(256), random.nextInt(256)) })
            out += Triple("gradient $w", w, IntArray(n) { p -> color(255 * (p % w) / w, 255 * (p / w) / h, 128) })
            out += Triple("stripes $w", w, IntArray(n) { p -> if ((p % w) / 3 % 2 == 0) color(200, 30, 30) else color(20, 20, 220) })
            out += Triple("blocks $w", w, IntArray(n) { p -> color(((p % w) / 8) * 40 % 256, ((p / w) / 6) * 50 % 256, (p % 7) * 30) })
            out += Triple("one color $w", w, IntArray(n) { color(90, 160, 40) })
            out += Triple("grey $w", w, IntArray(n) { p -> val v = (p * 5) % 256; color(v, v, v) })
            out += Triple("dark $w", w, IntArray(n) { p -> color(p % 4, (p / w) % 3, 0) })
            out += Triple("bright $w", w, IntArray(n) { p -> color(250 + p % 6, 255, 240 + (p / w) % 16) })
        }
        val w = 120
        val h = 90
        out += Triple("photo-like", w, IntArray(w * h) { p ->
            val x = p % w
            val y = p / w
            val disc = (x - 60) * (x - 60) + (y - 40) * (y - 40) < 900
            if (disc) color(220 - y, 140 + (x % 9), 40) else color(40 + y, 90 + (x * y) % 40, 160 - x / 2)
        })
        return out
    }

    /**
     * The same draws as Random(seed) (XorWowRandom draws everything from nextBits and nextInt()), and what the old
     * noise drew for its colour shift (nextInt(30)) and its mirror (the last nextBoolean).
     */
    private class Recording(seed: Long) : Random() {
        private val r = Random(seed)
        var shift: Int? = null
        var mirror: Boolean? = null
        override fun nextBits(bitCount: Int): Int = r.nextBits(bitCount)
        override fun nextInt(): Int = r.nextInt()
        override fun nextInt(until: Int): Int = super.nextInt(until).also { if (until == 30) shift = it }
        override fun nextBoolean(): Boolean = super.nextBoolean().also { mirror = it }
    }

    @Test
    fun theEffectsMakeTheOldNoisePixelForPixel() {
        var cases = 0
        val seen = mutableSetOf<Pair<Int, Boolean>>()
        for ((name, w, pixels) in corpus()) {
            val h = pixels.size / w
            for (seed in 0L until 12L) {
                val old = oldProtectFrame(pixels, w, h, Random(seed))
                val recording = Recording(seed)
                assertArrayEquals("$name seed $seed: the recording draws as Random(seed)", old, oldProtectFrame(pixels, w, h, recording))
                val effects = NoiseEffects(mirror = recording.mirror!!, grain = 20, hue = 20 + recording.shift!!)
                seen += effects.hue to effects.mirror
                assertArrayEquals("$name seed $seed, $effects", old, protectFrame(pixels, w, h, effects, Random(seed)))
                cases++
            }
        }
        assertEquals("every picture and seed compared", corpus().size * 12, cases)
        assertTrue("both mirrors and many shifts met", seen.map { it.second }.toSet().size == 2 && seen.size > 20)
    }

    @Test
    fun theAutomaticNoiseDrawsItsEffectsThenMakesThem() {
        val pixels = IntArray(16 * 12) { p -> color(p % 256, 80, 200) }
        for (seed in 0L until 20L) {
            val made = protectFrame(pixels, 16, 12, Random(seed))
            val random = Random(seed)
            val effects = NoiseEffects.random(random)
            assertEquals(effects, made.effects)
            assertArrayEquals(protectFrame(pixels, 16, 12, effects, random), made.pixels)
        }
    }

    @Test
    fun theAutomaticEffectsCoverTheirRangesAndNoMore() {
        val drawn = (0L until 3000L).map { NoiseEffects.random(Random(it)) }
        assertEquals("grain 20 to 30 %", (20..30).toSet(), drawn.map { it.grain }.toSet())
        assertEquals("colour shift 20 to 60 degrees", (20..60).toSet(), drawn.map { it.hue }.toSet())
        assertEquals(setOf(true, false), drawn.map { it.mirror }.toSet())
    }

    private val photo = corpus().last()
    private val pw = photo.second
    private val ph = photo.third.size / photo.second
    private val pixels = photo.third
    private val least = NoiseEffects(mirror = false, grain = MIN_NOISE_GRAIN, hue = MIN_NOISE_HUE)

    private fun plain(e: NoiseEffects, seed: Long = 7) = protectFrame(pixels, pw, ph, e, Random(seed))

    @Test
    fun grainRecolorsOnlyBorderPixelsUpToItsShare() {
        val labels = watershed(blurLevels(grayLevels(pixels), pw, ph, 2), pw, ph)
        val borders = pixels.indices.count { isBorder(labels, pw, it) }
        assertTrue("the picture has borders", borders > 100)
        // The grain alone: the colour shift taken back out is not exact, so compare against the shift of the original.
        val shiftedOnly = pixels.map { turnHue(it, MIN_NOISE_HUE.toFloat()) }.toIntArray()
        fun grained(grain: Int) = plain(least.copy(grain = grain)).let { out -> pixels.indices.filter { out[it] != shiftedOnly[it] } }
        val most = grained(MAX_NOISE_GRAIN)
        val fewest = grained(MIN_NOISE_GRAIN)
        assertTrue("only borders change", most.all { isBorder(labels, pw, it) } && fewest.all { isBorder(labels, pw, it) })
        assertTrue("about 40 % of the borders at the maximum, ${most.size} of $borders", most.size.toDouble() / borders in 0.25..0.45)
        assertTrue("about 20 % at the minimum, ${fewest.size} of $borders", fewest.size.toDouble() / borders in 0.1..0.25)
        assertArrayEquals("below the minimum: the minimum", plain(least), plain(least.copy(grain = 0)))
        assertArrayEquals("above the maximum: the maximum", plain(least.copy(grain = MAX_NOISE_GRAIN)), plain(least.copy(grain = 99)))
    }

    @Test
    fun colorShiftTurnsTheHueByItsDegrees() {
        // With the same draws the grain falls on the same pixels: the outputs differ by the turn alone.
        val grainOnly = plain(least.copy(hue = MIN_NOISE_HUE))
        val most = plain(least.copy(hue = MAX_NOISE_HUE))
        assertTrue("more shift, other colours", grainOnly.indices.count { grainOnly[it] != most[it] } > pixels.size / 2)
        assertEquals(color(255, 255, 0), turnHue(color(255, 0, 0), 60f))
        assertArrayEquals("below the minimum: the minimum", grainOnly, plain(least.copy(hue = 0)))
        assertArrayEquals("above the maximum: the maximum", most, plain(least.copy(hue = 200)))
    }

    @Test
    fun mirrorTurnsThePictureLeftToRight() {
        assertArrayEquals(mirrored(plain(least), pw), plain(least.copy(mirror = true)))
    }

    @Test
    fun effectsKeepTheirValuesThroughJsonAndStayInRange() {
        val e = NoiseEffects(mirror = true, grain = 33, hue = 27)
        assertEquals(e, NoiseEffects.fromJson(JSONObject(e.toJson().toString())))
        assertEquals(NoiseEffects(false, 40, 60), NoiseEffects.fromJson(JSONObject("""{"grain":90,"hue":61}""")))
        assertEquals(NoiseEffects(false, 20, 20), NoiseEffects.fromJson(JSONObject("""{"grain":0,"hue":-4}""")))
        assertEquals("nothing saved: the least noise", least, NoiseEffects.fromJson(JSONObject("{}")))
    }
}
