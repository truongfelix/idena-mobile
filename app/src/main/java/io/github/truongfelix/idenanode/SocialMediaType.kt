package io.github.truongfelix.idenanode

/*
 * What a post's media file is, read from its first bytes, never only from the type the post declares: a file that is
 * not what it says is not played (B-222). Also what the phone posts for a file sent as it is, and the MP4 boxes that
 * hold where a phone video was taken, renamed so that no player reads them.
 */

/** A file's format, from its first bytes ([sniffFormat]). */
enum class MediaFormat { JPEG, PNG, GIF, WEBP, ANIMATED_WEBP, BMP, AVIF, HEIF, MP4, M4A, WEBM, OGG, MP3, UNKNOWN }

/** What a post's media is: a still picture, a moving picture, a video or a sound. */
enum class MediaKind { PICTURE, ANIMATION, VIDEO, AUDIO }

private fun ByteArray.at(offset: Int, vararg values: Int) =
    size >= offset + values.size && values.indices.all { this[offset + it].toInt() and 0xff == values[it] }

private fun ByteArray.ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())

/** The format of [bytes], from its first bytes. */
fun sniffFormat(bytes: ByteArray): MediaFormat = with(bytes) {
    when {
        at(0, 0xff, 0xd8, 0xff) -> MediaFormat.JPEG
        at(0, 0x89, 'P'.code, 'N'.code, 'G'.code, 0x0d, 0x0a, 0x1a, 0x0a) -> MediaFormat.PNG
        ascii(0, "GIF8") -> MediaFormat.GIF
        // WebP: an extended header (VP8X) with its animation flag is a moving picture.
        ascii(0, "RIFF") && ascii(8, "WEBP") ->
            if (ascii(12, "VP8X") && size > 20 && this[20].toInt() and 0x02 != 0) MediaFormat.ANIMATED_WEBP else MediaFormat.WEBP
        ascii(0, "BM") -> MediaFormat.BMP
        ascii(4, "ftyp") -> isoBrand(this)
        at(0, 0x1a, 0x45, 0xdf, 0xa3) -> if (containsAscii(this, "webm", 64)) MediaFormat.WEBM else MediaFormat.UNKNOWN
        ascii(0, "OggS") -> MediaFormat.OGG
        ascii(0, "ID3") -> MediaFormat.MP3
        // An MPEG audio frame: 11 sync bits, then a layer that is not 0 (layer 0 is AAC's ADTS).
        size >= 2 && this[0].toInt() and 0xff == 0xff && this[1].toInt() and 0xe0 == 0xe0 && (this[1].toInt() shr 1) and 0x03 != 0 -> MediaFormat.MP3
        else -> MediaFormat.UNKNOWN
    }
}

/** An ISO media file (an "ftyp" box first) by its major brand: AVIF and HEIF pictures, M4A sound, else MP4. */
private fun isoBrand(bytes: ByteArray): MediaFormat {
    val brand = if (bytes.size >= 12) String(bytes, 8, 4, Charsets.ISO_8859_1) else ""
    return when (brand) {
        "avif", "avis" -> MediaFormat.AVIF
        "heic", "heix", "heim", "heis", "mif1", "msf1" -> MediaFormat.HEIF
        "M4A ", "M4B " -> MediaFormat.M4A
        else -> MediaFormat.MP4
    }
}

private fun containsAscii(bytes: ByteArray, text: String, within: Int): Boolean {
    val last = minOf(bytes.size, within) - text.length
    return (0..last).any { bytes.ascii(it, text) }
}

/** The types of a post's video the phone plays, by the format of its file. */
private val VIDEO_FORMATS = setOf(MediaFormat.MP4, MediaFormat.WEBM)

/** The types of a post's sound the phone plays (an MP4 or WebM may hold sound only). */
private val AUDIO_FORMATS = setOf(MediaFormat.MP3, MediaFormat.M4A, MediaFormat.MP4, MediaFormat.OGG, MediaFormat.WEBM)

