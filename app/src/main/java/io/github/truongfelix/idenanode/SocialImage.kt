package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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
fun encodeImageSizes(context: Context, uri: Uri): Map<Int, ByteArray> = encodeImageBytes(readImage(context, uri))

/** The picture [bytes] encoded at each size of [IMAGE_SIZES], as [encodeImageSizes]. */
fun encodeImageBytes(bytes: ByteArray): Map<Int, ByteArray> {
    val source = decodeUpright(bytes, IMAGE_SIZES.last()) ?: throw IOException(t(R.string.social_error_image_unreadable))
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
        if (out.size() > MAX_SOURCE_BYTES) throw IOException(t(R.string.social_error_image_too_large, MAX_SOURCE_BYTES / 1_000_000))
    }
    out.toByteArray()
} ?: throw IOException(t(R.string.social_error_image_cannot_open))

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

/** The size of the file at [uri] as the picker gives it, or null when it does not. */
private fun fileSize(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull()

/** How a picked file is posted ([pickedAs]). */
sealed interface PickedAs {
    /** Encoded as a WebP picture at the offered sizes; [tooLargeToMove] when it moved but is above [MAX_IPFS_FILE]. */
    data class Still(val tooLargeToMove: Boolean = false) : PickedAs

    /** Sent as it is, with this type ([postedType]). */
    data class AsIs(val type: String) : PickedAs

    /** A video or a sound above [MAX_IPFS_FILE]: refused, named by [what] ([ipfsFileName]). */
    data class TooLarge(val what: String) : PickedAs
}

/**
 * How a picked file of [format] and [size] bytes is posted (B-222): a moving picture ([animated]) and a video or a
 * sound as they are when they fit in one IPFS file; a moving picture that does not becomes a still one, a video or a
 * sound that does not is refused; any other picture is encoded as WebP. [audio] when it was picked as a sound.
 */
fun pickedAs(format: MediaFormat, animated: Boolean, size: Int, audio: Boolean): PickedAs {
    val fits = size <= MAX_IPFS_FILE
    return when {
        format == MediaFormat.GIF || format == MediaFormat.ANIMATED_WEBP ->
            if (!animated) PickedAs.Still() else if (fits) PickedAs.AsIs(postedType(format, audio)!!) else PickedAs.Still(tooLargeToMove = true)
        else -> {
            val type = postedType(format, audio) ?: return PickedAs.Still()
            if (fits) PickedAs.AsIs(type) else PickedAs.TooLarge(if (kindOfType(type) == MediaKind.VIDEO) "video" else "audio")
        }
    }
}

/**
 * The draft of the file at [uri] (from the photo picker, or the sound picker when [audio]): see [pickedAs]. An MP4 sent
 * as it is leaves out its details ([withoutMp4Details]). Throws when it cannot be read or is too large.
 */
fun pickMedia(context: Context, uri: Uri, audio: Boolean): ImageDraft {
    val pickedType = context.contentResolver.getType(uri).orEmpty()
    // A video or a sound above one IPFS file is refused from its size, before it is read.
    if (audio || pickedType.startsWith("video/") || pickedType.startsWith("audio/")) {
        val size = fileSize(context, uri)
        if (size != null && size > MAX_IPFS_FILE) {
            val what = if (pickedType.startsWith("video/") && !audio) "video" else "audio"
            throw IOException(t(R.string.social_error_too_large, ipfsFileName(what), (size / 1000).toInt(), MAX_IPFS_FILE / 1000))
        }
    }
    val bytes = readImage(context, uri)
    val format = sniffFormat(bytes)
    val asSound = audio || pickedType.startsWith("audio/")
    val animated = (format == MediaFormat.GIF || format == MediaFormat.ANIMATED_WEBP) && isAnimation(bytes)
    return when (val picked = pickedAs(format, animated, bytes.size, asSound)) {
        is PickedAs.AsIs -> ImageDraft.asIs(withoutMp4Details(bytes) ?: throw IOException(t(R.string.social_error_video_unreadable)), picked.type)
        is PickedAs.TooLarge -> throw IOException(t(R.string.social_error_too_large, ipfsFileName(picked.what), bytes.size / 1000, MAX_IPFS_FILE / 1000))
        is PickedAs.Still -> {
            // A picture picked as a sound is not one.
            if (audio && format != MediaFormat.UNKNOWN) throw IOException(t(R.string.social_error_not_sound))
            if (format == MediaFormat.UNKNOWN && (audio || pickedType.startsWith("video/") || pickedType.startsWith("audio/"))) {
                throw IOException(t(R.string.social_error_media_format))
            }
            val sizes = encodeImageBytes(bytes)
            ImageDraft(
                sizes,
                if (DEFAULT_IMAGE_SIZE in sizes) DEFAULT_IMAGE_SIZE else sizes.keys.max(),
                note = if (picked.tooLargeToMove) t(R.string.social_media_still_instead, bytes.size / 1000, MAX_IPFS_FILE / 1000) else null,
            )
        }
    }
}
