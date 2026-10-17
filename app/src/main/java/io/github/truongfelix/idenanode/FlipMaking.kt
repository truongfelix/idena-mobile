package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.random.Random

/*
 * Making a flip, as idena-desktop does (renderer/screens/flips/utils.js, machines.js): four frames telling
 * a story with the two words of a keyword pair, one of them a nonsense "anti-AI" frame made from other
 * pictures, and a second order of the same frames that breaks the story. The node encrypts the flip,
 * keeps it on IPFS and sends the transaction (flip_submit). The rules are the node's
 * (blockchain/validation validateSubmitFlipTx, core/state GetMaximumAvailableFlips): no flip once the flip
 * lottery started, at most the available flips; an identity that misses its required flips is killed
 * (a Verified or Human one suspended) at the next validation.
 */

/** The frames of a flip. */
const val FLIP_FRAMES = 4

/** The size of a flip frame as the apps send it, and its JPEG quality (desktop: 240 x 180, 0.6). */
const val FLIP_WIDTH = 240
const val FLIP_HEIGHT = 180
const val FLIP_JPEG_QUALITY = 60

/** The node takes a flip up to 2 MB of hex (desktop publishFlip). */
const val MAX_FLIP_HEX = 2 * 1024 * 1024

/** A keyword pair of the identity this epoch (dna_identity flipKeyWordPairs): two word numbers, used by a flip already or not. */
data class KeywordPair(val id: Int, val words: List<Int>, val used: Boolean)

/** The keyword pairs of [identity] (dna_identity). */
fun keywordPairs(identity: JSONObject): List<KeywordPair> {
    val array = identity.optJSONArray("flipKeyWordPairs") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val pair = array.optJSONObject(i) ?: return@mapNotNull null
        val words = pair.optJSONArray("words") ?: return@mapNotNull null
        KeywordPair(pair.optInt("id"), (0 until words.length()).map { words.getInt(it) }, pair.optBoolean("used"))
    }
}

/** The words of [pair] from the node's keyword list ([all], assets/keywords.json); unknown numbers are left out. */
fun pairWords(pair: KeywordPair, all: List<Keyword>): List<Keyword> = pair.words.mapNotNull { all.getOrNull(it) }

/**
 * The flips of an identity this epoch: [required] to stay validated, at most [available], [made] so far,
 * in identity [state].
 */
data class FlipQuota(val state: String, val required: Int, val available: Int, val made: Int) {
    /** Whether the identity may submit a flip now (the node checks again): Candidate or better, with flips left. */
    val canMake: Boolean
        get() = made < available && state !in NO_FLIP_STATES

    /** The required flips not made yet. */
    val missing: Int
        get() = (required - made).coerceAtLeast(0)

    /** What happens at the next validation when [missing] flips are still missing. */
    val missingOutcome: String
        get() = if (state == "Verified" || state == "Human") "Suspended" else "Killed"

    /** [missingOutcome] as the screens say it. */
    val missingOutcomeLabel: String
        get() = if (missingOutcome == "Suspended") t(R.string.flip_outcome_suspended) else t(R.string.flip_outcome_killed)

    companion object {
        private val NO_FLIP_STATES = setOf("", "Undefined", "Invite", "Killed")
    }
}

/**
 * The last time a flip can be sent: the flip lottery's start, [lotterySeconds] (dna_ceremonyIntervals
 * FlipLotteryDuration) before the validation at [validation].
 */
fun flipDeadline(validation: Instant, lotterySeconds: Double): Instant = validation.minusMillis((lotterySeconds * 1000).toLong())

/** A random position for the anti-AI frame: any of the four (desktop: the last one unless moved). */
fun randomAdversarialSlot(random: Random = Random.Default): Int = random.nextInt(FLIP_FRAMES)

/**
 * Whether [shuffled] (story positions in the order shown) breaks the story: a permutation of the four
 * positions, not the story itself ([0, 1, 2, 3]), and not the story again once the anti-AI frame
 * ([adversarial], a story position) is set aside (desktop areEqual / areEqualExceptOne).
 */
fun isValidShuffle(shuffled: List<Int>, adversarial: Int?): Boolean {
    val story = (0 until FLIP_FRAMES).toList()
    if (shuffled.sorted() != story) return false
    if (shuffled == story) return false
    if (adversarial != null && shuffled.filter { it != adversarial } == story.filter { it != adversarial }) return false
    return true
}

/** A random order that breaks the story ([isValidShuffle]). */
fun randomShuffle(adversarial: Int?, random: Random = Random.Default): List<Int> {
    while (true) {
        val order = (0 until FLIP_FRAMES).shuffled(random)
        if (isValidShuffle(order, adversarial)) return order
    }
}

