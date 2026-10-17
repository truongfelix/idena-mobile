package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/*
 * idena.social (https://idena.social), read from the node: posts are makePost calls of its contracts,
 * whose JSON argument holds the text, and tips are sendTip (and, in v1 and v5, sendTipFromBalance) calls. The
 * rules follow idena.social-ui (getNewPosterAndPost in src/logic/asyncUtils.ts, PostComponent.tsx); each
 * contract version's calls are read in its own format (SocialVersions.kt).
 */

/** The idena.social contract (idena.social-ui v12) and the block where its posts start. */
const val SOCIAL_CONTRACT = "0x840e092e31e9656fF15E541505039ed77585338E"
const val SOCIAL_FIRST_BLOCK = 10_929_805L

/** A reply made of this text only is a like of the post it replies to. */
const val LIKE = "❤️"

/** A makePost call as found in a block. */
data class PostCall(
    val hash: String,
    val height: Long,
    /** The block time, Unix seconds. */
    val time: Long,
    /** The position of the transaction in its block. */
    val index: Int,
    /** The sender, lowercase. */
    val author: String,
    /** The text, or "ipfs://<cid>" when idena.social stored it on IPFS. */
    val message: String,
    /** The id of the post it replies to, as the contract argument has it; empty for a post. */
    val replyTo: String,
    /** Empty for the main feed; "discuss:<id>" for a comment on a reply; "r/<name>" for a post in a topic. */
    val channel: String,
    val hasMedia: Boolean,
    /** The first media: "ipfs://<cid>" or inline base64; empty without media or when too large to keep. */
    val media: String = "",
    /** Its type as the post declares it (image/png, video/mp4...). */
    val mediaType: String = "",
    /** The older contract version it was made with ([SocialVersion.name]); empty for the current contract. */
    val version: String = "",
) {
    val isLike: Boolean
        get() = message == LIKE && replyTo.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("author", author)
        .put("message", message).put("replyTo", replyTo).put("channel", channel).put("hasMedia", hasMedia)
        .put("media", media).put("mediaType", mediaType).put("version", version)

    companion object {
        /** Inline media larger than this (base64 characters, about 1 MB) is not kept in the scan. */
        const val MAX_INLINE_MEDIA = 1_400_000

        fun fromJson(json: JSONObject) = PostCall(
            hash = json.getString("hash"),
            height = json.getLong("height"),
            time = json.getLong("time"),
            index = json.getInt("index"),
            author = json.getString("author"),
            message = json.optString("message"),
            replyTo = json.optString("replyTo"),
            channel = json.optString("channel"),
            hasMedia = json.optBoolean("hasMedia"),
            media = json.optString("media"),
            mediaType = json.optString("mediaType"),
            version = json.optString("version"),
        )

        /**
         * The post of a makePost call of contract [version], from its first argument (hex of the JSON text
         * idena.social sends: message, replyToPostId, channelId, media, mediaType); null if the argument is not
         * such JSON. v1 and v5 refuse a post without a message, and their media fields were never used.
         */
        fun parse(hash: String, height: Long, time: Long, index: Int, from: String, argHex: String, version: SocialVersion = CURRENT_VERSION): PostCall? {
            val args = runCatching { JSONObject(String(hexToBytes(argHex), Charsets.UTF_8)) }.getOrNull() ?: return null
            val older = version.format == CallFormat.V1
            if (older && args.optString("message").isEmpty()) return null
            val media = args.optJSONArray("media")
            val mediaType = args.optJSONArray("mediaType")
            val hasMedia = !older && media != null && media.length() > 0 && mediaType != null && mediaType.length() > 0
            val first = if (hasMedia) media!!.optString(0) else ""
            val replyTo = args.optString("replyToPostId")
            return PostCall(
                hash = hash,
                height = height,
                time = time,
                index = index,
                author = from.lowercase(),
                message = args.optString("message"),
                replyTo = if (older) olderReplyTo(replyTo) else replyTo,
                channel = args.optString("channelId"),
                hasMedia = hasMedia,
                media = first.takeIf { it.startsWith("ipfs://") || it.length <= MAX_INLINE_MEDIA }.orEmpty(),
                mediaType = if (hasMedia) mediaType!!.optString(0) else "",
                version = if (version.isCurrent) "" else version.name,
            )
        }

        private val HEX_ID = Regex("^0x[0-9a-fA-F]+$")

        /**
         * The post a v1 or v5 reply answers: the web app first wrote its id as hex, the 16 bytes of a
         * little-endian u128 ("0x2e00…" for 46), later as a decimal.
         */
        fun olderReplyTo(value: String): String {
            if (!HEX_ID.matches(value)) return value
            val hex = value.substring(2)
            var id = 0L
            // Ids stay far below 2^48: the upper bytes are zero.
            var i = minOf(hex.length, 12) - 2
            while (i >= 0) {
                id = id * 256 + hex.substring(i, i + 2).toLong(16)
                i -= 2
            }
            return id.toString()
        }
    }

    /** Whether it was made with an older contract: read only. */
    val isOlder: Boolean
        get() = version.isNotEmpty()
}

