package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
import java.math.BigDecimal

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
enum class NotifyKind(@StringRes private val titleId: Int) {
    Likes(R.string.social_notify_likes), Comments(R.string.social_notify_comments), Tips(R.string.social_notify_tips),
    ;

    val title: String
        get() = t(titleId)
}

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
        val actor = shortAddress(item.actor)
        return when (item.kind) {
            ActivityKind.Like -> t(R.string.social_notice_liked, actor)
            ActivityKind.Reply -> t(R.string.social_notice_replied, actor)
            ActivityKind.Comment -> t(R.string.social_notice_commented, actor)
            ActivityKind.Tip -> t(R.string.social_notice_tipped, actor, "${item.amount?.let(::tipText)}")
        }
    }
    val counts = news.groupingBy { it.kind.notifyKind }.eachCount()
    val parts = NotifyKind.entries.mapNotNull { kind ->
        val n = counts[kind] ?: return@mapNotNull null
        when (kind) {
            NotifyKind.Likes -> tp(R.plurals.social_notice_likes, n, n)
            NotifyKind.Comments -> tp(R.plurals.social_notice_replies, n, n)
            NotifyKind.Tips -> tp(R.plurals.social_notice_tips, n, n)
        }
    }
    return t(R.string.social_notice_many, news.size, parts.joinToString(", "))
}