/**
 * What a post's media declared as [declaredType] is when its file [bytes] really is one: a video or a sound the phone
 * plays; null when the file is something else (it is then not played) or the type is neither video nor audio.
 */
fun playableKind(declaredType: String, bytes: ByteArray): MediaKind? {
    val type = declaredType.lowercase()
    val format = sniffFormat(bytes)
    return when {
        type.startsWith("video/") && format in VIDEO_FORMATS -> MediaKind.VIDEO
        type.startsWith("audio/") && format in AUDIO_FORMATS -> MediaKind.AUDIO
        else -> null
    }
}

/**
 * The type the phone posts for a file sent as it is, by its [format]; [audio] when it was picked as a sound (an MP4,
 * WebM or Ogg file is then a sound, else a video). Null for a format that is not posted as it is (a still picture is
 * encoded as WebP, [encodeImageSizes]).
 */
fun postedType(format: MediaFormat, audio: Boolean): String? = when (format) {
    MediaFormat.GIF -> "image/gif"
    MediaFormat.ANIMATED_WEBP -> "image/webp"
    MediaFormat.MP3 -> "audio/mpeg"
    MediaFormat.M4A -> "audio/mp4"
    MediaFormat.OGG -> "audio/ogg"
    MediaFormat.MP4 -> if (audio) "audio/mp4" else "video/mp4"
    MediaFormat.WEBM -> if (audio) "audio/webm" else "video/webm"
    else -> null
}

/** What a post's media of [type] is, from its declared type alone. */
fun kindOfType(type: String): MediaKind = type.lowercase().let {
    when {
        it.startsWith("video/") -> MediaKind.VIDEO
        it.startsWith("audio/") -> MediaKind.AUDIO
        it == "image/gif" -> MediaKind.ANIMATION
        else -> MediaKind.PICTURE
    }
}

/**
 * The boxes of an MP4 that hold its details (place, camera, title; "uuid": XMP, where some cameras write the place):
 * renamed "free", which every reader skips.
 */
private val DETAIL_BOXES = setOf("udta", "meta", "uuid")

/** The boxes holding other boxes where details may sit. */
private val PARENT_BOXES = setOf("moov", "trak")

/**
 * [bytes] (an MP4 or M4A file) with its detail boxes ([DETAIL_BOXES]: where a phone writes the place a video was taken)
 * renamed "free" at the top, in "moov" and in its tracks. Nothing moves, so every offset in the file stays right. A
 * file that is not an MP4 comes back unchanged; null for an MP4 whose boxes do not add up (its details cannot be
 * found, so it is not sent).
 */
fun withoutMp4Details(bytes: ByteArray): ByteArray? {
    if (sniffFormat(bytes) !in setOf(MediaFormat.MP4, MediaFormat.M4A)) return bytes
    val out = bytes.copyOf()
    return if (renameDetails(out, 0, out.size)) out else null
}

/** Renames the detail boxes between [from] and [to] (and in their parent boxes); false when a box does not fit. */
private fun renameDetails(bytes: ByteArray, from: Int, to: Int): Boolean {
    var offset = from
    while (offset + 8 <= to) {
        var size = readUInt32(bytes, offset)
        var header = 8
        when (size) {
            0L -> size = (to - offset).toLong()
            1L -> {
                if (offset + 16 > to) return false
                size = (readUInt32(bytes, offset + 8) shl 32) or readUInt32(bytes, offset + 12)
                header = 16
            }
        }
        if (size < header || offset + size > to) return false
        val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
        val end = (offset + size).toInt()
        when (type) {
            in DETAIL_BOXES -> "free".forEachIndexed { i, c -> bytes[offset + 4 + i] = c.code.toByte() }
            in PARENT_BOXES -> if (!renameDetails(bytes, offset + header, end)) return false
        }
        offset = end
    }
    return true
}

private fun readUInt32(bytes: ByteArray, offset: Int): Long =
    (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xff) }
