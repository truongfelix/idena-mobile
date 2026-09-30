package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * idena.social (https://idena.social), read from the node: posts are makePost calls of its contract,
 * whose JSON argument holds the text. The rules follow idena.social-ui (getNewPosterAndPost in
 * src/logic/asyncUtils.ts, PostComponent.tsx), for posts made since its current contract (v12).
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
    val message: String,
    /** The id of the post it replies to, as the contract argument has it; empty for a post. */
    val replyTo: String,
    /** Empty for the main feed; "discuss:<id>" for a comment on a reply. */
    val channel: String,
    val hasMedia: Boolean,
) {
    val isLike: Boolean
        get() = message == LIKE && replyTo.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("author", author)
        .put("message", message).put("replyTo", replyTo).put("channel", channel).put("hasMedia", hasMedia)

    companion object {
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
        )

        /**
         * The post of a makePost call, from its first argument (hex of the JSON text idena.social sends:
         * message, replyToPostId, channelId, media, mediaType); null if the argument is not such JSON.
         */
        fun parse(hash: String, height: Long, time: Long, index: Int, from: String, argHex: String): PostCall? {
            val args = runCatching { JSONObject(String(hexToBytes(argHex), Charsets.UTF_8)) }.getOrNull() ?: return null
            val media = args.optJSONArray("media")
            val mediaType = args.optJSONArray("mediaType")
            return PostCall(
                hash = hash,
                height = height,
                time = time,
                index = index,
                author = from.lowercase(),
                message = args.optString("message"),
                replyTo = args.optString("replyToPostId"),
                channel = args.optString("channelId"),
                hasMedia = media != null && media.length() > 0 && mediaType != null && mediaType.length() > 0,
            )
        }
    }
}

/** A post of the feed with its likes and its replies (oldest first), which are posts too. */
data class SocialPost(val id: Long, val call: PostCall, val likes: Int, val replies: List<SocialPost>)

/**
 * The ids of the posts: the contract numbers the successful makePost calls 1, 2, 3... and keeps each
 * id's author ([authors], contract map "p:"). Going from the newest call to the oldest, a call gets the
 * next id down when its sender is that id's author; a call that does not match failed and has no id.
 * [posts] and [authors] must cover the same blocks: calls after the blocks the map was read at are left
 * out by the caller.
 */
fun postIds(posts: List<PostCall>, authors: Map<Long, String>): Map<String, Long> {
    val ids = HashMap<String, Long>()
    var id = authors.keys.maxOrNull() ?: return ids
    for (post in posts.sortedWith(compareBy({ it.height }, { it.index })).asReversed()) {
        if (id < 1) break
        if (authors[id] == post.author) {
            ids[post.hash] = id
            id--
        }
    }
    return ids
}

/**
 * The feed, newest first: the main-feed posts that have an id, with their replies and likes. As
 * idena.social: a post needs a text or media; a reply is shown under its post only when that post is
 * known, older and not a like; a like only counts. Comments on replies ("discuss:" channels) are not
 * shown here.
 */
fun socialFeed(posts: List<PostCall>, authors: Map<Long, String>): List<SocialPost> {
    val ids = postIds(posts, authors)
    val byId = HashMap<Long, PostCall>()
    for (post in posts) ids[post.hash]?.let { byId[it] = post }
    val children = HashMap<Long, MutableList<Long>>()
    val likes = HashMap<Long, Int>()
    val topLevel = ArrayList<Long>()
    // A post always has a greater id than the post it replies to: in id order, parents come first.
    val accepted = HashSet<Long>()
    for ((id, post) in byId.entries.sortedBy { it.key }) {
        if (post.channel.isNotEmpty()) continue
        if (post.message.isEmpty() && !post.hasMedia) continue
        if (post.replyTo.isEmpty()) {
            accepted += id
            topLevel += id
            continue
        }
        val parentId = post.replyTo.toLongOrNull()?.takeIf { it in accepted } ?: continue
        val parent = byId.getValue(parentId)
        if (post.time <= parent.time || parent.isLike) continue
        accepted += id
        if (post.isLike) likes[parentId] = (likes[parentId] ?: 0) + 1 else children.getOrPut(parentId) { mutableListOf() } += id
    }

    fun build(id: Long): SocialPost =
        SocialPost(id, byId.getValue(id), likes[id] ?: 0, children[id].orEmpty().map { build(it) })

    return topLevel.asReversed().map { build(it) }
}

