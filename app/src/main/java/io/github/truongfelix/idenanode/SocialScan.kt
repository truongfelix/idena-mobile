package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/**
 * The continuation token of a contract_iterateMap page, or null after the last page, where the node
 * returns "0x" (an empty token, which would start over from the first page).
 */
fun nextPageToken(token: Any?): String? = (token as? String)?.takeUnless { it.isEmpty() || it == "0x" }

/**
 * The authors of the posts from the contract map "p:" (contract_iterateMap with hex keys and values):
 * the key is the post id as a little-endian u128, the value the author's address as text.
 */
fun postAuthors(items: JSONArray): Map<Long, String> {
    val authors = HashMap<Long, String>()
    for (i in 0 until items.length()) {
        val item = items.getJSONObject(i)
        val key = hexToBytes(item.getString("key"))
        // Ids stay far below 2^63: the upper bytes are zero.
        var id = 0L
        for (b in key.take(8).asReversed()) id = (id shl 8) or (b.toLong() and 0xff)
        authors[id] = String(hexToBytes(item.getString("value")), Charsets.UTF_8).lowercase()
    }
    return authors
}

/**
 * What the phone has scanned, kept in the app's files so that no block is scanned twice: the blocks
 * [low]..[high] (none while [high] < [low]), the posts and tips found there (those of an older contract
 * version carry its name), the current contract's post authors as of block [authorsHeight], and each older
 * version's authors (they no longer change), by version name.
 */
