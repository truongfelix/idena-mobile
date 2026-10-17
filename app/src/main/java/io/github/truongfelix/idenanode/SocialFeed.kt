package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
import java.math.BigDecimal

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
 * older parent that is not a like; a post (not an answer) may also be in a topic's channel ([topicOf]),
 * posts in other channels are left out. A tip counts on the post it
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
            post.replyTo.isEmpty() && topicOf(post.channel) != null -> null
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
enum class FeedSort(@StringRes private val titleId: Int, val ranked: Boolean = false) {
    Activity(R.string.social_sort_activity),
    Newest(R.string.social_sort_newest),
    Likes(R.string.social_sort_likes, ranked = true),
    Comments(R.string.social_sort_comments, ranked = true),
    Tips(R.string.social_sort_tips, ranked = true),
    ;

    val title: String
        get() = t(titleId)
}

/** The posts a ranked sort looks at: made in the current epoch, the last 7 days, 30 days, 365 days, or ever. */
enum class FeedPeriod(@StringRes private val titleId: Int, val days: Int = 0) {
    Epoch(R.string.social_period_epoch), Week(R.string.social_period_week, 7), Month(R.string.social_period_month, 30),
    Year(R.string.social_period_year, 365), All(R.string.social_period_all),
    ;

    val title: String
        get() = t(titleId)
}

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
                FeedPeriod.All -> true
                else -> it.call.time >= now - period.days * 24L * 3600
            }
        }
        .map { it to rankOf(it, sort) }
        .filter { it.second.signum() > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
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
