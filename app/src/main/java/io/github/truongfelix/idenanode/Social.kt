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
    /** Empty for the main feed; "discuss:<id>" for a comment on a reply. */
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
    /** The tip as the argument has it; the contract takes whole iDNA. */
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
     * The tip in iDNA when the contract accepts it (sendTip): a whole number of at least 1 and no more
     * than was sent; null otherwise (such a call fails). Before v11 ([sent]) the tip is what was sent, if
     * anything; from the tips balance ([fromBalance]), its tipAmount, if anything (the balance it needs is not
     * known here).
     */
    val validAmount: BigDecimal?
        get() {
            if (sent || fromBalance) return (if (fromBalance) tipAmount else amount).toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            val tip = tipAmount.takeIf { WHOLE_IDNA.matches(it) }?.toBigDecimal()?.takeIf { it.signum() > 0 } ?: return null
            val paid = amount.toBigDecimalOrNull() ?: return null
            return tip.takeIf { paid >= it }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("from", from)
        .put("postId", postId).put("tipAmount", tipAmount).put("amount", amount).put("version", version)
        .put("sent", sent).put("fromBalance", fromBalance)

    companion object {
        private val WHOLE_IDNA = Regex("^[0-9]{1,15}$")
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
 * The posts and tips among contract calls (bcn_contractCalls) of contract [version]. A call the node marks as
 * refused by the contract (success false) is left out: it has no post id and moved no coins.
 */
fun callsToActivity(calls: JSONArray, version: SocialVersion = CURRENT_VERSION): BlockCalls {
    val posts = ArrayList<PostCall>()
    val tips = ArrayList<TipCall>()
    for (i in 0 until calls.length()) {
        val call = calls.getJSONObject(i)
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
        }
    }
    return BlockCalls(posts, tips)
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
    val tipTotal: BigDecimal
        get() = tips.sumOf { it.validAmount ?: BigDecimal.ZERO }

    /** Whether it was made with an older contract: read only. */
    val isOlder: Boolean
        get() = call.isOlder
}

/** The channel of the comments under reply [replyId] on idena.social. */
fun discussChannel(replyId: Long): String = "discuss:$replyId"

/**
 * The ids of the posts: the contract numbers the successful makePost calls 1, 2, 3... and keeps each
 * id's author ([authors], contract map "p:"). Going from the newest call to the oldest, a call gets the
 * next id down when its sender is that id's author; a call that does not match failed and has no id.
 * [posts] and [authors] must cover the same blocks: calls after the blocks the map was read at are left
 * out by the caller. With [fromOldest] (an older contract version: its posts after the next version came are
 * not read, but hold the last ids) it counts up from id 1 instead: [posts] must then hold all of its first posts.
 */
fun postIds(posts: List<PostCall>, authors: Map<Long, String>, fromOldest: Boolean = false): Map<String, Long> {
    val ids = HashMap<String, Long>()
    val last = authors.keys.maxOrNull() ?: return ids
    val ordered = posts.sortedWith(compareBy({ it.height }, { it.index }))
    var id = if (fromOldest) 1 else last
    for (post in if (fromOldest) ordered else ordered.asReversed()) {
        if (id < 1 || id > last) break
        if (authors[id] == post.author) {
            ids[post.hash] = id
            id += if (fromOldest) 1 else -1
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
 * names when its amount is valid and it comes after that post in the chain. [fromOldest]: see [postIds].
 */
fun socialFeed(posts: List<PostCall>, authors: Map<Long, String>, tips: List<TipCall> = emptyList(), fromOldest: Boolean = false): List<SocialPost> {
    val ids = postIds(posts, authors, fromOldest)
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

/**
 * How the feed is ordered: as idena.social offers it, by the newest posts or by the latest activity; or
 * the posts of a period ([FeedPeriod]) with the most likes, answers (replies and comments) or iDNA tipped.
 */
enum class FeedSort(val title: String, val ranked: Boolean = false) {
    Newest("Newest posts"),
    Activity("Latest activity"),
    Likes("Most liked", ranked = true),
    Comments("Most commented", ranked = true),
    Tips("Most tipped", ranked = true),
}

/** The posts a ranked sort looks at: made in the current epoch, the last 7 days, or ever. */
enum class FeedPeriod(val title: String) { Epoch("This epoch"), Week("7 days"), All("All") }

/** What a ranked sort counts on [post] itself. */
private fun rankOf(post: SocialPost, sort: FeedSort): BigDecimal = when (sort) {
    FeedSort.Likes -> post.likes.toBigDecimal()
    FeedSort.Comments -> replyCount(post).toBigDecimal()
    FeedSort.Tips -> post.tipTotal
    else -> BigDecimal.ZERO
}

/**
 * [feed] (newest posts first) in [sort]'s order; threads with activity in the same block keep their order.
 * A ranked sort keeps the posts of [period] that have at least one of what it counts, most first, newer
 * first on a tie: [now] (Unix seconds) sets the 7 days, [epochStart] (the epoch's first block) the epoch,
 * which is all posts while it is not known.
 */
fun sortFeed(
    feed: List<SocialPost>,
    sort: FeedSort,
    period: FeedPeriod = FeedPeriod.All,
    now: Long = 0,
    epochStart: Long? = null,
): List<SocialPost> = when (sort) {
    FeedSort.Newest -> feed
    FeedSort.Activity -> feed.sortedByDescending { lastActivityHeight(it) }
    else -> feed
        .filter {
            when (period) {
                FeedPeriod.Epoch -> epochStart == null || it.call.height >= epochStart
                FeedPeriod.Week -> it.call.time >= now - 7 * 24 * 3600
                FeedPeriod.All -> true
            }
        }
        .map { it to rankOf(it, sort) }
        .filter { it.second.signum() > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
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
    val amount: BigDecimal? = null,
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
            ActivityKind.Tip -> "tipped you ${item.amount?.let(::tipText)} iDNA"
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
 * What [address] did on idena.social, newest first: its posts, replies and comments, what it liked, the
 * tips it gave, and its posts with media. The likes and tips it received count in [SocialProfile.likesReceived]
 * and [SocialProfile.tipsReceived] only (idena.social's Profile.tsx also lists the tips received).
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
    val tipsReceived: BigDecimal,
    val tipsGiven: BigDecimal,
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
    var tipsReceived = BigDecimal.ZERO
    var tipsGiven = BigDecimal.ZERO

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
            val amount = tip.validAmount ?: BigDecimal.ZERO
            if (own) tipsReceived += amount
            if (tip.from == me) tipsGiven += amount
            if (tip.from == me) tips += ProfileItem(node, threadId, parent, tip = tip)
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

/** An identity's state (dna_identity) as idena.social names it: "Not validated" for an address without one. */
fun identityStatus(state: String): String = if (state.isEmpty() || state == "Undefined") "Not validated" else state

/** What a post's header shows of its author's identity: the state and the age in epochs. */
data class AuthorIdentity(val state: String, val age: Int) {
    /** "Human · age 12"; the state alone without an identity or at age 0. */
    val label: String
        get() = identityStatus(state) + if (age > 0 && identityStatus(state) != "Not validated") "  ·  age $age" else ""
}

/**
 * How long ago [time] was at [now] (Unix seconds), as Reddit shows it: 5s, 10min, 7h, 1d, 5mo, 2y (a month
 * counted as 30 days, a year as 365); "now" for a time in the future (a phone clock behind the block's).
 */
fun timeAgo(time: Long, now: Long): String {
    val s = now - time
    return when {
        s < 0 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}min"
        s < 86_400 -> "${s / 3600}h"
        s < 30 * 86_400 -> "${s / 86_400}d"
        s < 365 * 86_400 -> "${s / (30 * 86_400)}mo"
        else -> "${s / (365 * 86_400)}y"
    }
}

/** What a profile shows of an identity (dna_identity), as idena.social: its state, age and stake. */
data class ProfileIdentity(val state: String, val age: Int, val stake: BigDecimal) {
    /** The state as idena.social names it ([identityStatus]). */
    val status: String
        get() = identityStatus(state)

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
) {
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
    fun plus(calls: BlockCalls) = copy(posts = posts + calls.posts, tips = tips + calls.tips)

    fun toJson(): JSONObject {
        val postArray = JSONArray()
        posts.forEach { postArray.put(it.toJson()) }
        val tipArray = JSONArray()
        tips.forEach { tipArray.put(it.toJson()) }
        val olderObject = JSONObject()
        olderAuthors.forEach { (name, map) -> olderObject.put(name, authorsJson(map)) }
        return JSONObject().put("contract", SOCIAL_CONTRACT).put("version", VERSION).put("low", low).put("high", high)
            .put("posts", postArray).put("tips", tipArray).put("authors", authorsJson(authors)).put("authorsHeight", authorsHeight)
            .put("olderAuthors", olderObject)
    }

    companion object {
        /**
         * 3: the media references are kept too; a scan of an older version is done again. 4: the older contracts
         * too ([olderAuthors]); a scan of version 3 holds the current contract's blocks only and goes on below them.
         */
        const val VERSION = 4

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
            return SocialCache(
                low = json.getLong("low"),
                high = json.getLong("high"),
                posts = (0 until postArray.length()).map { PostCall.fromJson(postArray.getJSONObject(it)) },
                authors = authorsFrom(json.getJSONObject("authors")),
                authorsHeight = json.getLong("authorsHeight"),
                tips = (0 until tipArray.length()).map { TipCall.fromJson(tipArray.getJSONObject(it)) },
                olderAuthors = olderObject.keys().asSequence().associateWith { authorsFrom(olderObject.getJSONObject(it)) },
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
)

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
        height < SOCIAL_FIRST_BLOCK -> "Waiting for the node's newest blocks: it is at block ${blockNumber.format(height)}, before idena.social."
        age > MAX_START_BLOCK_AGE_SECONDS -> "Waiting for the node's newest blocks: its newest one is ${ageText(age)} old."
        else -> null
    }
}

private fun ageText(seconds: Long): String = when {
    seconds >= 2 * 86_400 -> "${seconds / 86_400} days"
    seconds >= 2 * 3_600 -> "${seconds / 3_600} h"
    else -> "${seconds / 60} min"
}

/**
 * The status of the Social tab from the node ([node] null when it does not answer) and the scan: down
 * without the node or its peers; off while the scan is off or a validation runs; behind while the node
 * syncs, the posts are more than [UP_TO_DATE_LAG] blocks behind its head, or the history is not read yet.
 */
fun scanState(node: NodeView?, scanning: Boolean, head: Long?, cache: SocialCache?): BarState {
    if (node == null) return BarState(Health.Bad, "The node does not answer")
    if (node.peers == 0) return BarState(Health.Bad, "No peers")
    if (node.validation) return BarState(Health.Off, "Paused during the validation")
    if (node.syncing) return BarState(Health.Warn, "The node is syncing")
    if (cache == null) return if (scanning) BarState(Health.Warn, "Starting the scan") else BarState(Health.Off, "Scan off")
    if (!scanning) return BarState(Health.Off, "Scan off")
    val behind = (head ?: cache.authorsHeight) - cache.authorsHeight
    if (behind > UP_TO_DATE_LAG) return BarState(Health.Warn, if (behind == 1L) "1 block behind" else "${blockNumber.format(behind)} blocks behind")
    if (!cache.historyDone) return BarState(Health.Warn, "Reading older posts: ${(cache.scannedShare * 100).toInt()}%")
    return BarState(Health.Good, "Up to date")
}

/** The Social status in the title row: [state]'s label, then the node's peers while it has some. */
fun socialBarLabel(state: BarState, peers: Int?): String = when {
    peers == null || peers <= 0 -> state.label
    peers == 1 -> "${state.label} · 1 peer"
    else -> "${state.label} · $peers peers"
}

/** Posts between two ads in the feed. */
const val POSTS_PER_AD = 5

/** How many posts of each [POSTS_PER_AD] in a row show an ad under them when opened. */
const val THREAD_ADS_PER_GROUP = 2

/**
 * Whether the thread of the post at [rank] (its place among the feed's posts, oldest first) shows an ad
 * under the post: in each [POSTS_PER_AD] posts in a row, [THREAD_ADS_PER_GROUP] picked at random. [salt]
 * changes the picks (one per app start) and keeps them the same while the app runs.
 */
fun threadShowsAd(rank: Int, salt: Long): Boolean {
    if (rank < 0) return false
    val group = rank / POSTS_PER_AD
    val picks = (0 until POSTS_PER_AD).shuffled(kotlin.random.Random(salt xor group.toLong() * 0x9E3779B97F4A7C15uL.toLong())).take(THREAD_ADS_PER_GROUP)
    return rank % POSTS_PER_AD in picks
}

/** The feed's rows for [posts] posts: an ad after each [POSTS_PER_AD] posts, none after a shorter end. */
fun feedRows(posts: Int): Int = posts + posts / POSTS_PER_AD

/** At feed row [row]: the ad's number (0, 1, …) when the row is an ad, else null. */
fun feedAdAt(row: Int): Int? = if (row % (POSTS_PER_AD + 1) == POSTS_PER_AD) row / (POSTS_PER_AD + 1) else null

/** At feed row [row], when it is not an ad: the post's index. */
fun feedPostAt(row: Int): Int = row / (POSTS_PER_AD + 1) * POSTS_PER_AD + row % (POSTS_PER_AD + 1)

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
    val next = cache.copy(low = from, posts = older.posts + cache.posts, tips = older.tips + cache.tips, olderAuthors = olderAuthors)
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