data class SocialCache(
    val low: Long,
    val high: Long,
    val posts: List<PostCall>,
    val authors: Map<Long, String>,
    val authorsHeight: Long,
    val tips: List<TipCall> = emptyList(),
    val olderAuthors: Map<String, Map<Long, String>> = emptyMap(),
    /** The private messages (sendMessage calls) read, still encrypted. */
    val messages: List<MessageCall> = emptyList(),
    /**
     * The first block from which the messages were read: a scan made before the app read them (version 4) has them
     * from the blocks scanned since, and reads the current contract's earlier blocks again for them ([scanOlderMessages]).
     */
    val messagesLow: Long = low,
    /** The public keys of the callers seen (lowercase address -> hex key), to encrypt a message for them. */
    val keys: Map<String, String> = emptyMap(),
) {
    /** Whether the current contract has blocks scanned before the app read messages, still to read for them. */
    val messagesPending: Boolean
        get() = messagesLow > maxOf(low, CURRENT_VERSION.from)

    /** Whether the scan reached the first block of idena.social's history. */
    val historyDone: Boolean
        get() = low <= HISTORY_START

    /** The share of the history's blocks scanned, 0 to 1. */
    val scannedShare: Double
        get() = if (high < HISTORY_START) 0.0
        else (high - maxOf(low, HISTORY_START) + 1).coerceAtLeast(0).toDouble() / (high - HISTORY_START + 1)

    /** The oldest block time among the scanned posts; null without posts. */
    val oldestPostTime: Long?
        get() = posts.minOfOrNull { it.time }

    /**
     * The feed of the posts and tips the author maps cover: the current contract's, then each older version's
     * once the scan read all its blocks (its ids count from its first post), with the app's ids for them
     * ([withAppIds]). The versions follow each other in time, so the feed stays newest first.
     */
    fun feed(): List<SocialPost> {
        val current = socialFeed(
            posts.filter { !it.isOlder && it.height <= authorsHeight }, authors, tips.filter { it.version.isEmpty() && it.height <= authorsHeight },
        )
        val older = OLDER_VERSIONS.flatMap { version ->
            val versionAuthors = olderAuthors[version.name]
            if (versionAuthors == null || low > version.from) return@flatMap emptyList()
            version.withAppIds(
                socialFeed(posts.filter { it.version == version.name }, versionAuthors, tips.filter { it.version == version.name }, fromOldest = true),
            )
        }
        return current + older
    }

    /** This cache with the calls of more blocks. */
    fun plus(calls: BlockCalls) =
        copy(posts = posts + calls.posts, tips = tips + calls.tips, messages = withMessages(calls.messages), keys = keys + calls.keys)

    /** The blocks where [address] (lowercase) called the contract, the newest first: their calls hold its key. */
    fun callHeightsOf(address: String): List<Long> =
        (posts.filter { it.author == address }.map { it.height } + tips.filter { it.from == address }.map { it.height } +
            messages.filter { it.from == address }.map { it.height }).distinct().sortedDescending()

    /** The messages with [more] added, each once (a backfill may read blocks again). */
    fun withMessages(more: List<MessageCall>): List<MessageCall> {
        if (more.isEmpty()) return messages
        val known = messages.mapTo(HashSet()) { it.hash }
        return (messages + more.filter { it.hash !in known }).sortedWith(compareBy({ it.height }, { it.index }))
    }

    fun toJson(): JSONObject {
        val postArray = JSONArray()
        posts.forEach { postArray.put(it.toJson()) }
        val tipArray = JSONArray()
        tips.forEach { tipArray.put(it.toJson()) }
        val olderObject = JSONObject()
        olderAuthors.forEach { (name, map) -> olderObject.put(name, authorsJson(map)) }
        val messageArray = JSONArray()
        messages.forEach { messageArray.put(it.toJson()) }
        val keyObject = JSONObject()
        keys.forEach { (address, key) -> keyObject.put(address, key) }
        return JSONObject().put("contract", SOCIAL_CONTRACT).put("version", VERSION).put("low", low).put("high", high)
            .put("posts", postArray).put("tips", tipArray).put("authors", authorsJson(authors)).put("authorsHeight", authorsHeight)
            .put("olderAuthors", olderObject).put("messages", messageArray).put("messagesLow", messagesLow).put("keys", keyObject)
    }

    companion object {
        /**
         * 3: the media references are kept too; a scan of an older version is done again. 4: the older contracts
         * too ([olderAuthors]); a scan of version 3 holds the current contract's blocks only and goes on below them.
         * 5: the private messages ([messages], [messagesLow]); a scan of version 3 or 4 has none and reads them again.
         */
        const val VERSION = 5

        private fun authorsJson(authors: Map<Long, String>) = JSONObject().apply { authors.forEach { (id, author) -> put(id.toString(), author) } }

        private fun authorsFrom(json: JSONObject): Map<Long, String> = json.keys().asSequence().associate { it.toLong() to json.getString(it) }

        /**
         * The cache from its JSON; null when it is for another contract or an unknown version, or when its scan
         * started below the history's first block: at the node's first block, before its sync (0.1.73 and earlier,
         * [scanStartWait]), it walks millions of blocks and takes the history as read.
         */
        fun fromJson(json: JSONObject): SocialCache? {
            if (!json.optString("contract").equals(SOCIAL_CONTRACT, ignoreCase = true)) return null
            if (json.optInt("version", 1) !in 3..VERSION) return null
            if (json.getLong("low") < HISTORY_START) return null
            val postArray = json.getJSONArray("posts")
            val tipArray = json.optJSONArray("tips") ?: JSONArray()
            val olderObject = json.optJSONObject("olderAuthors") ?: JSONObject()
            val messageArray = json.optJSONArray("messages") ?: JSONArray()
            val high = json.getLong("high")
            return SocialCache(
                low = json.getLong("low"),
                high = high,
                posts = (0 until postArray.length()).map { PostCall.fromJson(postArray.getJSONObject(it)) },
                authors = authorsFrom(json.getJSONObject("authors")),
                authorsHeight = json.getLong("authorsHeight"),
                tips = (0 until tipArray.length()).map { TipCall.fromJson(tipArray.getJSONObject(it)) },
                olderAuthors = olderObject.keys().asSequence().associateWith { authorsFrom(olderObject.getJSONObject(it)) },
                messages = (0 until messageArray.length()).map { MessageCall.fromJson(messageArray.getJSONObject(it)) },
                // Before version 5, no block was read for messages.
                messagesLow = if (json.has("messagesLow")) json.getLong("messagesLow") else high + 1,
                keys = json.optJSONObject("keys")?.let { k -> k.keys().asSequence().associateWith { k.getString(it) } }.orEmpty(),
            )
        }
    }
}