private val CID = Regex("^[A-Za-z0-9]{10,128}$")

/**
 * The IPFS content id of an "ipfs://<cid>" reference (a post's text or media), or null when [ref] is not
 * one or the id is not plain letters and digits (it names a file in the app's storage).
 */
fun ipfsCid(ref: String): String? = ref.removePrefix("ipfs://").takeIf { ref.startsWith("ipfs://") && CID.matches(it) }

/** A sendTip call as found in a block: [amount] iDNA sent, [tipAmount] of them for post [postId]'s author. */
data class TipCall(
    val hash: String,
    val height: Long,
    val time: Long,
    val index: Int,
    /** The tipper, lowercase. */
    val from: String,
    val postId: String,
    /** The tip as the argument has it; the contract takes the whole iDNA it starts with. */
    val tipAmount: String,
    /** The iDNA sent with the call (decimal). */
    val amount: String,
    /** The older contract version it was made with ([SocialVersion.name]); empty for the current contract. */
    val version: String = "",
    /** Before v11: the tip is all that was sent. */
    val sent: Boolean = false,
    /** A sendTipFromBalance of v1 or v5: the tip, [tipAmount] in iDNA, comes from the tipper's balance in the contract. */
    val fromBalance: Boolean = false,
) {
    /**
     * The tip in iDNA when the contract accepts it (sendTip): it reads [tipAmount] up to its first non-digit
     * ("6.9" pays 6, "0.5" pays 0), a whole number of at least 1 and no more than was sent; null otherwise
     * (such a call fails); what was sent beyond the tip stays in the contract. Before v11 ([sent]) the tip is
     * what was sent, if anything; from the tips balance ([fromBalance]), its tipAmount, if anything (the
     * balance it needs is not known here).
     */
    val validAmount: BigDecimal?
        get() {
            if (sent || fromBalance) return (if (fromBalance) tipAmount else amount).toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            val tip = LEADING_IDNA.find(tipAmount)?.value?.toBigDecimal()?.takeIf { it.signum() > 0 } ?: return null
            val paid = amount.toBigDecimalOrNull() ?: return null
            return tip.takeIf { paid >= it }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("from", from)
        .put("postId", postId).put("tipAmount", tipAmount).put("amount", amount).put("version", version)
        .put("sent", sent).put("fromBalance", fromBalance)

    companion object {
        /** The digits the contract reads from a sendTip tipAmount: those before the first non-digit. */
        private val LEADING_IDNA = Regex("^[0-9]{1,15}(?![0-9])")
        private val DIGITS = Regex("^[0-9]{1,40}$")

        fun fromJson(json: JSONObject) = TipCall(
            hash = json.getString("hash"),
            height = json.getLong("height"),
            time = json.getLong("time"),
            index = json.getInt("index"),
            from = json.getString("from"),
            postId = json.optString("postId"),
            tipAmount = json.optString("tipAmount"),
            amount = json.optString("amount"),
            version = json.optString("version"),
            sent = json.optBoolean("sent"),
            fromBalance = json.optBoolean("fromBalance"),
        )

        /**
         * The tip of a [method] call (sendTip, or sendTipFromBalance in v1 and v5) of contract [version], from its
         * argument; null if not in the version's format. Since v11 the argument is {"postId", "tipAmount"}, the tip
         * in whole iDNA; v9 and v10 take {"postId"}, v1 and v5 the post id as plain text. sendTipFromBalance takes
         * {"postId", "tipAmount"} in the version's [BalanceTips] unit; its tipAmount is kept in iDNA.
         */
        fun parse(
            hash: String, height: Long, time: Long, index: Int, from: String, amount: String, argHex: String,
            method: String = "sendTip", version: SocialVersion = CURRENT_VERSION,
        ): TipCall? {
            val text = runCatching { String(hexToBytes(argHex), Charsets.UTF_8) }.getOrNull() ?: return null
            val fromBalance = method == "sendTipFromBalance"
            val postId: String
            var tipAmount = ""
            if (fromBalance) {
                val units = version.balanceTips ?: return null
                val args = runCatching { JSONObject(text) }.getOrNull() ?: return null
                postId = args.optString("postId")
                tipAmount = args.optString("tipAmount")
                if (units == BalanceTips.Atomic) {
                    tipAmount = tipAmount.takeIf { DIGITS.matches(it) }?.let { BigDecimal(it).movePointLeft(18).stripTrailingZeros().toPlainString() }
                        ?: return null
                }
            } else if (version.format == CallFormat.V1) {
                postId = text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
            } else {
                val args = runCatching { JSONObject(text) }.getOrNull() ?: return null
                postId = args.optString("postId")
                tipAmount = args.optString("tipAmount")
            }
            return TipCall(
                hash, height, time, index, from.lowercase(), postId, tipAmount, amount,
                version = if (version.isCurrent) "" else version.name,
                sent = !fromBalance && version.format != CallFormat.V12,
                fromBalance = fromBalance,
            )
        }
    }
}

/**
 * A tip amount in iDNA as idena.social shows it (getDisplayTipAmount): at most 3 decimals, "0.000" for a tip
 * below that.
 */
fun tipText(amount: BigDecimal): String {
    val rounded = amount.setScale(3, java.math.RoundingMode.HALF_UP)
    if (rounded.signum() == 0) return if (amount.signum() > 0) "0.000" else "0"
    return rounded.stripTrailingZeros().toPlainString()
}

/**
 * The posts, tips and private messages among contract calls (bcn_contractCalls) of contract [version] (messages:
 * the current contract only). A call the node marks as refused by the contract (success false) is left out: it has
 * no post id and moved no coins.
 */
fun callsToActivity(calls: JSONArray, version: SocialVersion = CURRENT_VERSION): BlockCalls {
    val posts = ArrayList<PostCall>()
    val tips = ArrayList<TipCall>()
    val messages = ArrayList<MessageCall>()
    val keys = HashMap<String, String>()
    for (i in 0 until calls.length()) {
        val call = calls.getJSONObject(i)
        // The sender's public key (idena-go returns it since #38), refused calls included: it signed them.
        publicKeyHex(call.optString("senderPubKey"))?.let { keys[call.getString("from").lowercase()] = it }
        if (!call.optBoolean("success", true)) continue
        val arg = call.optJSONArray("args")?.optString(0) ?: continue
        val hash = call.getString("hash")
        val height = call.getLong("height")
        val time = call.getLong("timestamp")
        val index = call.getInt("index")
        val from = call.getString("from")
        when (val method = call.getString("method")) {
            "makePost" -> PostCall.parse(hash, height, time, index, from, arg, version)?.let { posts += it }
            "sendTip", "sendTipFromBalance" ->
                TipCall.parse(hash, height, time, index, from, call.optString("amount"), arg, method, version)?.let { tips += it }
            "sendMessage" -> if (version.isCurrent) MessageCall.parse(hash, height, time, index, from, arg)?.let { messages += it }
        }
    }
    return BlockCalls(posts, tips, messages, keys)
}

/** The idena.social calls of some blocks, and their senders' public keys (lowercase address -> hex key). */
data class BlockCalls(
    val posts: List<PostCall>,
    val tips: List<TipCall>,
    val messages: List<MessageCall> = emptyList(),
    val keys: Map<String, String> = emptyMap(),
)