/** A flip as flip_submit takes it: its public and private parts, as hex. */
data class FlipHex(val publicHex: String, val privateHex: String) {
    val size: Int
        get() = publicHex.length + privateHex.length
}

/**
 * The flip of [frames] (JPEG, in story order) and the order [shuffled] (story positions), encoded as the
 * apps do (desktop flipToHex): the frames in a random order, the first two in the public part and the
 * last two in the private part with the two orders, each order listing frame indexes; which order
 * comes first is random too.
 */
fun encodeFlip(frames: List<ByteArray>, shuffled: List<Int>, random: Random = Random.Default): FlipHex {
    require(frames.size == FLIP_FRAMES) { "a flip has $FLIP_FRAMES frames" }
    require(shuffled.sorted() == (0 until FLIP_FRAMES).toList()) { "not an order of the frames" }
    // seed[i]: the story position of the frame sent at index i; at[p]: the index of story position p.
    val seed = (0 until FLIP_FRAMES).shuffled(random)
    val sent = seed.map { frames[it] }
    val at = IntArray(FLIP_FRAMES).also { at -> seed.forEachIndexed { index, position -> at[position] = index } }
    val story = (0 until FLIP_FRAMES).map { at[it] }
    val broken = shuffled.map { at[it] }
    val orders = if (random.nextBoolean()) listOf(story, broken) else listOf(broken, story)
    fun images(list: List<ByteArray>) = Rlp.encodeList(list.map { Rlp.encodeBytes(it) })
    val public = Rlp.encodeList(listOf(images(sent.subList(0, 2))))
    val private = Rlp.encodeList(
        listOf(images(sent.subList(2, 4)), Rlp.encodeList(orders.map { order -> Rlp.encodeList(order.map { Rlp.encodeInt(it) }) })),
    )
    return FlipHex(bytesToHex(public), bytesToHex(private))
}

/** The argument of flip_submit. */
fun flipSubmitArgs(flip: FlipHex, pairId: Int): JSONArray =
    JSONArray().put(JSONObject().put("publicHex", flip.publicHex).put("privateHex", flip.privateHex).put("pairId", pairId))

/** What the node's refusal of a flip means for the user (desktop publishFlip). */
fun flipRefusal(message: String): String = when {
    "candidate" in message.lowercase() -> t(R.string.flip_refusal_state)
    "ceremony" in message.lowercase() || "late" in message.lowercase() -> t(R.string.flip_refusal_lottery)
    else -> message
}

/*
 * DuckDuckGo's part of the picture search ([PictureSearch]), as idena-desktop's main/image-search.js asks it, but
 * thumbnails only and always through DuckDuckGo's own image proxy.
 */

/** The page that gives the search token. */
fun duckLandingUrl(query: String): String =
    "https://duckduckgo.com/?q=${urlEncode(query)}&iax=images&ia=images"

/** The search token (vqd) in DuckDuckGo's page; null when it has none. */
fun duckToken(html: String): String? {
    val patterns = listOf(Regex("vqd=[\"']([^\"']+)[\"']"), Regex("vqd=([^&\"'\\\\]+)&"), Regex("\"vqd\":\"([^\"]+)\""))
    for (pattern in patterns) {
        val token = pattern.find(html)?.groupValues?.get(1)?.trim() ?: continue
        if (token.length <= 128 && Regex("^[A-Za-z0-9._-]+$").matches(token)) return token
    }
    return null
}

/** The image results for [query] with [token]. */
fun duckResultsUrl(query: String, token: String): String =
    "https://duckduckgo.com/i.js?l=us-en&o=json&q=${urlEncode(query)}&vqd=${urlEncode(token)}&f=,,,&p=1"

/** The thumbnails of DuckDuckGo's results ([json], i.js), at most [limit], each through DuckDuckGo's image proxy. */
fun duckThumbnails(json: String, limit: Int = 30): List<String> {
    val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
    val seen = LinkedHashSet<String>()
    for (i in 0 until results.length()) {
        val thumbnail = results.optJSONObject(i)?.optString("thumbnail").orEmpty()
        if (thumbnail.startsWith("https://") && thumbnail.length <= 2048) seen += duckProxy(thumbnail)
        if (seen.size >= limit) break
    }
    return seen.toList()
}

/** [url] as DuckDuckGo's image proxy serves it. */
fun duckProxy(url: String): String = "https://external-content.duckduckgo.com/iu/?u=${urlEncode(url)}&f=1"

