package io.github.truongfelix.idenanode

/*
 * Flips as the node returns them (flip_get): RLP, as the Idena apps encode them when a flip is made.
 * The public part is [images 1-2], the private part [images 3-4, orders]; an order lists the four
 * image indexes of a story, each an RLP integer (0 is the empty string). Older flips have no private
 * part: the public part is then [images, orders].
 */

/** Decodes the flip the node returned; throws [IllegalArgumentException] if it is not a flip. */
fun decodeFlip(hash: String, publicHex: String, privateHex: String?, keywords: List<Keyword> = emptyList()): Flip {
    val public = Rlp.decode(hexToBytes(publicHex)).list()
    val private = privateHex?.takeIf { it.isNotEmpty() && it != "0x" }?.let { Rlp.decode(hexToBytes(it)).list() }
    val images: List<ByteArray>
    val orders: List<Rlp.Item>
    if (private != null) {
        images = public[0].list().map { it.bytes() } + private[0].list().map { it.bytes() }
        orders = private[1].list()
    } else {
        images = public[0].list().map { it.bytes() }
        orders = public[1].list()
    }
    require(orders.size == 2) { "a flip has two stories, not ${orders.size}" }
    val (left, right) = orders.map { order ->
        order.list().map { index ->
            val bytes = index.bytes()
            val value = if (bytes.isEmpty()) 0 else bytes[0].toInt() and 0xff
            require(value in images.indices) { "image $value of ${images.size}" }
            value
        }
    }
    return Flip(hash, images, left, right, keywords)
}

/** "0x0a1b..." or "0a1b..." to bytes. */
fun hexToBytes(hex: String): ByteArray {
    val digits = hex.removePrefix("0x")
    require(digits.length % 2 == 0) { "odd hex length" }
    return ByteArray(digits.length / 2) { i ->
        val high = Character.digit(digits[2 * i], 16)
        val low = Character.digit(digits[2 * i + 1], 16)
        require(high >= 0 && low >= 0) { "not hex" }
        (high shl 4 or low).toByte()
    }
}

/** [bytes] as "0x0a1b...". */
fun bytesToHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = CharArray(2 + bytes.size * 2)
    out[0] = '0'
    out[1] = 'x'
    bytes.forEachIndexed { i, b ->
        out[2 + 2 * i] = digits[(b.toInt() shr 4) and 0xf]
        out[3 + 2 * i] = digits[b.toInt() and 0xf]
    }
    return String(out)
}

/** A minimal RLP decoder (Ethereum's recursive length prefix): byte strings and lists. */
object Rlp {
    sealed class Item {
        class Bytes(val value: ByteArray) : Item()
        class List(val items: kotlin.collections.List<Item>) : Item()

        fun bytes(): ByteArray = (this as? Bytes)?.value ?: throw IllegalArgumentException("a list, not bytes")
        fun list(): kotlin.collections.List<Item> = (this as? List)?.items ?: throw IllegalArgumentException("bytes, not a list")
    }

    /** Decodes one item that spans all of [data]. */
    fun decode(data: ByteArray): Item {
        val (item, end) = decodeAt(data, 0, data.size)
        require(end == data.size) { "${data.size - end} bytes after the item" }
        return item
    }

    /** Decodes the item at [start], within [limit]; returns it and where it ends. */
    private fun decodeAt(data: ByteArray, start: Int, limit: Int): Pair<Item, Int> {
        require(start < limit) { "truncated" }
        val prefix = data[start].toInt() and 0xff
        return when {
            prefix < 0x80 -> Item.Bytes(byteArrayOf(data[start])) to start + 1
            prefix <= 0xb7 -> bytesAt(data, start + 1, prefix - 0x80, limit)
            prefix <= 0xbf -> {
                val lengthSize = prefix - 0xb7
                bytesAt(data, start + 1 + lengthSize, length(data, start + 1, lengthSize, limit), limit)
            }
            else -> {
                val (first, size) = if (prefix <= 0xf7) {
                    start + 1 to prefix - 0xc0
                } else {
                    val lengthSize = prefix - 0xf7
                    start + 1 + lengthSize to length(data, start + 1, lengthSize, limit)
                }
                val end = first + size
                require(end in first..limit) { "truncated list" }
                val items = mutableListOf<Item>()
                var at = first
                while (at < end) {
                    val (item, next) = decodeAt(data, at, end)
                    items += item
                    at = next
                }
                Item.List(items) to end
            }
        }
    }

    private fun bytesAt(data: ByteArray, start: Int, size: Int, limit: Int): Pair<Item, Int> {
        val end = start + size
        require(size >= 0 && end in start..limit) { "truncated string" }
        return Item.Bytes(data.copyOfRange(start, end)) to end
    }

    private fun length(data: ByteArray, start: Int, size: Int, limit: Int): Int {
        require(size in 1..4 && start + size <= limit) { "bad length" }
        var value = 0L
        for (i in 0 until size) value = value shl 8 or (data[start + i].toLong() and 0xff)
        require(value <= Int.MAX_VALUE) { "too long" }
        return value.toInt()
    }
}
