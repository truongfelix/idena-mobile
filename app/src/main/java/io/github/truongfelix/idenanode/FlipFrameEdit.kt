package io.github.truongfelix.idenanode

import org.json.JSONObject
import kotlin.random.Random

/*
 * How a picture fills its flip frame (B-204, as the desktop's image editor; brightness B-200 as the desktop's
 * B-198), free of Android so that it is tested on its own: the frame is always covered, the picture moved and
 * zoomed inside it, its proportions kept (cut to the frame) or not (squeezed or stretched to it), turned a
 * quarter at a time, and made darker or brighter.
 */

/** The brightness range of a picture (desktop: 20 % to 180 %). */
const val MIN_FRAME_BRIGHTNESS = 0.2f
const val MAX_FRAME_BRIGHTNESS = 1.8f

/** How far a picture can be zoomed into its frame. */
const val MAX_FRAME_ZOOM = 4f

/**
 * A picture's place in its frame: [zoom] from 1 (the picture just covers the frame) to [MAX_FRAME_ZOOM]; [panX] and
 * [panY] from -1 to 1, the share of the room left to move it (0: centred; 1: its left or top edge on the frame's);
 * [turns] quarter turns clockwise; [keepProportions] false squeezes or stretches it to the frame; [brightness] a
 * factor on its colours.
 */
data class FrameEdit(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val turns: Int = 0,
    val keepProportions: Boolean = true,
    val brightness: Float = 1f,
) {
    /** Every value in its range. */
    fun normalized(): FrameEdit = FrameEdit(
        zoom.finiteOr(1f).coerceIn(1f, MAX_FRAME_ZOOM),
        panX.finiteOr(0f).coerceIn(-1f, 1f),
        panY.finiteOr(0f).coerceIn(-1f, 1f),
        ((turns % 4) + 4) % 4,
        keepProportions,
        brightness.finiteOr(1f).coerceIn(MIN_FRAME_BRIGHTNESS, MAX_FRAME_BRIGHTNESS),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("zoom", zoom.toDouble()).put("panX", panX.toDouble()).put("panY", panY.toDouble())
        .put("turns", turns).put("keepProportions", keepProportions).put("brightness", brightness.toDouble())

    companion object {
        fun fromJson(json: JSONObject?): FrameEdit = if (json == null) FrameEdit() else FrameEdit(
            json.optDouble("zoom", 1.0).toFloat(),
            json.optDouble("panX", 0.0).toFloat(),
            json.optDouble("panY", 0.0).toFloat(),
            json.optInt("turns", 0),
            json.optBoolean("keepProportions", true),
            json.optDouble("brightness", 1.0).toFloat(),
        ).normalized()
    }
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback

/**
 * A 2D affine map, as Android's Matrix holds it: x' = a x + c y + e, y' = b x + d y + f
 * (Matrix.setValues(floatArrayOf(a, c, e, b, d, f, 0, 0, 1))).
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(x: Float, y: Float) = a * x + c * y + e
    fun mapY(x: Float, y: Float) = b * x + d * y + f

    /** The values in Android Matrix order. */
    fun matrixValues(): FloatArray = floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)
}

/** The picture's size once turned by [turns] quarter turns: width and height swapped on an odd number. */
fun turnedSize(width: Int, height: Int, turns: Int): Pair<Float, Float> =
    if (((turns % 4) + 4) % 2 == 1) height.toFloat() to width.toFloat() else width.toFloat() to height.toFloat()

/** The scale of a picture of [width] x [height] (turned) in a frame of [frameWidth] x [frameHeight] at zoom 1, each way. */
private fun baseScale(width: Float, height: Float, frameWidth: Float, frameHeight: Float, keepProportions: Boolean): Pair<Float, Float> {
    if (!keepProportions) return frameWidth / width to frameHeight / height
    val cover = maxOf(frameWidth / width, frameHeight / height)
    return cover to cover
}

