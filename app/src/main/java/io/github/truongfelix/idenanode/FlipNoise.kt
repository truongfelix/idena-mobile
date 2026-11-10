package io.github.truongfelix.idenanode

import org.json.JSONObject
import kotlin.random.Random

/*
 * The anti-AI noise of a flip picture, effect by effect (B-215, as the desktop's B-213 noise.js): the protected
 * pictures each get a control for every effect, and Regenerate draws them at random. Free of Android.
 */

/**
 * The share of a picture's region border pixels recoloured from its palette, in %: at least 20, the noise of the
 * builds before (owner 2026-11-10); the automatic noise draws 20 to 30.
 */
const val MIN_NOISE_GRAIN = 20
const val MAX_NOISE_GRAIN = 40
const val MAX_AUTOMATIC_NOISE_GRAIN = 30

/** The colour shift, in degrees of hue: at least 20 (owner 2026-11-10); the automatic noise draws 20 to 60. */
const val MIN_NOISE_HUE = 20
const val MAX_NOISE_HUE = 60

/**
 * The effects of a picture's noise: [mirror] left to right, [grain] % of the region border pixels recoloured, the
 * hue turned by [hue] degrees: a grain and a colour shift at least as strong as the builds before always sent (owner
 * 2026-11-10). The brightness is the picture editor's ([FrameEdit]), not the noise's (owner 2026-11-10).
 */
data class NoiseEffects(
    val mirror: Boolean = false,
    val grain: Int = MIN_NOISE_GRAIN,
    val hue: Int = MIN_NOISE_HUE,
) {
    /** Every value in its range. */
    fun normalized(): NoiseEffects = NoiseEffects(
        mirror,
        grain.coerceIn(MIN_NOISE_GRAIN, MAX_NOISE_GRAIN),
        hue.coerceIn(MIN_NOISE_HUE, MAX_NOISE_HUE),
    )

    fun toJson(): JSONObject = JSONObject().put("mirror", mirror).put("grain", grain).put("hue", hue)

    companion object {
        fun fromJson(json: JSONObject): NoiseEffects = NoiseEffects(
            json.optBoolean("mirror", false),
            json.optInt("grain", MIN_NOISE_GRAIN),
            json.optInt("hue", MIN_NOISE_HUE),
        ).normalized()

        /**
         * The automatic noise's effects (owner 2026-11-10): grain 20 to 30 %, a colour shift of 20 to 60 degrees,
         * mirrored one time in two.
         */
        fun random(random: Random = Random.Default): NoiseEffects {
            val grain = random.nextInt(MIN_NOISE_GRAIN, MAX_AUTOMATIC_NOISE_GRAIN + 1)
            val hue = random.nextInt(MIN_NOISE_HUE, MAX_NOISE_HUE + 1)
            return NoiseEffects(mirror = random.nextBoolean(), grain = grain, hue = hue)
        }
    }
}
