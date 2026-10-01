package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/*
 * Idena's ads, as the desktop app makes and shows them: an identity lists its ads in its profile on IPFS,
 * each with the oracle vote that reviewed it, and burns coins with a key naming the ad and its audience
 * to show it. The node keeps the burns of the last 4320 blocks, about a day (bcn_burntCoins). An ad shows
 * when its review vote approved it and its audience includes this identity, the most burnt first, the
 * coins weighted by how narrow the audience is. Here the vote must also name the ad, so that an approval
 * cannot be reused for another ad. Formats: idena-desktop's renderer/shared/models/proto/models.proto.
 */

/** The ads shown at most, as in the desktop app. */
const val MAX_ADS = 3

/** Who an ad is for: empty or 0 means anyone. */
data class AdTarget(val language: String = "", val age: Int = 0, val os: String = "", val stake: Int = 0)

/** Who sees the ads: the phone's language, the OS, and the identity's age and stake. */
data class AdViewer(val language: String, val os: String, val age: Int, val stake: BigDecimal)

/** A burn for an ad: the ad's IPFS cid and its audience, from bcn_burntCoins. */
data class AdBurn(val address: String, val amount: String, val cid: String, val target: AdTarget)

/** An ad in its author's profile: its cid and the contract of its review vote. */
data class ProfileAd(val cid: String, val contract: String)

/** An ad's content (ProtoAd); [thumb] and [media] are images, null when missing. */
class AdContent(val title: String, val desc: String, val url: String, val thumb: ByteArray?, val media: ByteArray?)

/** An approved ad to show: its content, who paid for it, and the iDNA burnt for it over the last day. */
class ChainAd(val cid: String, val author: String, val burnt: String, val content: AdContent)

/** Whether [target] includes [viewer]: same language and OS, at least its age and stake (desktop's isTargetedAd). */
fun isTargeted(target: AdTarget, viewer: AdViewer): Boolean =
    (target.language.isEmpty() || target.language.equals(viewer.language, ignoreCase = true)) &&
        (target.os.isEmpty() || target.os.equals(viewer.os, ignoreCase = true)) &&
        (target.age == 0 || viewer.age >= target.age) &&
        (target.stake == 0 || viewer.stake >= BigDecimal(target.stake))

/** The rank of a burn: the iDNA burnt, times 22 for a set language and 5 for a set OS (desktop's calculateTotalAdScore). */
fun adScore(burn: AdBurn): BigDecimal {
    var weight = 1
    if (burn.target.language.isNotEmpty()) weight *= 22
    if (burn.target.os.isNotEmpty()) weight *= 5
    return (burn.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO) * BigDecimal(weight)
}

/**
 * The burns of bcn_burntCoins whose audience includes [viewer], best first, one per cid and author (its
 * best key): the ads to check, in the order to show them.
 */
fun rankedBurns(burnt: JSONArray, viewer: AdViewer): List<AdBurn> {
    val burns = (0 until burnt.length()).mapNotNull { i ->
        val item = burnt.optJSONObject(i) ?: return@mapNotNull null
        val key = decodeBurnKey(item.optString("key")) ?: return@mapNotNull null
        AdBurn(item.optString("address"), item.optString("amount"), key.first, key.second)
    }
    return burns.filter { isTargeted(it.target, viewer) }
        .sortedByDescending { adScore(it) }
        .distinctBy { it.address.lowercase() to it.cid }
}

/** A burn key (ProtoAdBurnKey, hex): the ad's cid and its audience; null when it is not an ad's. */
fun decodeBurnKey(hex: String): Pair<String, AdTarget>? = runCatching {
    val key = Proto(hexToBytes(hex))
    val cid = key.string(1)
    if (ipfsCid("ipfs://$cid") == null) return null
    val targetHex = key.string(2)
    cid to if (targetHex.isEmpty()) AdTarget() else decodeTarget(hexToBytes(targetHex))
}.getOrNull()

/** An audience (ProtoAdTarget). */
fun decodeTarget(bytes: ByteArray): AdTarget {
    val target = Proto(bytes)
    return AdTarget(target.string(1), target.long(2).toInt(), target.string(3), target.long(4).toInt())
}

/** The ads of a profile (ProtoProfile); empty when it is not one. */
fun decodeProfileAds(bytes: ByteArray): List<ProfileAd> = runCatching {
    Proto(bytes).all(1).filterIsInstance<ByteArray>().map {
        val ad = Proto(it)
        ProfileAd(ad.string(1), ad.string(3))
    }
}.getOrDefault(emptyList())

