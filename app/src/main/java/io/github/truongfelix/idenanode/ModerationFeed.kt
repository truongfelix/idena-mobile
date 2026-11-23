package io.github.truongfelix.idenanode

/*
 * What Community moderation hides in the feed, by the idena.social website's rules (docs/moderation.md, "What gets
 * hidden"), as the desktop app (moderation-feed.js): a post made while its author is banned is not shown while the
 * ban lasts (an unban or its end shows it again); a post made before the ban, or hidden one by one, stays as a
 * placeholder (no text, no media, no author, no actions) while a reply or comment under it is still shown, and
 * leaves otherwise. Likes are never hidden (a "like" with media is no like). Tips and likes keep counting.
 */

/** Why a post is hidden: made during its author's ban (never shown then), or hidden with a [reason]. */
sealed interface HiddenStatus {
    data object DuringBan : HiddenStatus
    data class Hidden(val reason: String) : HiddenStatus
}

/** Why [call] is hidden in [epoch], or null. */
fun hiddenStatus(call: PostCall, state: ModerationState, epoch: Long?): HiddenStatus? {
    if (call.isLike && !call.hasMedia) return null
    val ban = state.activeBan(call.author, epoch)
    // Made during the ban: after the ban's call in chain order (a post earlier in the ban's block was before it).
    if (ban != null && (call.height > ban.since.height || call.height == ban.since.height && call.index > ban.since.index))
        return HiddenStatus.DuringBan
    state.hidden[normalHex(call.hash)]?.let { return HiddenStatus.Hidden(it.reason) }
    if (ban != null) return HiddenStatus.Hidden(ban.reason)
    return null
}

/** A placeholder's call: the same post, without what it says or shows. */
private fun PostCall.emptied() = copy(message = "", hasMedia = false, media = "", mediaType = "")

/** The posts Community moderation shows as a placeholder (by transaction hash), with the moderators' reason. */
data class ModeratedFeed(val feed: List<SocialPost>, val placeholders: Map<String, String>)

/**
 * The feed (posts, their replies, each reply's comments) as Community moderation shows it: what leaves is taken out,
 * placeholders have their text and media gone and are listed in [ModeratedFeed.placeholders]. The same feed when
 * nothing is moderated.
 */
fun moderatedFeed(feed: List<SocialPost>, state: ModerationState, epoch: Long?): ModeratedFeed {
    if (state.isEmpty) return ModeratedFeed(feed, emptyMap())
    val placeholders = mutableMapOf<String, String>()
    fun placeholder(post: SocialPost, status: HiddenStatus.Hidden, replies: List<SocialPost>): SocialPost {
        placeholders[post.call.hash.lowercase()] = status.reason
        return post.copy(call = post.call.emptied(), replies = replies)
    }

    // A reply's comments: a comment's answers are the comments that reply to it; a loop counts as nothing shown.
    fun comments(comments: List<SocialPost>): List<SocialPost> {
        val ids = comments.map { it.id.toString() }.toSet()
        val answers = comments.filter { it.call.replyTo in ids && it.call.replyTo != it.id.toString() }
            .groupBy { it.call.replyTo }
        val shown = mutableMapOf<Long, Boolean>()
        val resolving = mutableSetOf<Long>()
        fun shows(comment: SocialPost): Boolean {
            shown[comment.id]?.let { return it }
            if (!resolving.add(comment.id)) return false
            val result = when (val status = hiddenStatus(comment.call, state, epoch)) {
                null -> true
                HiddenStatus.DuringBan -> false
                is HiddenStatus.Hidden -> answers[comment.id.toString()].orEmpty().any { shows(it) }
            }
            resolving.remove(comment.id)
            shown[comment.id] = result
            return result
        }
        return comments.filter { shows(it) }.map { comment ->
            when (val status = hiddenStatus(comment.call, state, epoch)) {
                is HiddenStatus.Hidden -> placeholder(comment, status, comment.replies)
                else -> comment
            }
        }
    }

    // A post or a reply: it leaves when made during a ban, or hidden with nothing shown under it.
    fun node(post: SocialPost, replies: List<SocialPost>): SocialPost? =
        when (val status = hiddenStatus(post.call, state, epoch)) {
            null -> post.copy(replies = replies)
            HiddenStatus.DuringBan -> null
            is HiddenStatus.Hidden -> if (replies.isEmpty()) null else placeholder(post, status, replies)
        }

    val shown = feed.mapNotNull { post ->
        node(post, post.replies.mapNotNull { reply -> node(reply, comments(reply.replies)) })
    }
    return ModeratedFeed(shown, placeholders)
}

/** Whether [post] is a placeholder of [moderated]. */
fun ModeratedFeed.isPlaceholder(post: SocialPost): Boolean = post.call.hash.lowercase() in placeholders
