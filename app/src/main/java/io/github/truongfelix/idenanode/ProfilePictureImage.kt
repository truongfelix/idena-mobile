package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The WebP qualities tried for a profile picture, the best first, as the desktop. */
private val PICTURE_QUALITIES = listOf(80, 70, 60, 50, 40)

/**
 * The profile picture of the image at [uri]: its centered square, upright, at most [PICTURE_SIDE] pixels a side, as a
 * WebP at the best quality that fits in [MAX_PICTURE_BYTES] (a GIF keeps its first frame). Encoding again also leaves
 * out the photo's metadata. Throws with a reason the editor shows.
 */
fun encodePicture(context: Context, uri: Uri): ByteArray {
    val source = decodeUpright(readImage(context, uri), PICTURE_SIDE) ?: throw IOException(t(R.string.profile_picture_unreadable))
    val (x, y, side) = squareCrop(source.width, source.height)
    val out = minOf(PICTURE_SIDE, side)
    val square = Bitmap.createScaledBitmap(Bitmap.createBitmap(source, x, y, side, side), out, out, true)
    for (quality in PICTURE_QUALITIES) {
        val bytes = webp(square, quality)
        if (bytes.size <= MAX_PICTURE_BYTES) return bytes
    }
    throw IOException(t(R.string.profile_picture_too_large, MAX_PICTURE_BYTES / 1000))
}

@Suppress("DEPRECATION")
private fun webp(bitmap: Bitmap, quality: Int): ByteArray {
    // Before Android 11, WEBP below quality 100 is the lossy encoder.
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    val out = ByteArrayOutputStream()
    bitmap.compress(format, quality, out)
    return out.toByteArray()
}