/** An ad (ProtoAd); null when it is not one or has no title. */
fun decodeAd(bytes: ByteArray): AdContent? = runCatching {
    val ad = Proto(bytes)
    AdContent(ad.string(1).trim(), ad.string(2).trim(), ad.string(3).trim(), ad.bytes(4), ad.bytes(5))
}.getOrNull()?.takeIf { it.title.isNotEmpty() }

/** The question of the desktop app's review votes. */
const val AD_REVIEW_TITLE = "Is this ad appropriate?"

/** The keys of a review vote read with contract_batchReadData. */
fun adReviewKeys(): JSONArray = JSONArray()
    .put(JSONObject().put("key", "state").put("format", "byte"))
    .put(JSONObject().put("key", "fact").put("format", "hex"))
    .put(JSONObject().put("key", "result").put("format", "byte"))

/** A review vote: whether it is over (then it no longer changes) and whether it approved the ad. */
data class AdReview(val over: Boolean, val approved: Boolean)

/**
 * The review vote of the ad [cid] from contract_batchReadData of [adReviewKeys]: over once archived (state
 * 2) or terminated (no state left), approved when over with the "Approve" option (id 0) winning, on the
 * desktop app's question, for this ad. Null when the contract is no vote.
 */
fun adReview(data: JSONArray, cid: String): AdReview? {
    val byKey = (0 until data.length()).mapNotNull { data.optJSONObject(it) }.associateBy { it.optString("key") }
    val state = byKey["state"] ?: return null
    val fact = byKey["fact"]?.takeIf { !it.has("error") } ?: return null
    val result = byKey["result"]?.takeIf { !it.has("error") } ?: return null
    val over = when {
        state.optString("error") == "data is nil" -> true
        state.has("error") -> return null
        else -> state.optInt("value", -1) == 2
    }
    val question = runCatching { JSONObject(String(hexToBytes(fact.optString("value")), Charsets.UTF_8)) }.getOrNull()
        ?: return null
    val winner = result.optInt("value", -1)
    val options = question.optJSONArray("options") ?: JSONArray()
    val winning = (0 until options.length()).mapNotNull { options.optJSONObject(it) }.firstOrNull { it.optInt("id", -1) == winner }
    val approved = over &&
        question.optString("title") == AD_REVIEW_TITLE &&
        question.optString("adCid") == cid &&
        winner == 0 && winning?.optString("value") == "Approve"
    return AdReview(over, approved)
}

/** [url] when the browser may open it (http or https), else null: ads may also hold dna:// links for the desktop app. */
fun adLink(url: String): String? =
    url.trim().takeIf { Regex("^https?://[^\\s/]+\\S*$", RegexOption.IGNORE_CASE).matches(it) }

/**
 * A protobuf message's fields by number: varints as Long, length-delimited fields as ByteArray (fixed-size
 * fields are skipped). Throws on a malformed message.
 */
class Proto(bytes: ByteArray) {
    private val fields = mutableMapOf<Int, MutableList<Any>>()

    init {
        var i = 0
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(i < bytes.size && shift < 64) { "bad varint" }
                val b = bytes[i++].toInt()
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
        while (i < bytes.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            require(number > 0) { "bad field number" }
            when ((tag and 7).toInt()) {
                0 -> fields.getOrPut(number) { mutableListOf() }.add(varint())
                1 -> { require(bytes.size - i >= 8) { "truncated" }; i += 8 }
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= bytes.size - i) { "truncated" }
                    fields.getOrPut(number) { mutableListOf() }.add(bytes.copyOfRange(i, i + length.toInt()))
                    i += length.toInt()
                }
                5 -> { require(bytes.size - i >= 4) { "truncated" }; i += 4 }
                else -> throw IllegalArgumentException("bad wire type")
            }
        }
    }

    /** Every value of field [number], in order (a repeated field). */
    fun all(number: Int): List<Any> = fields[number].orEmpty()

    fun bytes(number: Int): ByteArray? = all(number).lastOrNull() as? ByteArray

    fun string(number: Int): String = bytes(number)?.toString(Charsets.UTF_8).orEmpty()

    fun long(number: Int): Long = all(number).lastOrNull() as? Long ?: 0L
}
