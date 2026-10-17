package io.github.truongfelix.idenanode

import java.math.BigDecimal

/**
 * [text] as a JSON string, escaped as JavaScript's JSON.stringify escapes it (idena.social writes its
 * arguments with it). Android's org.json would also escape "/", which the contract's parser may not.
 */
fun jsonString(text: String): String {
    val out = StringBuilder("\"")
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            // A lone surrogate is escaped; a pair is kept.
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                out.append(c).append(text[i + 1])
                i++
            }
            c.isSurrogate() -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        i++
    }
    return out.append('"').toString()
}

/** The argument of a sendTip call: [amount] whole iDNA for the author of post [postId]. */
fun sendTipArgument(postId: Long, amount: Long): String =
    "{\"postId\":${jsonString(postId.toString())},\"tipAmount\":${jsonString(amount.toString())}}"

/**
 * The argument of a makePost call, with idena.social's keys in its order: [message], [replyTo] for a
 * reply, a comment or a like, [channel] for a comment or a like inside a reply's discussion, and one
 * [media] ("ipfs://<cid>") of [mediaType].
 */
fun makePostArgument(
    message: String,
    replyTo: Long? = null,
    channel: String? = null,
    media: String? = null,
    mediaType: String? = null,
): String = buildString {
    append("{\"message\":").append(jsonString(message))
    replyTo?.let { append(",\"replyToPostId\":").append(jsonString(it.toString())) }
    channel?.let { append(",\"channelId\":").append(jsonString(it)) }
    if (media != null && mediaType != null) {
        append(",\"media\":[").append(jsonString(media)).append("],\"mediaType\":[").append(jsonString(mediaType)).append(']')
    }
    append('}')
}

/*
 * Files stored on IPFS, as idena.social does with the node (storeFileToIpfs in asyncUtils.ts): ipfs_add
 * pins the file on the node, then a storeToIpfs transaction asks the network to keep it. Its fee grows
 * with the file: idena-go counts a fifth of the file's size as transaction bytes (fee.getTxSizeForFee).
 */

/** The largest file stored at once: the node takes requests up to 2 MB, and the file goes as hex. */
const val MAX_IPFS_FILE = 900_000

/** The bytes of a CID string as ipfs_add gives it: version 1, base32 ("b" then RFC 4648 lowercase). */
fun cidBytes(cid: String): ByteArray {
    require(cid.length > 1 && cid[0] == 'b') { "not a version 1 CID: $cid" }
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in cid.substring(1)) {
        val value = alphabet.indexOf(c)
        require(value >= 0) { "not base32: $cid" }
        buffer = (buffer shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xff)
        }
    }
    return out.toByteArray()
}

/** The payload of a storeToIpfs transaction (ProtoStoreToIpfsAttachment: cid = 1, size = 2). */
fun storeToIpfsPayload(cid: String, size: Int): ByteArray {
    val bytes = cidBytes(cid)
    val out = java.io.ByteArrayOutputStream()
    fun varint(value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
    out.write(0x0a)
    varint(bytes.size.toLong())
    out.write(bytes)
    out.write(0x10)
    varint(size.toLong())
    return out.toByteArray()
}

/**
 * About what storing a file of [size] bytes costs, in iDNA, at [feePerGas] (bcn_feePerGas, in the
 * smallest unit): ten gas per transaction byte, the transaction itself about 150 bytes with its signature.
 */
fun storeToIpfsFeeEstimate(feePerGas: BigDecimal, size: Int): BigDecimal {
    val gas = (150 + (size * 0.2f).toInt()) * 10L
    return (feePerGas * BigDecimal(gas)).movePointLeft(18)
}

/** What a post, reply, comment or like answers on idena.social: its makePost argument's fields. */
data class PostTarget(val replyTo: Long?, val channel: String?) {
    companion object {
        val NEW_POST = PostTarget(null, null)

        /** A reply to post [postId], or a like of it. */
        fun onPost(postId: Long) = PostTarget(postId, null)

        /** A comment or a like on reply [replyId]. */
        fun onReply(replyId: Long) = PostTarget(replyId, discussChannel(replyId))

        /** A comment answering, or a like of, comment [commentId] under reply [replyId]. */
        fun onComment(replyId: Long, commentId: Long) = PostTarget(commentId, discussChannel(replyId))
    }
}