/**
 * The room the picture has to move each way, in frame units: half of how much its scaled size passes the frame.
 * A pan of 1 moves it by that much.
 */
fun frameRoom(sourceWidth: Int, sourceHeight: Int, frameWidth: Float, frameHeight: Float, edit: FrameEdit): Pair<Float, Float> {
    val e = edit.normalized()
    val (w, h) = turnedSize(sourceWidth, sourceHeight, e.turns)
    val (sx, sy) = baseScale(w, h, frameWidth, frameHeight, e.keepProportions)
    return (w * sx * e.zoom - frameWidth).coerceAtLeast(0f) / 2 to (h * sy * e.zoom - frameHeight).coerceAtLeast(0f) / 2
}

/**
 * Where each pixel of a picture of [sourceWidth] x [sourceHeight] goes in a frame of [frameWidth] x [frameHeight]
 * with [edit]: turned around its centre, scaled to cover the frame (then zoomed), centred, then moved by the pan.
 */
fun frameTransform(sourceWidth: Int, sourceHeight: Int, frameWidth: Float, frameHeight: Float, edit: FrameEdit): Affine {
    val e = edit.normalized()
    val (w, h) = turnedSize(sourceWidth, sourceHeight, e.turns)
    val (bx, by) = baseScale(w, h, frameWidth, frameHeight, e.keepProportions)
    val sx = bx * e.zoom
    val sy = by * e.zoom
    // The quarter turn, clockwise on the screen (y down): (x, y) -> (-y, x).
    val (r11, r12, r21, r22) = when (e.turns) {
        1 -> listOf(0f, -1f, 1f, 0f)
        2 -> listOf(-1f, 0f, 0f, -1f)
        3 -> listOf(0f, 1f, -1f, 0f)
        else -> listOf(1f, 0f, 0f, 1f)
    }
    val a = sx * r11
    val c = sx * r12
    val b = sy * r21
    val d = sy * r22
    val (roomX, roomY) = frameRoom(sourceWidth, sourceHeight, frameWidth, frameHeight, e)
    val cx = frameWidth / 2 + e.panX * roomX
    val cy = frameHeight / 2 + e.panY * roomY
    val hx = sourceWidth / 2f
    val hy = sourceHeight / 2f
    return Affine(a, b, c, d, cx - (a * hx + c * hy), cy - (b * hx + d * hy))
}

/** Where the anti-AI picture is made from (B-200, as the desktop's B-199). */
enum class AdversarialSource(val key: String) {
    /** Two search pictures and two story pictures, at random; what one side lacks comes from the other. */
    Both("both"),

    /** The search pictures; the story pictures fill in when there are fewer than four. */
    Search("search"),

    /** The story pictures; the search pictures only when there is no story picture. */
    Story("story");

    companion object {
        /** The saved choice; [Both] when there is none, or one this build does not know. */
        fun of(key: String?): AdversarialSource = entries.firstOrNull { it.key == key } ?: Both
    }
}

/**
 * The pictures the anti-AI frame is made from, by [source] (desktop adversarialSources): [found] the search's, [story]
 * the story pictures. At most four; fewer when there are not enough (the caller mirrors copies to make four).
 */
fun <T> adversarialSources(found: List<T>, story: List<T>, source: AdversarialSource, random: Random = Random.Default): List<T> = when (source) {
    AdversarialSource.Search -> (if (found.size >= FLIP_FRAMES) found else found + story).shuffled(random).take(FLIP_FRAMES)
    AdversarialSource.Story -> (story.ifEmpty { found }).shuffled(random).take(FLIP_FRAMES)
    AdversarialSource.Both -> {
        val search = found.shuffled(random)
        val picked = search.take(2).toMutableList()
        picked += story.shuffled(random).take(FLIP_FRAMES - picked.size)
        picked += search.drop(2).take(FLIP_FRAMES - picked.size)
        picked
    }
}
