package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * Images posted from the phone: the photo is decoded, turned upright and encoded again as WebP at the
 * size chosen. Storing on IPFS costs about 1 iDNA per 5 KB, so the sizes are small; encoding again also
 * leaves out the photo's metadata (place, camera), which would otherwise be public for good.
 */

/** The sizes offered for an image, as its longer side in pixels, and the one chosen at first. */
val IMAGE_SIZES = listOf(320, 480, 720, 1080)
const val DEFAULT_IMAGE_SIZE = 480

/** The largest photo read (bytes): larger files are refused before decoding. */
private const val MAX_SOURCE_BYTES = 40_000_000

private const val WEBP_QUALITY = 75

/**
 * The image at [uri] (from the photo picker) encoded at each size of [IMAGE_SIZES] no larger than the
 * image itself (at least one). Throws when it cannot be read.
 */
fun encodeImageSizes(context: Context, uri: Uri): Map<Int, ByteArray> {
    val bytes = readImage(context, uri)
    val source = decodeUpright(bytes, IMAGE_SIZES.last()) ?: throw IOException("Android cannot read this image")
    val longer = maxOf(source.width, source.height)
    val sizes = IMAGE_SIZES.filter { it <= longer }.ifEmpty { listOf(longer) }
    return sizes.associateWith { encodeWebp(scaled(source, it)) }
}

/** The file at [uri] (a picked or taken photo), refused above [MAX_SOURCE_BYTES]. */
fun readImage(context: Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > MAX_SOURCE_BYTES) throw IOException("the image is larger than ${MAX_SOURCE_BYTES / 1_000_000} MB")
    }
    out.toByteArray()
} ?: throw IOException("the image cannot be opened")

/**
 * [bytes] decoded with a sample size that keeps its longer side at least [minSide] (the phone has
 * little memory), turned as its EXIF orientation says; null when Android cannot read it.
 */
internal fun decodeUpright(bytes: ByteArray, minSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { postRotate(270f); postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** [source] with its longer side at most [maxSide]. */
internal fun scaled(source: Bitmap, maxSide: Int): Bitmap {
    val longer = maxOf(source.width, source.height)
    if (longer <= maxSide) return source
    val factor = maxSide.toFloat() / longer
    return Bitmap.createScaledBitmap(source, maxOf(1, (source.width * factor).toInt()), maxOf(1, (source.height * factor).toInt()), true)
}

@Suppress("DEPRECATION")
internal fun encodeWebp(bitmap: Bitmap): ByteArray {
    // Before Android 11, WEBP below quality 100 is the lossy encoder.
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    val out = ByteArrayOutputStream()
    bitmap.compress(format, WEBP_QUALITY, out)
    return out.toByteArray()
}
