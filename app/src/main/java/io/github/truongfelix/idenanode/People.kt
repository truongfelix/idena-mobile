package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * The People list, kept on the phone only (files/social/people.json): a private name for an address,
 * shown instead of the address in the Social tab, and two marks: Following (their posts in the feed's
 * Following filter) and Message (private conversations, for the messages to come). Nothing is published.
 */

/** Someone of the People list: [address] lowercase, [name] empty when not named. */
data class Person(
    val address: String,
    val name: String = "",
    val following: Boolean = false,
    val message: Boolean = false,
) {
    /** Whether there is anything to keep: a person with no name and no mark leaves the list. */
    val isEmpty: Boolean
        get() = name.isEmpty() && !following && !message

    fun toJson(): JSONObject = JSONObject().put("address", address).put("name", name).put("following", following).put("message", message)

    companion object {
        /** A name is one line, trimmed, at most this long. */
        const val MAX_NAME = 40

        fun fromJson(json: JSONObject): Person? {
            val address = json.optString("address").lowercase().takeIf(::isAddress) ?: return null
            return Person(address, cleanName(json.optString("name")), json.optBoolean("following"), json.optBoolean("message"))
        }
    }
}

/** [text] as a name: one line, no control characters, trimmed, at most [Person.MAX_NAME] characters. */
fun cleanName(text: String): String =
    text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().replace(Regex("\\s+"), " ").takeWhole(Person.MAX_NAME).trim()

/** The first [n] UTF-16 units of the text, one less when the last would split an emoji (a surrogate pair). */
fun String.takeWhole(n: Int): String {
    val cut = take(n)
    return if (cut.length == n && cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** The People list by address, with [person] put in (or taken out once it has no name and no mark). */
fun Map<String, Person>.with(person: Person): Map<String, Person> =
    if (person.isEmpty) this - person.address else this + (person.address to person)

/** The people of the list in name order (named ones first, then by address). */
fun sortedPeople(people: Map<String, Person>): List<Person> =
    people.values.sortedWith(compareBy<Person> { it.name.isEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.address })

fun peopleToJson(people: Map<String, Person>): JSONObject {
    val array = JSONArray()
    sortedPeople(people).forEach { array.put(it.toJson()) }
    return JSONObject().put("version", PEOPLE_VERSION).put("people", array)
}

/** The list from its JSON; entries that are not valid are left out. */
fun peopleFromJson(json: JSONObject): Map<String, Person> {
    val array = json.optJSONArray("people") ?: return emptyMap()
    var people = emptyMap<String, Person>()
    for (i in 0 until array.length()) {
        array.optJSONObject(i)?.let(Person::fromJson)?.let { people = people.with(it) }
    }
    return people
}

private const val PEOPLE_VERSION = 1

/** How [address] shows: its name in the People list, else shortened. */
fun displayName(address: String, names: Map<String, String>): String = names[address.lowercase()] ?: shortAddress(address)

/** The names of the People list, by lowercase address. */
fun peopleNames(people: Map<String, Person>): Map<String, String> =
    people.values.filter { it.name.isNotEmpty() }.associate { it.address to it.name }

/** The posts of [feed] whose author is followed. */
fun followingFeed(feed: List<SocialPost>, people: Map<String, Person>): List<SocialPost> =
    feed.filter { people[it.call.author]?.following == true }

/** What a search finds. */
sealed interface SearchHit {
    /** A pasted address: its profile. */
    data class Address(val address: String) : SearchHit

    /** Someone of the People list, by name or address. */
    data class Named(val person: Person) : SearchHit

    /**
     * A post, reply or comment ([node]) in the thread of post [threadId] whose text matches; [target] is
     * where a like of it goes (a reply's and a comment's go to the reply's discussion channel).
     */
    data class Text(val node: SocialPost, val threadId: Long, val target: PostTarget) : SearchHit
}

/** The shortest query searched in the texts. */
const val MIN_SEARCH = 2

/**
 * What [query] finds, in this order: the address it is, the people whose name (or address) has it, then
 * the posts, replies and comments whose text has it, newest first, at most [limit]. A text on IPFS is
 * searched when [ipfsText] has it (fetched already); likes are not texts.
 */
fun searchSocial(
    feed: List<SocialPost>,
    people: Map<String, Person>,
    query: String,
    ipfsText: (String) -> String? = { null },
    limit: Int = 200,
): List<SearchHit> {
    val q = query.trim()
    if (q.length < MIN_SEARCH) return emptyList()
    val hits = ArrayList<SearchHit>()
    if (isAddress(q)) hits += SearchHit.Address(q.lowercase())
    val lower = q.lowercase()
    for (person in sortedPeople(people)) {
        val byAddress = lower.startsWith("0x") && lower.length >= 4 && person.address.startsWith(lower)
        if (person.name.contains(q, ignoreCase = true) || byAddress) hits += SearchHit.Named(person)
    }
    val texts = ArrayList<SearchHit.Text>()
    /** [replyId]: the reply a comment is under, null for the post and its replies. */
    fun visit(node: SocialPost, threadId: Long, replyId: Long?) {
        val message = node.call.message
        val text = ipfsCid(message)?.let(ipfsText) ?: message.takeUnless { it.startsWith("ipfs://") }
        if (text != null && text.contains(q, ignoreCase = true)) {
            val target = when {
                node.id == threadId -> PostTarget.onPost(node.id)
                replyId == null -> PostTarget.onReply(node.id)
                else -> PostTarget.onComment(replyId, node.id)
            }
            texts += SearchHit.Text(node, threadId, target)
        }
        val under = if (node.id == threadId) null else replyId ?: node.id
        node.replies.forEach { visit(it, threadId, under) }
    }
    feed.forEach { visit(it, it.id, null) }
    texts.sortWith(compareByDescending<SearchHit.Text> { it.node.call.height }.thenByDescending { it.node.call.index })
    return hits + texts.take(limit)
}

/** What an import changed: people added, people changed, and the list after it. */
data class PeopleImport(val added: Int, val changed: Int, val people: Map<String, Person>)

/**
 * [current] with an exported list ([imported]) merged in, without losing anything: a name from the file
 * replaces the phone's (an empty one keeps it), and a mark set on either side stays set.
 */
fun mergePeople(current: Map<String, Person>, imported: Map<String, Person>): PeopleImport {
    var people = current
    var added = 0
    var changed = 0
    for (person in imported.values) {
        val old = people[person.address]
        val merged = if (old == null) person else Person(
            person.address,
            person.name.ifEmpty { old.name },
            following = old.following || person.following,
            message = old.message || person.message,
        )
        if (old == null) added++ else if (merged != old) changed++
        people = people.with(merged)
    }
    return PeopleImport(added, changed, people)
}
