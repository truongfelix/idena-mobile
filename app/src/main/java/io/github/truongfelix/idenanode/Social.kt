package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/*
 * idena.social (https://idena.social), read from the node: posts are makePost calls of its contract,
 * whose JSON argument holds the text, and tips are sendTip calls. The rules follow idena.social-ui (getNewPosterAndPost in
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
    /** The text, or "ipfs://<cid>" when idena.social stored it on IPFS. */
    val message: String,
    /** The id of the post it replies to, as the contract argument has it; empty for a post. */
    val replyTo: String,
    /** Empty for the main feed; "discuss:<id>" for a comment on a reply. */
    val channel: String,
    val hasMedia: Boolean,
    /** The first media: "ipfs://<cid>" or inline base64; empty without media or when too large to keep. */
    val media: String = "",
    /** Its type as the post declares it (image/png, video/mp4...). */
    val mediaType: String = "",
) {
    val isLike: Boolean
        get() = message == LIKE && replyTo.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("author", author)
        .put("message", message).put("replyTo", replyTo).put("channel", channel).put("hasMedia", hasMedia)
        .put("media", media).put("mediaType", mediaType)

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
        )

        /**
         * The post of a makePost call, from its first argument (hex of the JSON text idena.social sends:
         * message, replyToPostId, channelId, media, mediaType); null if the argument is not such JSON.
         */
        fun parse(hash: String, height: Long, time: Long, index: Int, from: String, argHex: String): PostCall? {
            val args = runCatching { JSONObject(String(hexToBytes(argHex), Charsets.UTF_8)) }.getOrNull() ?: return null
            val media = args.optJSONArray("media")
            val mediaType = args.optJSONArray("mediaType")
            val hasMedia = media != null && media.length() > 0 && mediaType != null && mediaType.length() > 0
            val first = if (hasMedia) media!!.optString(0) else ""
            return PostCall(
                hash = hash,
                height = height,
                time = time,
                index = index,
                author = from.lowercase(),
                message = args.optString("message"),
                replyTo = args.optString("replyToPostId"),
                channel = args.optString("channelId"),
                hasMedia = hasMedia,
                media = first.takeIf { it.startsWith("ipfs://") || it.length <= MAX_INLINE_MEDIA }.orEmpty(),
                mediaType = if (hasMedia) mediaType!!.optString(0) else "",
            )
        }
    }
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
    /** The tip as the argument has it; the contract takes whole iDNA. */
    val tipAmount: String,
    /** The iDNA sent with the call (decimal). */
    val amount: String,
) {
    /**
     * The tip in iDNA when the contract accepts it (sendTip): a whole number of at least 1 and no more
     * than was sent; null otherwise (such a call fails).
     */
    val validAmount: Long?
        get() {
            val tip = tipAmount.takeIf { WHOLE_IDNA.matches(it) }?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val sent = amount.toBigDecimalOrNull() ?: return null
            return tip.takeIf { sent >= BigDecimal(it) }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("from", from)
        .put("postId", postId).put("tipAmount", tipAmount).put("amount", amount)

    companion object {
        private val WHOLE_IDNA = Regex("^[0-9]{1,15}$")

        fun fromJson(json: JSONObject) = TipCall(
            hash = json.getString("hash"),
            height = json.getLong("height"),
            time = json.getLong("time"),
            index = json.getInt("index"),
            from = json.getString("from"),
            postId = json.optString("postId"),
            tipAmount = json.optString("tipAmount"),
            amount = json.optString("amount"),
        )

        /** The tip of a sendTip call, from its argument (hex of {"postId", "tipAmount"}); null if not such JSON. */
        fun parse(hash: String, height: Long, time: Long, index: Int, from: String, amount: String, argHex: String): TipCall? {
            val args = runCatching { JSONObject(String(hexToBytes(argHex), Charsets.UTF_8)) }.getOrNull() ?: return null
            return TipCall(hash, height, time, index, from.lowercase(), args.optString("postId"), args.optString("tipAmount"), amount)
        }
    }
}

/** The idena.social calls of some blocks. */
data class BlockCalls(val posts: List<PostCall>, val tips: List<TipCall>)

/**
 * A post of the feed with its likes (the like calls), its valid tips, and what answers it, oldest first:
 * a post's replies, or a reply's comments (idena.social's two levels; a comment has none).
 */
data class SocialPost(
    val id: Long,
    val call: PostCall,
    val likeCalls: List<PostCall>,
    val tips: List<TipCall>,
    val replies: List<SocialPost>,
) {
    val likes: Int
        get() = likeCalls.size

    val likedBy: List<String>
        get() = likeCalls.map { it.author }

    /** The iDNA tipped to the post's author for it. */
    val tipTotal: Long
        get() = tips.sumOf { it.validAmount ?: 0 }
}

/** The channel of the comments under reply [replyId] on idena.social. */
fun discussChannel(replyId: Long): String = "discuss:$replyId"

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
 * The feed, newest first, as idena.social shows it (PostComponent.tsx): posts in the main channel;
 * under each post its replies; under each reply its comments, in the channel "discuss:<reply id>"
 * (a main-channel answer to a reply or a comment counts as a comment too). A like (a reply made of
 * [LIKE]) only counts on what it replies to. Each post needs a text or media, an id, and a known,
 * older parent that is not a like; posts in other channels are left out. A tip counts on the post it
 * names when its amount is valid and it comes after that post in the chain.
 */
fun socialFeed(posts: List<PostCall>, authors: Map<Long, String>, tips: List<TipCall> = emptyList()): List<SocialPost> {
    val ids = postIds(posts, authors)
    val byId = HashMap<Long, PostCall>()
    for (post in posts) ids[post.hash]?.let { byId[it] = post }
    val answers = HashMap<Long, MutableList<Long>>()
    val likes = HashMap<Long, MutableList<PostCall>>()
    val topLevel = ArrayList<Long>()
    // The level of each accepted post, and the reply each comment belongs to. A post always has a
    // greater id than the post it answers: in id order, parents come first.
    val levels = HashMap<Long, Int>()
    val replyOf = HashMap<Long, Long>()
    for ((id, post) in byId.entries.sortedBy { it.key }) {
        if (post.message.isEmpty() && !post.hasMedia) continue
        val discussId = when {
            post.channel.isEmpty() -> null
            post.channel.startsWith("discuss:") -> post.channel.removePrefix("discuss:").toLongOrNull() ?: continue
            else -> continue
        }
        val parentId = post.replyTo.toLongOrNull()
        if (post.replyTo.isNotEmpty() && parentId == null) continue
        if (parentId == null && discussId == null) {
            levels[id] = POST
            topLevel += id
            continue
        }
        // What it answers: the post, reply or comment it replies to, else the reply of its channel.
        val targetId = parentId ?: discussId!!
        val targetLevel = levels[targetId] ?: continue
        val target = byId.getValue(targetId)
        if (post.time <= target.time || target.isLike) continue
        if (post.isLike) {
            likes.getOrPut(targetId) { mutableListOf() } += post
            levels[id] = LIKE_LEVEL
            continue
        }
        // Where it is shown: a reply under its post, a comment under its reply.
        val reply = when {
            discussId != null -> discussId.takeIf { levels[it] == REPLY } ?: continue
            targetLevel == POST -> null
            targetLevel == REPLY -> targetId
            targetLevel == COMMENT -> replyOf.getValue(targetId)
            else -> continue
        }
        if (reply == null) {
            levels[id] = REPLY
            answers.getOrPut(targetId) { mutableListOf() } += id
        } else {
            if (post.time <= byId.getValue(reply).time) continue
            levels[id] = COMMENT
            replyOf[id] = reply
            answers.getOrPut(reply) { mutableListOf() } += id
        }
    }

    val tipsOf = HashMap<Long, MutableList<TipCall>>()
    for (tip in tips) {
        val postId = tip.postId.toLongOrNull() ?: continue
        val level = levels[postId] ?: continue
        val post = byId.getValue(postId)
        val after = tip.height > post.height || tip.height == post.height && tip.index > post.index
        if (level != LIKE_LEVEL && after && tip.validAmount != null) tipsOf.getOrPut(postId) { mutableListOf() } += tip
    }

    fun build(id: Long): SocialPost = SocialPost(
        id, byId.getValue(id), likes[id].orEmpty(), tipsOf[id].orEmpty().sortedWith(compareBy({ it.height }, { it.index })),
        answers[id].orEmpty().map { build(it) },
    )

    return topLevel.asReversed().map { build(it) }
}

private const val POST = 0
private const val REPLY = 1
private const val COMMENT = 2
private const val LIKE_LEVEL = 3

/** The number of replies under [post], at every depth. */
fun replyCount(post: SocialPost): Int = post.replies.sumOf { 1 + replyCount(it) }

/** The block of the newest thing in [post]'s thread: the post, a reply, a comment, a like or a tip. */
fun lastActivityHeight(post: SocialPost): Long = maxOf(
    post.call.height,
    post.likeCalls.maxOfOrNull { it.height } ?: 0,
    post.tips.maxOfOrNull { it.height } ?: 0,
    post.replies.maxOfOrNull { lastActivityHeight(it) } ?: 0,
)

/** How the feed is ordered, as idena.social offers it: by the newest posts, or by the latest activity. */
enum class FeedSort { Newest, Activity }

/** [feed] (newest posts first) in [sort]'s order; threads with activity in the same block keep their order. */
fun sortFeed(feed: List<SocialPost>, sort: FeedSort): List<SocialPost> = when (sort) {
    FeedSort.Newest -> feed
    FeedSort.Activity -> feed.sortedByDescending { lastActivityHeight(it) }
}

enum class ActivityKind { Like, Reply, Comment, Tip }

/**
 * Something another identity did on one of [me]'s posts, replies or comments: [what] is that post,
 * [answer] the reply or comment made, [amount] the tip; [threadId] is the thread to open, at [focusId]
 * (the answer, or the post liked or tipped).
 */
data class ActivityItem(
    val kind: ActivityKind,
    val actor: String,
    val height: Long,
    val index: Int,
    val time: Long,
    val threadId: Long,
    val focusId: Long,
    val what: PostCall,
    val answer: PostCall? = null,
    val amount: Long? = null,
)

/**
 * The activity on [me]'s posts, newest first, as idena.social's Post Activity: likes, replies to a
 * post, comments on a reply or answering a comment, and tips, made by others.
 */
fun socialActivity(feed: List<SocialPost>, me: String): List<ActivityItem> {
    val items = ArrayList<ActivityItem>()
    fun likesAndTips(node: SocialPost, threadId: Long) {
        for (like in node.likeCalls) {
            if (like.author != me) items += ActivityItem(ActivityKind.Like, like.author, like.height, like.index, like.time, threadId, node.id, node.call)
        }
        for (tip in node.tips) {
            if (tip.from != me) items += ActivityItem(ActivityKind.Tip, tip.from, tip.height, tip.index, tip.time, threadId, node.id, node.call, amount = tip.validAmount)
        }
    }
    for (post in feed) {
        if (post.call.author == me) {
            likesAndTips(post, post.id)
            for (reply in post.replies) {
                if (reply.call.author != me) {
                    items += ActivityItem(ActivityKind.Reply, reply.call.author, reply.call.height, reply.call.index, reply.call.time, post.id, reply.id, post.call, reply.call)
                }
            }
        }
        for (reply in post.replies) {
            if (reply.call.author == me) {
                likesAndTips(reply, post.id)
                for (comment in reply.replies) {
                    // Comments on the reply itself; those answering another comment count for that comment.
                    val onReply = comment.call.replyTo.isEmpty() || comment.call.replyTo == reply.id.toString()
                    if (onReply && comment.call.author != me) {
                        items += ActivityItem(ActivityKind.Comment, comment.call.author, comment.call.height, comment.call.index, comment.call.time, post.id, comment.id, reply.call, comment.call)
                    }
                }
            }
            for (mine in reply.replies) {
                if (mine.call.author != me) continue
                likesAndTips(mine, post.id)
                for (comment in reply.replies) {
                    if (comment.call.replyTo == mine.id.toString() && comment.call.author != me) {
                        items += ActivityItem(ActivityKind.Comment, comment.call.author, comment.call.height, comment.call.index, comment.call.time, post.id, comment.id, mine.call, comment.call)
                    }
                }
            }
        }
    }
    return items.sortedWith(compareByDescending<ActivityItem> { it.height }.thenByDescending { it.index })
}

/** What can notify the identity, each kind on or off: likes, replies and comments, tips. */
enum class NotifyKind(val title: String) { Likes("Likes"), Comments("Comments"), Tips("Tips") }

val ActivityKind.notifyKind: NotifyKind
    get() = when (this) {
        ActivityKind.Like -> NotifyKind.Likes
        ActivityKind.Reply, ActivityKind.Comment -> NotifyKind.Comments
        ActivityKind.Tip -> NotifyKind.Tips
    }

/** The [items] that are news: in a block after [seenThrough] (the scan's head when last seen), of a kind on in [enabled]. */
fun newActivity(items: List<ActivityItem>, seenThrough: Long, enabled: Set<NotifyKind>): List<ActivityItem> =
    items.filter { it.height > seenThrough && it.kind.notifyKind in enabled }

/** What the in-app notice says of [news] (newest first): the one thing done, or how many of each kind. */
fun noticeText(news: List<ActivityItem>, shortAddress: (String) -> String): String {
    if (news.size == 1) {
        val item = news[0]
        val action = when (item.kind) {
            ActivityKind.Like -> "liked your post"
            ActivityKind.Reply -> "replied to your post"
            ActivityKind.Comment -> "commented on your post"
            ActivityKind.Tip -> "tipped you ${item.amount} iDNA"
        }
        return "${shortAddress(item.actor)} $action"
    }
    val counts = news.groupingBy { it.kind.notifyKind }.eachCount()
    val parts = NotifyKind.entries.mapNotNull { kind ->
        val n = counts[kind] ?: return@mapNotNull null
        when (kind) {
            NotifyKind.Likes -> if (n == 1) "1 like" else "$n likes"
            NotifyKind.Comments -> if (n == 1) "1 reply" else "$n replies"
            NotifyKind.Tips -> if (n == 1) "1 tip" else "$n tips"
        }
    }
    return "${news.size} new on your posts: ${parts.joinToString(", ")}"
}

/**
 * One entry of a profile's lists: [node] (a post, reply or comment) in the thread of post [threadId],
 * with what it answers ([parent]: the post of a reply, the reply or comment a comment answers).
 */
data class ProfileItem(
    val node: SocialPost,
    val threadId: Long,
    val parent: PostCall?,
    /** In the likes: the profile's like of [node]. */
    val like: PostCall? = null,
    /** In the tips: a tip on [node], made or received by the profile. */
    val tip: TipCall? = null,
) {
    /** The block of what the entry shows: the like, the tip, or else the post itself. */
    val height: Long
        get() = like?.height ?: tip?.height ?: node.call.height

    val index: Int
        get() = like?.index ?: tip?.index ?: node.call.index
}

/**
 * What [address] did on idena.social, newest first, as idena.social's profile shows it (Profile.tsx):
 * its posts, replies and comments, what it liked, its tips (made and received), and its posts with media.
 */
data class SocialProfile(
    val address: String,
    val posts: List<ProfileItem>,
    val replies: List<ProfileItem>,
    val comments: List<ProfileItem>,
    val likes: List<ProfileItem>,
    val tips: List<ProfileItem>,
    val media: List<ProfileItem>,
    /** The likes on its posts, replies and comments. */
    val likesReceived: Int,
    /** The iDNA tipped to it, and by it. */
    val tipsReceived: Long,
    val tipsGiven: Long,
)

/** The profile of [address] in [feed]. */
fun socialProfile(feed: List<SocialPost>, address: String): SocialProfile {
    val me = address.lowercase()
    val posts = ArrayList<ProfileItem>()
    val replies = ArrayList<ProfileItem>()
    val comments = ArrayList<ProfileItem>()
    val likes = ArrayList<ProfileItem>()
    val tips = ArrayList<ProfileItem>()
    val media = ArrayList<ProfileItem>()
    var likesReceived = 0
    var tipsReceived = 0L
    var tipsGiven = 0L

    fun visit(node: SocialPost, threadId: Long, parent: PostCall?, list: MutableList<ProfileItem>) {
        val own = node.call.author == me
        if (own) {
            val item = ProfileItem(node, threadId, parent)
            list += item
            if (node.call.hasMedia) media += item
            likesReceived += node.likes
        }
        for (like in node.likeCalls) {
            if (like.author == me) likes += ProfileItem(node, threadId, parent, like = like)
        }
        for (tip in node.tips) {
            val amount = tip.validAmount ?: 0
            if (own) tipsReceived += amount
            if (tip.from == me) tipsGiven += amount
            if (own || tip.from == me) tips += ProfileItem(node, threadId, parent, tip = tip)
        }
    }

    for (post in feed) {
        visit(post, post.id, null, posts)
        for (reply in post.replies) {
            visit(reply, post.id, post.call, replies)
            val inReply = reply.replies.associateBy { it.id }
            for (comment in reply.replies) {
                // A comment answers another comment of the reply, or else the reply.
                val answered = comment.call.replyTo.toLongOrNull()?.let { inReply[it] }?.call ?: reply.call
                visit(comment, post.id, answered, comments)
            }
        }
    }

    val newestFirst = compareByDescending<ProfileItem> { it.height }.thenByDescending { it.index }
    return SocialProfile(
        me, posts.sortedWith(newestFirst), replies.sortedWith(newestFirst), comments.sortedWith(newestFirst),
        likes.sortedWith(newestFirst), tips.sortedWith(newestFirst), media.sortedWith(newestFirst),
        likesReceived, tipsReceived, tipsGiven,
    )
}

/** What a profile shows of an identity (dna_identity), as idena.social: its state, age and stake. */
data class ProfileIdentity(val state: String, val age: Int, val stake: BigDecimal) {
    /** The state as idena.social names it: "Not validated" for an address without an identity. */
    val status: String
        get() = if (state.isEmpty() || state == "Undefined") "Not validated" else state

    companion object {
        fun fromJson(json: JSONObject) = ProfileIdentity(
            state = json.optString("state"),
            age = json.optInt("age"),
            stake = json.optString("stake").toBigDecimalOrNull() ?: BigDecimal.ZERO,
        )
    }
}

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
 * [low]..[high] (none while [high] < [low]), the posts and tips found there, and the post authors as of
 * block [authorsHeight].
 */
data class SocialCache(
    val low: Long,
    val high: Long,
    val posts: List<PostCall>,
    val authors: Map<Long, String>,
    val authorsHeight: Long,
    val tips: List<TipCall> = emptyList(),
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

    /** The feed of the posts and tips the author map covers. */
    fun feed(): List<SocialPost> =
        socialFeed(posts.filter { it.height <= authorsHeight }, authors, tips.filter { it.height <= authorsHeight })

    /** This cache with the calls of more blocks. */
    fun plus(calls: BlockCalls) = copy(posts = posts + calls.posts, tips = tips + calls.tips)

    fun toJson(): JSONObject {
        val postArray = JSONArray()
        posts.forEach { postArray.put(it.toJson()) }
        val tipArray = JSONArray()
        tips.forEach { tipArray.put(it.toJson()) }
        val authorObject = JSONObject()
        authors.forEach { (id, author) -> authorObject.put(id.toString(), author) }
        return JSONObject().put("contract", SOCIAL_CONTRACT).put("version", VERSION).put("low", low).put("high", high)
            .put("posts", postArray).put("tips", tipArray).put("authors", authorObject).put("authorsHeight", authorsHeight)
    }

    companion object {
        /** 3: the media references are kept too; a scan of an older version is done again. */
        const val VERSION = 3

        /** The cache from its JSON; null when it is for another contract or an older version. */
        fun fromJson(json: JSONObject): SocialCache? {
            if (!json.optString("contract").equals(SOCIAL_CONTRACT, ignoreCase = true)) return null
            if (json.optInt("version", 1) != VERSION) return null
            val postArray = json.getJSONArray("posts")
            val tipArray = json.optJSONArray("tips") ?: JSONArray()
            val authorObject = json.getJSONObject("authors")
            return SocialCache(
                low = json.getLong("low"),
                high = json.getLong("high"),
                posts = (0 until postArray.length()).map { PostCall.fromJson(postArray.getJSONObject(it)) },
                authors = authorObject.keys().asSequence().associate { it.toLong() to authorObject.getString(it) },
                authorsHeight = json.getLong("authorsHeight"),
                tips = (0 until tipArray.length()).map { TipCall.fromJson(tipArray.getJSONObject(it)) },
            )
        }
    }
}

/** The node as the Social tab reads it at each scan: its peers, its sync, and whether a validation runs. */
data class NodeView(val peers: Int, val syncing: Boolean, val currentBlock: Long, val highestBlock: Long, val validation: Boolean)

/** How the Social tab stands, shown as a colored dot: up to date, behind, down (node or peers), or off. */
enum class ScanHealth { UpToDate, Behind, Down, Off }

/** The dot and the short line of the Social tab's status bar. */
data class ScanState(val health: ScanHealth, val label: String)

/** Blocks the scan may lag the node's head and still be up to date: a block comes about every 20 s. */
const val UP_TO_DATE_LAG = 3

/**
 * The status of the Social tab from the node ([node] null when it does not answer) and the scan: down
 * without the node or its peers; off while the scan is off or a validation runs; behind while the node
 * syncs, the posts are more than [UP_TO_DATE_LAG] blocks behind its head, or the history is not read yet.
 */
fun scanState(node: NodeView?, scanning: Boolean, head: Long?, cache: SocialCache?): ScanState {
    if (node == null) return ScanState(ScanHealth.Down, "The node does not answer")
    if (node.peers == 0) return ScanState(ScanHealth.Down, "No peers")
    if (node.validation) return ScanState(ScanHealth.Off, "Paused during the validation")
    if (node.syncing) return ScanState(ScanHealth.Behind, "The node is syncing")
    if (cache == null) return if (scanning) ScanState(ScanHealth.Behind, "Starting the scan") else ScanState(ScanHealth.Off, "Scan off")
    if (!scanning) return ScanState(ScanHealth.Off, "Scan off")
    val behind = (head ?: cache.authorsHeight) - cache.authorsHeight
    if (behind > UP_TO_DATE_LAG) return ScanState(ScanHealth.Behind, if (behind == 1L) "1 block behind" else "$behind blocks behind")
    if (!cache.historyDone) return ScanState(ScanHealth.Behind, "Reading older posts: ${(cache.scannedShare * 100).toInt()}%")
    return ScanState(ScanHealth.UpToDate, "Up to date")
}

/** What the scan needs from the node. */
interface SocialSource {
    /** The most blocks [calls] takes at once. */
    fun batchSize(): Long

    /** The node's head block. */
    fun head(): Long

    /** The posts and tips of blocks [from]..[to]; throws if a block is missing. */
    fun calls(from: Long, to: Long): BlockCalls

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

/** Scans one batch of blocks before the cache, down to the contract's first block. */
fun scanOlderBlocks(cache: SocialCache, source: SocialSource, save: (SocialCache) -> Unit): SocialCache {
    if (cache.historyDone) return cache
    val from = maxOf(cache.low - source.batchSize(), SOCIAL_FIRST_BLOCK)
    val older = source.calls(from, cache.low - 1)
    val next = cache.copy(low = from, posts = older.posts + cache.posts, tips = older.tips + cache.tips)
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
