package io.github.truongfelix.idenanode

/*
 * Keccak-256 (the original padding 0x01, not SHA3-256's 0x06), as js-sha3's keccak256 that idena.social uses for a
 * message's messageHash. Android has no Keccak.
 */

private val ROUND_CONSTANTS = longArrayOf(
    0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808AuL.toLong(),
    0x8000000080008000uL.toLong(), 0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(),
    0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(), 0x000000000000008AuL.toLong(),
    0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
    0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(),
    0x8000000000008003uL.toLong(), 0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
    0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(), 0x8000000080008081uL.toLong(),
    0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
)

/** The rotation of each lane (x + 5y) in the rho step. */
private val ROTATIONS = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

private const val RATE = 136

private fun keccakF(a: LongArray) {
    val c = LongArray(5)
    val b = LongArray(25)
    for (round in 0 until 24) {
        for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
        for (x in 0 until 5) {
            val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
            for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
        }
        for (x in 0 until 5) for (y in 0 until 5) {
            b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROTATIONS[x + 5 * y])
        }
        for (y in 0 until 25 step 5) for (x in 0 until 5) {
            a[y + x] = b[y + x] xor (b[y + (x + 1) % 5].inv() and b[y + (x + 2) % 5])
        }
        a[0] = a[0] xor ROUND_CONSTANTS[round]
    }
}

/** The Keccak-256 hash of [data]. */
fun keccak256(data: ByteArray): ByteArray {
    val state = LongArray(25)
    val padded = ByteArray((data.size / RATE + 1) * RATE)
    data.copyInto(padded)
    padded[data.size] = (padded[data.size].toInt() xor 0x01).toByte()
    padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
    for (block in padded.indices step RATE) {
        for (i in 0 until RATE / 8) {
            var lane = 0L
            for (j in 0 until 8) lane = lane or ((padded[block + 8 * i + j].toLong() and 0xff) shl (8 * j))
            state[i] = state[i] xor lane
        }
        keccakF(state)
    }
    return ByteArray(32) { (state[it / 8] ushr (8 * (it % 8))).toByte() }
}

/** [text]'s Keccak-256 in lowercase hex, without 0x (js-sha3's keccak256 of a string: its UTF-8 bytes). */
fun keccak256Hex(text: String): String = keccak256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
