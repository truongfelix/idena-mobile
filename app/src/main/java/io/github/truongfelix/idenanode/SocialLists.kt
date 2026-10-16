package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * What Social keeps on the phone for the user alone: the names given to people, the people followed and blocked, the
 * topics followed and the hidden words. Exported and imported as one file (Social's settings), to keep it or move it to another phone. Version 1
 * files (the Contacts export: names and people only) still import.
 */

/** The names and people ([Person]), the topics followed and the hidden words ([WordFilter]). */
data class SocialLists(val people: Map<String, Person>, val topics: Set<String>, val words: Set<String> = emptySet())

/** The people followed, in name order ([sortedPeople]). */
fun followedPeople(people: Map<String, Person>): List<Person> = sortedPeople(people).filter { it.following }

/** The export file: [peopleToJson]'s people, the topics and the hidden words, sorted. */
fun socialListsToJson(lists: SocialLists): JSONObject =
    peopleToJson(lists.people).put("version", LISTS_VERSION).put("topics", JSONArray(lists.topics.sorted()))
        .put("hiddenWords", JSONArray(lists.words.sorted()))

/** A file's lists; entries that are not valid are left out. Null when it is not an export (no "people"). */
fun socialListsFromJson(json: JSONObject): SocialLists? {
    if (json.optJSONArray("people") == null) return null
    val array = json.optJSONArray("topics")
    val topics = (0 until (array?.length() ?: 0)).mapNotNull { i -> array?.optString(i)?.let { topicOf(TOPIC_PREFIX + it) } }.toSet()
    val wordArray = json.optJSONArray("hiddenWords")
    val words = (0 until (wordArray?.length() ?: 0)).mapNotNull { i -> wordArray?.optString(i)?.let(::cleanHiddenWord) }.toSet()
    return SocialLists(peopleFromJson(json), topics, words)
}

/** What an import did: people added and changed ([PeopleImport]), topics and hidden words added, and the lists after it. */
data class ListsImport(val people: PeopleImport, val topicsAdded: Int, val lists: SocialLists, val wordsAdded: Int = 0)

/**
 * [current] with [imported] merged in, without losing anything ([mergePeople]; a topic followed or a word hidden on
 * either side stays, up to [MAX_HIDDEN_WORDS] words).
 */
fun mergeLists(current: SocialLists, imported: SocialLists): ListsImport {
    val people = mergePeople(current.people, imported.people)
    val topics = current.topics + imported.topics
    val words = current.words + (imported.words - current.words).take(maxOf(0, MAX_HIDDEN_WORDS - current.words.size))
    return ListsImport(people, topics.size - current.topics.size, SocialLists(people.people, topics, words), words.size - current.words.size)
}

private const val LISTS_VERSION = 3
