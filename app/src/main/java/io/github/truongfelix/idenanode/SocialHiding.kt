package io.github.truongfelix.idenanode

import java.text.Normalizer

/*
 * What the user hides, on this phone only (never on chain: the posts stay public): blocked accounts ([Person.blocked])
 * and a list of hidden words. A blocked account's posts, replies, comments, likes and tips leave the lists, the
 * threads, the notifications and the messages. A post whose text has a hidden word leaves the lists; inside a thread,
 * a reply or comment with one shows folded ([PostContent]). The identity's own posts are never hidden.
 */

/** The addresses blocked in the People list. */
fun blockedAddresses(people: Map<String, Person>): Set<String> = people.values.filter { it.blocked }.map { it.address }.toSet()

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
 * The feed as shown: the posts of [blocked] accounts left out with their whole thread, their replies and comments
 * (with what answers them) left out of the others' threads, their likes and tips taken off; and the posts whose text
 * has a hidden word ([words]) left out. [me]'s posts always stay.
 */
fun visibleFeed(feed: List<SocialPost>, blocked: Set<String>, words: WordFilter, me: String?): List<SocialPost> {
    if (blocked.isEmpty() && words.isEmpty) return feed
    return feed.filter { it.call.author == me || it.call.author !in blocked && !words.hides(it.call, me) }.map { withoutBlocked(it, blocked, me) }
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

/** [items] without those made by a blocked account, nor answers whose text has a hidden word. */
fun visibleActivity(items: List<ActivityItem>, blocked: Set<String>, words: WordFilter, me: String?): List<ActivityItem> =
    if (blocked.isEmpty() && words.isEmpty) items
    else items.filter { it.actor !in blocked && it.answer?.let { answer -> words.hides(answer, me) } != true }

/** The conversations without those where a blocked account takes part. */
fun visibleConversations(conversations: List<Conversation>, blocked: Set<String>): List<Conversation> =
    if (blocked.isEmpty()) conversations else conversations.filter { c -> c.others.none { it in blocked } }

/** A hidden word or phrase is at most this long. */
const val MAX_HIDDEN_WORD = 60

/** At most this many hidden words. */
const val MAX_HIDDEN_WORDS = 300
