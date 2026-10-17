package io.github.truongfelix.idenanode

import org.json.JSONObject
import java.math.BigDecimal

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

/**
 * An identity's state (dna_identity) as idena.social names it, in the app's language: "Not validated" for an
 * address without one; a state the app does not know shows as the node writes it.
 */
fun identityStatus(state: String): String =
    if (state.isEmpty() || state == "Undefined") t(R.string.social_not_validated) else identityStateText(state)

/** What a post's header shows of its author's identity: the state and the age in epochs. */
data class AuthorIdentity(val state: String, val age: Int) {
    /** "Human · age 12"; the state alone without an identity or at age 0. */
    val label: String
        get() = if (age > 0 && state.isNotEmpty() && state != "Undefined") t(R.string.social_status_age, identityStatus(state), age) else identityStatus(state)
}

/**
 * How long ago [time] was at [now] (Unix seconds), as Reddit shows it: 5s, 10min, 7h, 1d, 5mo, 2y (a month
 * counted as 30 days, a year as 365); "now" for a time in the future (a phone clock behind the block's).
 */
fun timeAgo(time: Long, now: Long): String {
    val s = now - time
    return when {
        s < 0 -> t(R.string.social_ago_now)
        s < 60 -> t(R.string.social_ago_seconds, s)
        s < 3600 -> t(R.string.social_ago_minutes, s / 60)
        s < 86_400 -> t(R.string.social_ago_hours, s / 3600)
        s < 30 * 86_400 -> t(R.string.social_ago_days, s / 86_400)
        s < 365 * 86_400 -> t(R.string.social_ago_months, s / (30 * 86_400))
        else -> (s / (365 * 86_400)).toInt().let { tp(R.plurals.social_ago_years, it, it) }
    }
}

/** What a profile shows of an identity (dna_identity), as idena.social: its state, age and stake. */
data class ProfileIdentity(
    val state: String,
    val age: Int,
    val stake: BigDecimal,
    /** Who invited it, while the node keeps the link (until it is Verified or Human, or killed); lowercase. */
    val inviter: String? = null,
    /** Whom it invited and the node still links to it (Invite, Candidate, Newbie): lowercase, in the node's order. */
    val invitees: List<String> = emptyList(),
) {
    /** The state as idena.social names it ([identityStatus]). */
    val status: String
        get() = identityStatus(state)

    companion object {
        fun fromJson(json: JSONObject): ProfileIdentity {
            val invitees = json.optJSONArray("invitees")
            return ProfileIdentity(
                state = json.optString("state"),
                age = json.optInt("age"),
                stake = json.optString("stake").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                inviter = json.optJSONObject("inviter")?.optString("address")?.lowercase()?.takeIf(::isAddress),
                // The node writes each as {"TxHash", "Address"} (state.TxAddr, no JSON names).
                invitees = (0 until (invitees?.length() ?: 0)).mapNotNull { i ->
                    invitees?.optJSONObject(i)?.optString("Address")?.lowercase()?.takeIf(::isAddress)
                }.distinct(),
            )
        }
    }
}
