package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialImageViewerTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun fileTypeFromTheFirstBytes() {
        assertEquals("image/jpeg" to "jpg", imageFileType(bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0x10)))
        assertEquals("image/png" to "png", imageFileType(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0)))
        assertEquals("image/gif" to "gif", imageFileType(ascii("GIF89a")))
        assertEquals("image/webp" to "webp", imageFileType(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8 ")))
        assertNull("a RIFF file that is not WebP (a WAV)", imageFileType(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WAVEfmt ")))
        assertNull("an SVG is text", imageFileType(ascii("<svg xmlns=")))
        assertNull("too short", imageFileType(bytes(0xff, 0xd8)))
        assertNull(imageFileType(ByteArray(0)))
    }

    @Test
    fun galleryNameFromTheKey() {
        assertEquals("16 characters of the key", "idena-bafkreiabcdefghi.webp", savedImageName("bafkreiabcdefghijklmnop", "webp"))
        assertEquals("a transaction hash, without 0x", "idena-7f7ae1d8105ced21.jpg", savedImageName("0x7f7ae1d8105ced21d1e935a05d", "jpg"))
        assertEquals("only letters and digits", "idena-ab.png", savedImageName("a/../b", "png"))
    }
}
