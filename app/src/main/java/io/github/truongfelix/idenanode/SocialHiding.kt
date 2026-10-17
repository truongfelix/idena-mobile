package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
import java.text.Normalizer

/*
 * What the user hides, on this phone only (never on chain: the posts stay public): blocked accounts ([Person.blocked]),
 * hidden words, posts hidden one by one and blocked topics ([Hiding]). A blocked account's posts, replies, comments,
 * likes and tips leave the lists, the threads, the notifications and the messages. A post hidden, or whose text has a
 * hidden word, leaves the lists and the notifications; inside a thread, such a reply or comment shows folded
 * ([PostContent]). A blocked topic's posts leave the lists. The identity's own posts are never hidden.
 */

/** The addresses blocked in the People list. */
fun blockedAddresses(people: Map<String, Person>): Set<String> = people.values.filter { it.blocked }.map { it.address }.toSet()

/** The addresses muted in the People list. */
fun mutedAddresses(people: Map<String, Person>): Set<String> = people.values.filter { it.muted }.map { it.address }.toSet()

/**
 * The Social setting "Pictures in posts" (user 2026-10-17): [Show] them (as before); [TapToShow]: the post stays, its
 * picture one line until tapped in its thread (nothing downloaded before); [Hide] posts with pictures: they leave the
 * feeds, a reply or comment with one folds in its thread. The identity's own posts are never filtered.
 */
enum class PicturesMode(@StringRes val title: Int) {
    Show(R.string.socialb_pictures_show),
    TapToShow(R.string.socialb_pictures_tap),
    Hide(R.string.socialb_pictures_hide),
    ;

    companion object {
        fun of(name: String?): PicturesMode = entries.firstOrNull { it.name == name } ?: Show
    }
}

/** [text] as a hidden word or phrase: one line, spaces collapsed, trimmed, at most [MAX_HIDDEN_WORD] characters; null if empty. */
fun cleanHiddenWord(text: String): String? =
    text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().replace(Regex("\\s+"), " ")
        .takeWhole(MAX_HIDDEN_WORD).trim().takeIf { it.isNotEmpty() }

/** [text] lowercase, without accents, spaces collapsed: what a hidden word is matched against. */
fun matchForm(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ")

/**
 * The hidden words and phrases: a text matches when it holds one as a whole word (not inside a longer word), with
 * case and accents ignored.
 */
class WordFilter(words: Collection<String>) {
    private val patterns = words.mapNotNull { cleanHiddenWord(it) }.map(::matchForm).distinct().map {
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])")
    }

    val isEmpty: Boolean
        get() = patterns.isEmpty()

    fun matches(text: String): Boolean {
        if (patterns.isEmpty() || text.isEmpty()) return false
        val form = matchForm(text)
        return patterns.any { it.containsMatchIn(form) }
    }

    /** Whether [call]'s text, when in the transaction, has a hidden word; not for [me]'s own posts. */
    fun hides(call: PostCall, me: String?): Boolean = call.author != me && !call.message.startsWith("ipfs://") && matches(call.message)

    companion object {
        val None = WordFilter(emptyList())
    }
}

/**
 * What is hidden: the accounts blocked ([blocked], lowercase), the hidden words ([words]), the posts, replies and
 * comments hidden one by one (by transaction hash, [posts]) and the topics blocked ([topics]); what leaves the feeds
 * only: the accounts muted ([muted]) and, with [pictures] at Hide, the posts with pictures.
 */
data class Hiding(
    val blocked: Set<String> = emptySet(),
    val words: WordFilter = WordFilter.None,
    val posts: Set<String> = emptySet(),
    val topics: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),
    val pictures: PicturesMode = PicturesMode.Show,
) {
    /**
     * Whether [post] leaves the feeds (Home, Following, a topic's page), but stays on its author's profile and in
     * threads: a muted author's, or one with a picture while pictures are hidden; never [me]'s. In a thread, such a
     * reply or comment starts folded.
     */
    fun leavesFeeds(post: SocialPost, me: String?): Boolean =
        post.call.author != me && (post.call.author in muted || (pictures == PicturesMode.Hide && post.call.hasMedia))

    val isEmpty: Boolean
        get() = blocked.isEmpty() && words.isEmpty && posts.isEmpty() && topics.isEmpty()

    /** Whether [call]'s text is hidden (hidden one by one, or with a hidden word in the transaction); never [me]'s. */
    fun hidesText(call: PostCall, me: String?): Boolean = call.author != me && (call.hash.lowercase() in posts || words.hides(call, me))

    companion object {
        val None = Hiding()
    }
}