/** The node as the app reads it: its peers, its sync, whether a validation runs, and whether the phone's clock is wrong. */
data class NodeView(
    val peers: Int,
    val syncing: Boolean,
    val currentBlock: Long,
    val highestBlock: Long,
    val validation: Boolean,
    val wrongTime: Boolean = false,
    /** The first block of the current epoch; null when not known. */
    val epochStart: Long? = null,
    /** The chain's height (bcn_lastBlock), below [currentBlock] while a fast sync holds headers; null when not read. */
    val chainBlock: Long? = null,
    /** When the chain's head block was made (seconds); null when not read. */
    val headTime: Long? = null,
)

/** The step of [this] node's sync ([syncStep]); [chainMoving]: whether its chain grew lately. */
fun NodeView.syncStep(chainMoving: Boolean): SyncStep =
    syncStep(chainBlock ?: currentBlock, currentBlock, highestBlock, chainMoving)

/** Blocks the scan may lag the node's head and still be up to date: a block comes about every 20 s. */
const val UP_TO_DATE_LAG = 3

/** The oldest the node's head block may be for the first scan to start at it: a synced node's is about 20 s old. */
const val MAX_START_BLOCK_AGE_SECONDS = 3_600L

/**
 * Why the first scan waits rather than start at the node's head block [height], made at [time], when the phone's
 * clock reads [now] (both in seconds); null when it can start. A new node can report itself synced for a moment
 * while still at its first block (4,871,137 on mainnet, from 2022): a scan started there walks millions of blocks
 * before reaching the posts, and takes the history as read ([SocialCache.historyDone]).
 */
fun scanStartWait(height: Long, time: Long, now: Long): String? {
    val age = now - time
    return when {
        height < SOCIAL_FIRST_BLOCK -> t(R.string.social_wait_before_contract, blockNumber.format(height))
        age > MAX_START_BLOCK_AGE_SECONDS -> t(R.string.social_wait_old_head, ageText(age))
        else -> null
    }
}

private fun ageText(seconds: Long): String = when {
    seconds >= 2 * 86_400 -> t(R.string.social_age_days, seconds / 86_400)
    seconds >= 2 * 3_600 -> t(R.string.social_age_hours, seconds / 3_600)
    else -> t(R.string.social_age_minutes, seconds / 60)
}

/**
 * The status of the Social tab from the node ([node] null when it does not answer) and the scan: down
 * without the node or its peers; off while the scan is off or a validation runs; behind while the node
 * syncs, the posts are more than [UP_TO_DATE_LAG] blocks behind its head, or the history is not read yet.
 */
fun scanState(node: NodeView?, scanning: Boolean, head: Long?, cache: SocialCache?): BarState {
    if (node == null) return BarState(Health.Bad, t(R.string.social_bar_no_answer))
    if (node.peers == 0) return BarState(Health.Bad, t(R.string.social_bar_no_peers))
    if (node.validation) return BarState(Health.Off, t(R.string.social_bar_validation))
    if (node.syncing) return BarState(Health.Warn, t(R.string.social_bar_node_syncing))
    if (cache == null) return if (scanning) BarState(Health.Warn, t(R.string.social_bar_starting)) else BarState(Health.Off, t(R.string.social_bar_scan_off))
    if (!scanning) return BarState(Health.Off, t(R.string.social_bar_scan_off))
    val behind = (head ?: cache.authorsHeight) - cache.authorsHeight
    if (behind > UP_TO_DATE_LAG) {
        return BarState(Health.Warn, tp(R.plurals.social_bar_blocks_behind, behind.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), blockNumber.format(behind)))
    }
    if (!cache.historyDone) return BarState(Health.Warn, t(R.string.social_bar_reading_older, (cache.scannedShare * 100).toInt()))
    return BarState(Health.Good, t(R.string.social_bar_up_to_date))
}

