package io.github.truongfelix.idenanode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTest {
    private val a = "0x34004a3e0000000000000000000000000000b850"
    private val b = "0x9f1c2e7d5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d"

    @Test
    fun identiconIsStableSymmetricAndPerAddress() {
        val (cells, hue) = identicon(a)
        assertEquals(IDENTICON_SIZE * IDENTICON_SIZE, cells.size)
        assertTrue(hue in 0 until 360)
        // The same address, in any case, gives the same picture.
        assertArrayEquals(cells, identicon(a.uppercase().replace("0X", "0x")).first)
        assertEquals(hue, identicon(a.uppercase()).second)
        for (row in 0 until IDENTICON_SIZE) {
            for (column in 0 until IDENTICON_SIZE) {
                assertEquals(cells[row * IDENTICON_SIZE + column], cells[row * IDENTICON_SIZE + IDENTICON_SIZE - 1 - column])
            }
        }
        assertNotEquals(cells.toList(), identicon(b).first.toList())
        // No crash on an empty or odd text.
        identicon("")
        identicon("0x")
    }
}