/**
 * The feed as shown: the posts of blocked accounts left out with their whole thread, their replies and comments
 * (with what answers them) left out of the others' threads, their likes and tips taken off; the posts hidden, with
 * a hidden word or in a blocked topic left out. [me]'s posts always stay.
 */
fun visibleFeed(feed: List<SocialPost>, hiding: Hiding, me: String?): List<SocialPost> {
    if (hiding.isEmpty) return feed
    val blocked = hiding.blocked
    return feed.filter { post ->
        post.call.author == me || post.call.author !in blocked && !hiding.hidesText(post.call, me) && post.topic !in hiding.topics
    }.map { withoutBlocked(it, blocked, me) }
}

private fun withoutBlocked(post: SocialPost, blocked: Set<String>, me: String?): SocialPost {
    if (blocked.isEmpty()) return post
    val replies = post.replies.filter { it.call.author == me || it.call.author !in blocked }
    // Comments answering a removed comment leave with it.
    val removed = post.replies.filter { it !in replies }.map { it.id.toString() }.toMutableSet()
    var kept = replies
    while (true) {
        val next = kept.filter { it.call.replyTo !in removed || it.call.author == me }
        if (next.size == kept.size) break
        removed += kept.filter { it !in next }.map { it.id.toString() }
        kept = next
    }
    return post.copy(
        likeCalls = post.likeCalls.filter { it.author !in blocked },
        tips = post.tips.filter { it.from !in blocked },
        replies = kept.map { withoutBlocked(it, blocked, me) },
    )
}

/** The posts of a feed ([posts]) without those that leave the feeds ([Hiding.leavesFeeds]). */
fun feedPosts(posts: List<SocialPost>, hiding: Hiding, me: String?): List<SocialPost> =
    if (hiding.muted.isEmpty() && hiding.pictures != PicturesMode.Hide) posts else posts.filterNot { hiding.leavesFeeds(it, me) }

/** [items] without those made by a blocked account, nor answers hidden ([Hiding.hidesText]). */
fun visibleActivity(items: List<ActivityItem>, hiding: Hiding, me: String?): List<ActivityItem> =
    if (hiding.isEmpty) items
    else items.filter { it.actor !in hiding.blocked && it.answer?.let { answer -> hiding.hidesText(answer, me) } != true }

/**
 * The thread of post [id] as shown when opened: from the whole [feed], since a post hidden, with a hidden word or in a
 * blocked topic still opens (folded, [PostContent]); without what blocked accounts wrote, and none for their posts.
 */
fun threadPost(feed: List<SocialPost>, id: Long, hiding: Hiding, me: String?): SocialPost? =
    feed.firstOrNull { it.id == id }?.let { visibleFeed(listOf(it), Hiding(blocked = hiding.blocked), me).firstOrNull() }

/** The conversations without those where a blocked account takes part. */
fun visibleConversations(conversations: List<Conversation>, blocked: Set<String>): List<Conversation> =
    if (blocked.isEmpty()) conversations else conversations.filter { c -> c.others.none { it in blocked } }

/** A hidden word or phrase is at most this long. */
const val MAX_HIDDEN_WORD = 60

/** At most this many hidden words. */
const val MAX_HIDDEN_WORDS = 300

/** A transaction hash as the posts carry it: 0x and 64 hex digits, lowercase; null when it is not one. */
fun txHashOf(text: String): String? = text.trim().lowercase().takeIf { Regex("^0x[0-9a-f]{64}$").matches(it) }