/** The Social status in the title row: [state]'s label, then the node's peers while it has some. */
fun socialBarLabel(state: BarState, peers: Int?): String =
    if (peers == null || peers <= 0) state.label else tp(R.plurals.social_bar_peers, peers, state.label, peers)

/** What the scan needs from the node. */
interface SocialSource {
    /** The most blocks [calls] takes at once. */
    fun batchSize(): Long

    /** The node's head block. */
    fun head(): Long

    /** The posts and tips of contract [version] in blocks [from]..[to]; throws if a block is missing. */
    fun calls(from: Long, to: Long, version: SocialVersion = CURRENT_VERSION): BlockCalls

    /** The post authors (contract map "p:") of contract [version] in the node's current state. */
    fun authors(version: SocialVersion = CURRENT_VERSION): Map<Long, String>
}

/**
 * Scans the blocks after the cache up to the node's head, in batches saved as they complete, then reads
 * the post authors at that head. The authors are only taken when the head did not move meanwhile, so
 * that they match the scanned blocks; otherwise it scans the new blocks again, up to 3 times. Without a
 * cache, the scan starts at the head: the older blocks are the history ([scanOlderBlocks]). It stops
 * before a batch when [keepGoing] turns false, keeping the batches done and the previous authors.
 */
fun checkNewBlocks(
    cache: SocialCache?,
    source: SocialSource,
    keepGoing: () -> Boolean = { true },
    save: (SocialCache) -> Unit,
): SocialCache {
    var current = cache
    repeat(3) {
        val head = source.head()
        var next = current ?: SocialCache(low = head + 1, high = head, posts = emptyList(), authors = emptyMap(), authorsHeight = 0)
        while (next.high < head) {
            if (!keepGoing()) return next
            val to = minOf(next.high + source.batchSize(), head)
            next = next.plus(source.calls(next.high + 1, to)).copy(high = to)
            save(next)
        }
        val authors = source.authors()
        if (source.head() == head) {
            next = next.copy(authors = authors, authorsHeight = head)
            save(next)
            return next
        }
        current = next
    }
    return current!!
}

/**
 * Scans one batch of blocks before the cache, down to the first block of the history, with the contract version
 * of those blocks: a batch never spans two versions. An older version's authors are read once, with its first
 * batch.
 */
fun scanOlderBlocks(cache: SocialCache, source: SocialSource, save: (SocialCache) -> Unit): SocialCache {
    if (cache.historyDone) return cache
    val version = versionAt(cache.low - 1) ?: return cache
    val from = maxOf(cache.low - source.batchSize(), version.from)
    val older = source.calls(from, cache.low - 1, version)
    var olderAuthors = cache.olderAuthors
    if (!version.isCurrent && version.name !in olderAuthors) olderAuthors = olderAuthors + (version.name to source.authors(version))
    // The messages of these blocks, when the ones above are read (else the messages' own pass reads them).
    val withMessages = cache.messagesLow <= cache.low
    val next = cache.copy(
        low = from, posts = older.posts + cache.posts, tips = older.tips + cache.tips, olderAuthors = olderAuthors,
        messages = if (withMessages) cache.withMessages(older.messages) else cache.messages,
        messagesLow = if (withMessages) from else cache.messagesLow,
        keys = older.keys + cache.keys,
    )
    save(next)
    return next
}

/**
 * Reads one batch of the current contract's blocks scanned before the app read messages, for their messages only
 * ([SocialCache.messagesPending]), from the newest down.
 */
fun scanOlderMessages(cache: SocialCache, source: SocialSource, save: (SocialCache) -> Unit): SocialCache {
    if (!cache.messagesPending) return cache
    val bottom = maxOf(cache.low, CURRENT_VERSION.from)
    val from = maxOf(cache.messagesLow - source.batchSize(), bottom)
    val read = source.calls(from, cache.messagesLow - 1)
    val next = cache.copy(messages = cache.withMessages(read.messages), messagesLow = from, keys = read.keys + cache.keys)
    save(next)
    return next
}
