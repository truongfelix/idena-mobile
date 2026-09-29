package io.github.truongfelix.idenanode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipDecodingTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    /**
     * A real flip as flip_get returns it (public and private part): flip 2 of the Idena desktop app's
     * test fixtures (idena-desktop test/e2e/flips.js, MIT). Checked against an independent decoder.
     */
    @Test
    fun decodesARealFlip() {
        val (publicHex, privateHex) = javaClass.classLoader!!.getResource("flip-fixture.txt")!!.readText().lines()
        val flip = decodeFlip("flip2", publicHex, privateHex)
        assertEquals(listOf(3443, 1808, 2502, 1404), flip.images.map { it.size })
        flip.images.forEach { assertArrayEquals(png, it.copyOfRange(0, 4)) }
        assertEquals(listOf(0, 1, 3, 2), flip.leftOrder)
        assertEquals(listOf(3, 2, 0, 1), flip.rightOrder)
        assertTrue(flip.loaded)
    }

    @Test
    fun decodesAnOldFlipWithoutPrivatePart() {
        val images = (1..4).map { i -> ByteArray(60 + i) { i.toByte() } }
        val hex = "0x" + hex(list(list(*images.map { bytes(it) }.toTypedArray()), list(order(0, 1, 2, 3), order(3, 2, 1, 0))))
        for (privateHex in listOf(null, "", "0x")) {
            val flip = decodeFlip("old", hex, privateHex)
            assertEquals(images.map { it.toList() }, flip.images.map { it.toList() })
            assertEquals(listOf(0, 1, 2, 3), flip.leftOrder)
            assertEquals(listOf(3, 2, 1, 0), flip.rightOrder)
        }
    }

    @Test
    fun rejectsWhatIsNotAFlip() {
        val images = list(bytes(byteArrayOf(1)), bytes(byteArrayOf(2)))
        val cases = listOf(
            // Truncated, and bytes after the item.
            "0xc3" to null,
            "0x" + hex(list(images, list(order(0, 1), order(1, 0)))) + "00" to null,
            // An image index beyond the images.
            "0x" + hex(list(images, list(order(0, 2), order(1, 0)))) to null,
            // One story only.
            "0x" + hex(list(images, list(order(0, 1)))) to null,
            "0x0g" to null,
        )
        for ((publicHex, privateHex) in cases) {
            assertThrows(publicHex, IllegalArgumentException::class.java) { decodeFlip("bad", publicHex, privateHex) }
        }
    }

    @Test
    fun decodesRlpLengths() {
        // Single bytes below 0x80 are their own encoding; 0x80 is the empty string (the integer 0).
        assertArrayEquals(byteArrayOf(0x7f), Rlp.decode(byteArrayOf(0x7f)).bytes())
        assertArrayEquals(ByteArray(0), Rlp.decode(byteArrayOf(0x80.toByte())).bytes())
        // Strings and lists longer than 55 bytes carry the size of their length.
        val long = ByteArray(1024) { it.toByte() }
        assertArrayEquals(long, Rlp.decode(bytes(long)).bytes())
        val items = Rlp.decode(list(*Array(30) { bytes(byteArrayOf(1, 2)) })).list()
        assertEquals(30, items.size)
        assertEquals(listOf<Byte>(1, 2), items[29].bytes().toList())
    }

    @Test
    fun readsHex() {
        assertArrayEquals(byteArrayOf(0x0a, 0x1b, 0xff.toByte()), hexToBytes("0x0a1bff"))
        assertArrayEquals(byteArrayOf(0x0a, 0x1b), hexToBytes("0A1B"))
        assertThrows(IllegalArgumentException::class.java) { hexToBytes("0x123") }
    }

    // A minimal RLP encoder for the test data.

    private fun bytes(value: ByteArray): ByteArray = when {
        value.size == 1 && value[0] in 0..0x7f -> value
        else -> prefix(0x80, 0xb7, value.size) + value
    }

    private fun list(vararg items: ByteArray): ByteArray {
        val body = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return prefix(0xc0, 0xf7, body.size) + body
    }

    private fun order(vararg indexes: Int): ByteArray =
        list(*indexes.map { if (it == 0) bytes(ByteArray(0)) else bytes(byteArrayOf(it.toByte())) }.toTypedArray())

    private fun prefix(short: Int, long: Int, size: Int): ByteArray {
        if (size <= 55) return byteArrayOf((short + size).toByte())
        val length = generateSequence(size) { it shr 8 }.takeWhile { it > 0 }.map { it.toByte() }.toList().reversed()
        return byteArrayOf((long + length.size).toByte()) + length.toByteArray()
    }

    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}
