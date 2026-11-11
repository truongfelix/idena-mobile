package io.github.truongfelix.idenanode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SocialMediaTypeTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** An ISO box: its 32-bit size, its type, its content. */
    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { acc, part -> acc + part }
        val size = 8 + body.size
        return bytes(size ushr 24, (size ushr 16) and 0xff, (size ushr 8) and 0xff, size and 0xff) + ascii(type) + body
    }

    private fun ftyp(brand: String) = box("ftyp", ascii(brand), bytes(0, 0, 0, 0), ascii("isom"))

    private fun webp(chunk: String, flags: Int = 0) = ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBP") + ascii(chunk) +
        bytes(10, 0, 0, 0, flags, 0, 0, 0)

    @Test
    fun formatFromTheFirstBytes() {
        assertEquals(MediaFormat.JPEG, sniffFormat(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals(MediaFormat.PNG, sniffFormat(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        assertEquals(MediaFormat.GIF, sniffFormat(ascii("GIF89a")))
        assertEquals("a simple WebP", MediaFormat.WEBP, sniffFormat(webp("VP8 ")))
        assertEquals("an extended WebP without animation", MediaFormat.WEBP, sniffFormat(webp("VP8X", 0x10)))
        assertEquals("the animation flag", MediaFormat.ANIMATED_WEBP, sniffFormat(webp("VP8X", 0x12)))
        assertEquals(MediaFormat.AVIF, sniffFormat(ftyp("avif")))
        assertEquals("an AVIF sequence", MediaFormat.AVIF, sniffFormat(ftyp("avis")))
        assertEquals(MediaFormat.HEIF, sniffFormat(ftyp("heic")))
        assertEquals(MediaFormat.M4A, sniffFormat(ftyp("M4A ")))
        assertEquals(MediaFormat.MP4, sniffFormat(ftyp("isom")))
        assertEquals("any other brand is an MP4", MediaFormat.MP4, sniffFormat(ftyp("mp42")))
        assertEquals(MediaFormat.WEBM, sniffFormat(bytes(0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x82, 0x84) + ascii("webm")))
        assertEquals("a Matroska file that is not WebM", MediaFormat.UNKNOWN, sniffFormat(bytes(0x1a, 0x45, 0xdf, 0xa3) + ascii("matroska")))
        assertEquals(MediaFormat.OGG, sniffFormat(ascii("OggS")))
        assertEquals("an MP3 with its tags", MediaFormat.MP3, sniffFormat(ascii("ID3") + bytes(4, 0)))
        assertEquals("an MP3 frame (layer 3)", MediaFormat.MP3, sniffFormat(bytes(0xff, 0xfb, 0x90, 0x64)))
        assertEquals("AAC in ADTS (layer 0) is not an MP3", MediaFormat.UNKNOWN, sniffFormat(bytes(0xff, 0xf1, 0x50, 0x80)))
        assertEquals("an SVG is text", MediaFormat.UNKNOWN, sniffFormat(ascii("<svg xmlns=")))
        assertEquals(MediaFormat.UNKNOWN, sniffFormat(ByteArray(0)))
    }

    @Test
    fun playedOnlyWhenTheFileIsWhatThePostDeclares() {
        val mp4 = ftyp("isom")
        assertEquals(MediaKind.VIDEO, playableKind("video/mp4", mp4))
        assertEquals("the declared type's case", MediaKind.VIDEO, playableKind("Video/MP4", mp4))
        assertEquals("an MP4 may hold sound only", MediaKind.AUDIO, playableKind("audio/mp4", mp4))
        assertEquals(MediaKind.AUDIO, playableKind("audio/mpeg", ascii("ID3") + bytes(4, 0)))
        assertEquals(MediaKind.AUDIO, playableKind("audio/ogg", ascii("OggS")))
        assertEquals(MediaKind.AUDIO, playableKind("audio/x-m4a", ftyp("M4A ")))
        assertNull("a sound file is no video", playableKind("video/mp4", ascii("OggS")))
        assertNull("a picture declared as a video", playableKind("video/mp4", ascii("GIF89a")))
        assertNull("text declared as a sound", playableKind("audio/mpeg", ascii("<html>")))
        assertNull("a picture's type is not played", playableKind("image/gif", ascii("GIF89a")))
    }

    @Test
    fun typePostedForAFileSentAsItIs() {
        assertEquals("image/gif", postedType(MediaFormat.GIF, audio = false))
        assertEquals("image/webp", postedType(MediaFormat.ANIMATED_WEBP, audio = false))
        assertEquals("video/mp4", postedType(MediaFormat.MP4, audio = false))
        assertEquals("picked as a sound", "audio/mp4", postedType(MediaFormat.MP4, audio = true))
        assertEquals("video/webm", postedType(MediaFormat.WEBM, audio = false))
        assertEquals("audio/webm", postedType(MediaFormat.WEBM, audio = true))
        assertEquals("audio/ogg", postedType(MediaFormat.OGG, audio = false))
        assertEquals("audio/mpeg", postedType(MediaFormat.MP3, audio = true))
        assertEquals("audio/mp4", postedType(MediaFormat.M4A, audio = false))
        assertNull("a still picture is encoded as WebP", postedType(MediaFormat.JPEG, audio = false))
        assertNull(postedType(MediaFormat.WEBP, audio = false))
        assertNull(postedType(MediaFormat.UNKNOWN, audio = true))
    }

    @Test
    fun aPickedFileIsSentAsItIsWhenItFits() {
        val fits = MAX_IPFS_FILE
        val over = MAX_IPFS_FILE + 1
        assertEquals(PickedAs.AsIs("image/gif"), pickedAs(MediaFormat.GIF, animated = true, size = fits, audio = false))
        assertEquals(PickedAs.AsIs("image/webp"), pickedAs(MediaFormat.ANIMATED_WEBP, animated = true, size = 1000, audio = false))
        assertEquals("a moving picture too large moves no more", PickedAs.Still(tooLargeToMove = true), pickedAs(MediaFormat.GIF, animated = true, size = over, audio = false))
        assertEquals("a GIF of one frame is a picture", PickedAs.Still(), pickedAs(MediaFormat.GIF, animated = false, size = 1000, audio = false))
        assertEquals(PickedAs.AsIs("video/mp4"), pickedAs(MediaFormat.MP4, animated = false, size = fits, audio = false))
        assertEquals(PickedAs.TooLarge("video"), pickedAs(MediaFormat.MP4, animated = false, size = over, audio = false))
        assertEquals(PickedAs.AsIs("audio/ogg"), pickedAs(MediaFormat.OGG, animated = false, size = 1000, audio = true))
        assertEquals(PickedAs.TooLarge("audio"), pickedAs(MediaFormat.MP3, animated = false, size = over, audio = true))
        assertEquals("an MP4 picked as a sound", PickedAs.AsIs("audio/mp4"), pickedAs(MediaFormat.MP4, animated = false, size = 1000, audio = true))
        assertEquals("a photo is encoded", PickedAs.Still(), pickedAs(MediaFormat.JPEG, animated = false, size = over, audio = false))
        assertEquals(PickedAs.Still(), pickedAs(MediaFormat.AVIF, animated = false, size = 1000, audio = false))
    }

    @Test
    fun kindFromTheDeclaredType() {
        assertEquals(MediaKind.VIDEO, kindOfType("video/webm"))
        assertEquals(MediaKind.AUDIO, kindOfType("AUDIO/OGG"))
        assertEquals(MediaKind.ANIMATION, kindOfType("image/gif"))
        assertEquals(MediaKind.PICTURE, kindOfType("image/webp"))
    }

    @Test
    fun theDetailsOfAnMp4AreRenamedInPlace() {
        val place = box("udta", box("©xyz", ascii("+48.85+002.35/")))
        val keys = box("meta", ascii("com.apple.quicktime.location.ISO6709"))
        val track = box("trak", box("tkhd", bytes(1, 2, 3)), box("udta", ascii("track name")))
        val file = ftyp("mp42") + box("moov", box("mvhd", bytes(9, 9)), place, keys, track) + box("mdat", bytes(7, 7, 7))
        val cleaned = withoutMp4Details(file)
        assertEquals("nothing moves", file.size, cleaned.size)
        val text = String(cleaned, Charsets.ISO_8859_1)
        assertEquals("no detail box is left", -1, text.indexOf("udta"))
        assertEquals(-1, text.indexOf("meta"))
        assertEquals("the moov's two and the track's one", 3, Regex("free").findAll(text).count())
        // Only the four type bytes of each renamed box changed.
        val changed = file.indices.filter { file[it] != cleaned[it] }
        assertEquals(12, changed.size)
        assertArrayEquals("the media itself is untouched", box("mdat", bytes(7, 7, 7)), cleaned.copyOfRange(file.size - 11, file.size))
    }

    @Test
    fun aFileThatIsNotACleanMp4ComesBackUnchanged() {
        val ogg = ascii("OggS") + ascii("udta")
        assertSame("not an MP4", ogg, withoutMp4Details(ogg))
        // A moov whose size runs past the end of the file.
        val broken = ftyp("isom") + bytes(0, 0, 0x10, 0) + ascii("moov") + box("udta", ascii("x"))
        assertSame(broken, withoutMp4Details(broken))
        val noDetails = ftyp("isom") + box("moov", box("mvhd", bytes(1)))
        assertArrayEquals(noDetails, withoutMp4Details(noDetails))
    }
}
