package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.security.MessageDigest

/**
 * The robot avatars of Idena (robohash.idena.io, as the desktop app and idena.social show them), drawn on
 * the phone: RoboHash's set1 ported from e1ven/Robohash (robohash.py, MIT), its pictures in the app's
 * assets (`robohash/`, see CREDITS.txt there). Checked against robohash.idena.io: the same pictures.
 *
 * RoboHash hashes the text with SHA-512, cuts the 128 hex digits into 11 numbers of 11 digits, and takes
 * the color from the first and one picture of each part from the 5th to the 9th (the 2nd to the 4th pick
 * a set and a background, unused here).
 */
object Robohash {
    /** The colors of set1, in RoboHash's (natural) order. */
    val COLORS = listOf("blue", "brown", "green", "grey", "orange", "pink", "purple", "red", "white", "yellow")

    /** The parts in RoboHash's order of choice (its folders 000#Mouth … 004#02Face), each with [VARIANTS] pictures. */
    const val PARTS = 5
    const val VARIANTS = 10

    /** The order to draw the parts in: body, face, accessory, eyes, mouth (RoboHash sorts by the name after '#'). */
    val LAYERS = intArrayOf(3, 4, 2, 1, 0)

    /** The robot of [text]: the desktop app asks robohash.idena.io for the lowercase address. */
    fun robot(text: String): Robot {
        val hex = MessageDigest.getInstance("SHA-512").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val block = hex.length / 11
        val numbers = LongArray(11) { hex.substring(it * block, (it + 1) * block).toLong(16) }
        return Robot(COLORS[(numbers[0] % COLORS.size).toInt()], List(PARTS) { (numbers[4 + it] % VARIANTS).toInt() })
    }
}

/** A robot: its color and the picture of each part, in [Robohash]'s order of choice. */
data class Robot(val color: String, val parts: List<Int>) {
    /** The asset paths of its pictures, in the order to draw them. */
    fun layers(): List<String> = Robohash.LAYERS.map { "robohash/$color/$it/${parts[it]}.png" }
}

/** The robot avatars drawn so far, by address, kept while the app runs (about 12 MB at most). */
object RobotAvatars {
    /** The side of a drawn robot in pixels: enough for a 64 dp avatar on a phone. */
    private const val SIDE = 192

    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** The robot of [address] when already drawn, else null. */
    fun cached(address: String): ImageBitmap? = cache.get(address.lowercase())

    /** The robot of [address], drawn from the assets (off the main thread); null if a picture is missing. */
    fun draw(context: Context, address: String): ImageBitmap? {
        val key = address.lowercase()
        cache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val target = Rect(0, 0, SIDE, SIDE)
        for (path in Robohash.robot(key).layers()) {
            val part = runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
            canvas.drawBitmap(part, null, target, paint)
            part.recycle()
        }
        return out.asImageBitmap().also { cache.put(key, it) }
    }
}
