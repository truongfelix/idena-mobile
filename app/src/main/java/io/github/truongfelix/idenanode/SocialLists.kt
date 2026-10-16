package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * What Social keeps on the phone for the user alone: the names given to people, the people followed and blocked, the
 * topics followed and blocked, the hidden words and the posts hidden. Exported and imported as one file (Social's settings), to keep it or move it to another phone. Version 1
 * files (the Contacts export: names and people only) still import.
 */

/**
 * The names and people ([Person]), the topics followed, the hidden words ([WordFilter]), the posts hidden (their
 * transaction hashes, lowercase) and the topics blocked.
 */
data class SocialLists(
    val people: Map<String, Person>,
    val topics: Set<String>,
    val words: Set<String> = emptySet(),
    val hiddenPosts: Set<String> = emptySet(),
    val blockedTopics: Set<String> = emptySet(),
)

/** The people followed, in name order ([sortedPeople]). */
fun followedPeople(people: Map<String, Person>): List<Person> = sortedPeople(people).filter { it.following }

/** The export file: [peopleToJson]'s people, the topics, the hidden words, the hidden posts and the blocked topics, sorted. */
fun socialListsToJson(lists: SocialLists): JSONObject =
    peopleToJson(lists.people).put("version", LISTS_VERSION).put("topics", JSONArray(lists.topics.sorted()))
        .put("hiddenWords", JSONArray(lists.words.sorted())).put("hiddenPosts", JSONArray(lists.hiddenPosts.sorted()))
        .put("blockedTopics", JSONArray(lists.blockedTopics.sorted()))

/** The strings of array [name] of [json] that [clean] keeps. */
private fun strings(json: JSONObject, name: String, clean: (String) -> String?): Set<String> {
    val array = json.optJSONArray(name) ?: return emptySet()
    return (0 until array.length()).mapNotNull { i -> array.optString(i)?.let(clean) }.toSet()
}

/** A file's lists; entries that are not valid are left out. Null when it is not an export (no "people"). */
fun socialListsFromJson(json: JSONObject): SocialLists? {
    if (json.optJSONArray("people") == null) return null
    val topic = { name: String -> topicOf(TOPIC_PREFIX + name) }
    return SocialLists(
        peopleFromJson(json),
        strings(json, "topics", topic),
        strings(json, "hiddenWords", ::cleanHiddenWord),
        strings(json, "hiddenPosts", ::txHashOf),
        strings(json, "blockedTopics", topic),
    )
}

/**
 * What an import did: people added and changed ([PeopleImport]), topics, hidden words, and hidden posts and blocked
 * topics ([othersAdded]) added, and the lists after it.
 */
data class ListsImport(val people: PeopleImport, val topicsAdded: Int, val lists: SocialLists, val wordsAdded: Int = 0, val othersAdded: Int = 0)

/**
 * [current] with [imported] merged in, without losing anything ([mergePeople]; a topic followed or a word hidden on
 * either side stays, up to [MAX_HIDDEN_WORDS] words).
 */
fun mergeLists(current: SocialLists, imported: SocialLists): ListsImport {
    val people = mergePeople(current.people, imported.people)
    val topics = current.topics + imported.topics
    val words = current.words + (imported.words - current.words).take(maxOf(0, MAX_HIDDEN_WORDS - current.words.size))
    val hidden = current.hiddenPosts + imported.hiddenPosts
    val blockedTopics = current.blockedTopics + imported.blockedTopics
    return ListsImport(
        people,
        topics.size - current.topics.size,
        SocialLists(people.people, topics, words, hidden, blockedTopics),
        words.size - current.words.size,
        hidden.size - current.hiddenPosts.size + blockedTopics.size - current.blockedTopics.size,
    )
}

private const val LISTS_VERSION = 3