private fun urlEncode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

/*
 * After a flip is sent, the node counts it (madeFlips, the pair "used") only once its transaction is in a
 * block, and checks a new flip only against the flips in blocks (validateSubmitFlipTx). Until then the
 * app keeps the flip sent ([SentFlip], files/flips/sent.json) and counts it itself, so that the same pair
 * is not used twice and no more flips are sent than allowed.
 */

/** A flip sent and maybe not in a block yet: its transaction, its hash (CID), its keyword pair and epoch. */
data class SentFlip(val txHash: String, val hash: String, val pairId: Int, val sentAt: Long, val epoch: Int) {
    fun toJson(): JSONObject =
        JSONObject().put("txHash", txHash).put("hash", hash).put("pairId", pairId).put("sentAt", sentAt).put("epoch", epoch)

    companion object {
        /** A record without an epoch (app 0.1.31) reads as epoch -1: it is from no current epoch. */
        fun fromJson(json: JSONObject): SentFlip? = runCatching {
            SentFlip(json.getString("txHash"), json.optString("hash"), json.getInt("pairId"), json.getLong("sentAt"), json.optInt("epoch", -1))
        }.getOrNull()
    }
}

/** Whether the node counts [sent] already: its pair is used in [pairs] (dna_identity, from the blocks). */
fun isCounted(sent: SentFlip, pairs: List<KeywordPair>): Boolean = pairs.any { it.id == sent.pairId && it.used }

/** What becomes of the flip sent, at each check. */
enum class SentFlipCheck { Wait, Counted, OtherEpoch, TimedOut }

/**
 * The flip sent is [SentFlipCheck.Counted] once its pair is used; from another epoch (pair ids start
 * again each epoch, so its pair says nothing now) it is forgotten; and after [SENT_FLIP_TIMEOUT_MS]
 * without being counted, too, whether the node still knows its transaction or not: the record must
 * never block flip making for good.
 */
fun checkSent(sent: SentFlip, epoch: Int, pairs: List<KeywordPair>, now: Long): SentFlipCheck = when {
    sent.epoch != epoch -> SentFlipCheck.OtherEpoch
    isCounted(sent, pairs) -> SentFlipCheck.Counted
    now - sent.sentAt > SENT_FLIP_TIMEOUT_MS -> SentFlipCheck.TimedOut
    else -> SentFlipCheck.Wait
}

/**
 * The quota and pairs as they will be once [sent] is in a block: one more flip made and its pair used,
 * when the node does not count it yet.
 */
fun countSent(quota: FlipQuota, pairs: List<KeywordPair>, sent: SentFlip?, epoch: Int): Pair<FlipQuota, List<KeywordPair>> {
    if (sent == null || sent.epoch != epoch || isCounted(sent, pairs)) return quota to pairs
    return quota.copy(made = quota.made + 1) to pairs.map { if (it.id == sent.pairId) it.copy(used = true) else it }
}

/** How long a sent flip may stay uncounted before the app forgets it (the node's mempool drops it by then). */
const val SENT_FLIP_TIMEOUT_MS = 30 * 60 * 1000L

/**
 * What identifies a draft's pictures: its frames (bytes) and which one is the anti-AI frame. Pictures
 * protected for one fingerprint are not sent for another.
 */
fun draftFingerprint(frames: List<ByteArray?>, adversarial: Int): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    frames.forEach { frame ->
        digest.update(if (frame == null) byteArrayOf(0) else byteArrayOf(1))
        frame?.let { digest.update(java.nio.ByteBuffer.allocate(4).putInt(it.size).array()); digest.update(it) }
    }
    digest.update(adversarial.toByte())
    return bytesToHex(digest.digest())
}

/**
 * The order of the draft writes: the screen saves each change in the background, so a write may come
 * late. A write is done only when it is the newest so far ([next] gives its ticket) and was asked after
 * the last [clear] (a flip sent), so that a late write never brings back an older or a sent draft.
 */
class DraftWriteOrder {
    data class Ticket(val generation: Int, val sequence: Long)

    private var generation = 0
    private var sequence = 0L
    private var written = 0L

    @Synchronized
    fun next(): Ticket = Ticket(generation, ++sequence)

    /** Whether the write of [ticket] may happen now; true marks it as the newest written. */
    @Synchronized
    fun claim(ticket: Ticket): Boolean {
        if (ticket.generation != generation || ticket.sequence <= written) return false
        written = ticket.sequence
        return true
    }

    /** Every write asked before this is dropped. */
    @Synchronized
    fun clear() {
        generation++
    }
}