/** The number of replies under [post], at every depth. */
fun replyCount(post: SocialPost): Int = post.replies.sumOf { 1 + replyCount(it) }

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

/** The blocks read per node request while scanning block by block (about 1.5 s on the phone). */
const val SOCIAL_BATCH = 500L

/**
 * What the phone has scanned, kept in the app's files so that no block is scanned twice: the blocks
 * [low]..[high] (none while [high] < [low]), the posts found there, and the post authors as of block
 * [authorsHeight].
 */
data class SocialCache(
    val low: Long,
    val high: Long,
    val posts: List<PostCall>,
    val authors: Map<Long, String>,
    val authorsHeight: Long,
) {
    /** Whether the scan reached the first block of the contract. */
    val historyDone: Boolean
        get() = low <= SOCIAL_FIRST_BLOCK

    /** The share of the contract's blocks scanned, 0 to 1. */
    val scannedShare: Double
        get() = if (high < SOCIAL_FIRST_BLOCK) 0.0
        else (high - maxOf(low, SOCIAL_FIRST_BLOCK) + 1).coerceAtLeast(0).toDouble() / (high - SOCIAL_FIRST_BLOCK + 1)

    /** The oldest block time among the scanned posts; null without posts. */
    val oldestPostTime: Long?
        get() = posts.minOfOrNull { it.time }

    /** The feed of the posts the author map covers. */
    fun feed(): List<SocialPost> = socialFeed(posts.filter { it.height <= authorsHeight }, authors)

    fun toJson(): JSONObject {
        val postArray = JSONArray()
        posts.forEach { postArray.put(it.toJson()) }
        val authorObject = JSONObject()
        authors.forEach { (id, author) -> authorObject.put(id.toString(), author) }
        return JSONObject().put("contract", SOCIAL_CONTRACT).put("low", low).put("high", high)
            .put("posts", postArray).put("authors", authorObject).put("authorsHeight", authorsHeight)
    }

    companion object {
        /** The cache from its JSON; null when it is for another contract. */
        fun fromJson(json: JSONObject): SocialCache? {
            if (!json.optString("contract").equals(SOCIAL_CONTRACT, ignoreCase = true)) return null
            val postArray = json.getJSONArray("posts")
            val authorObject = json.getJSONObject("authors")
            return SocialCache(
                low = json.getLong("low"),
                high = json.getLong("high"),
                posts = (0 until postArray.length()).map { PostCall.fromJson(postArray.getJSONObject(it)) },
                authors = authorObject.keys().asSequence().associate { it.toLong() to authorObject.getString(it) },
                authorsHeight = json.getLong("authorsHeight"),
            )
        }
    }
}

/** What the scan needs from the node. */
interface SocialSource {
    /** The most blocks [posts] takes at once. */
    fun batchSize(): Long

    /** The node's head block. */
    fun head(): Long

    /** The posts of blocks [from]..[to]; throws if a block is missing. */
    fun posts(from: Long, to: Long): List<PostCall>

    /** The post authors (contract map "p:") in the node's current state. */
    fun authors(): Map<Long, String>
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
            next = next.copy(high = to, posts = next.posts + source.posts(next.high + 1, to))
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

/** Scans one batch of blocks before the cache, down to the contract's first block. */
fun scanOlderBlocks(cache: SocialCache, source: SocialSource, save: (SocialCache) -> Unit): SocialCache {
    if (cache.historyDone) return cache
    val from = maxOf(cache.low - source.batchSize(), SOCIAL_FIRST_BLOCK)
    val next = cache.copy(low = from, posts = source.posts(from, cache.low - 1) + cache.posts)
    save(next)
    return next
}

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

/** The argument of a makePost call with [message] (a post; a reply when [replyTo] is set). */
fun makePostArgument(message: String, replyTo: Long? = null): String =
    if (replyTo == null) "{\"message\":${jsonString(message)}}"
    else "{\"message\":${jsonString(message)},\"replyToPostId\":${jsonString(replyTo.toString())}}"
