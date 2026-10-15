package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * What Social keeps on the phone for the user alone: the names given to people, the people followed and the topics
 * followed. Exported and imported as one file (Social's settings), to keep it or move it to another phone. Version 1
 * files (the Contacts export: names and people only) still import.
 */

/** The names and people ([Person]) and the topics followed. */
data class SocialLists(val people: Map<String, Person>, val topics: Set<String>)

/** The people followed, in name order ([sortedPeople]). */
fun followedPeople(people: Map<String, Person>): List<Person> = sortedPeople(people).filter { it.following }

/** The export file: [peopleToJson]'s people and the topics, sorted. */
fun socialListsToJson(lists: SocialLists): JSONObject =
    peopleToJson(lists.people).put("version", LISTS_VERSION).put("topics", JSONArray(lists.topics.sorted()))

/** A file's lists; entries that are not valid are left out. Null when it is not an export (no "people"). */
fun socialListsFromJson(json: JSONObject): SocialLists? {
    if (json.optJSONArray("people") == null) return null
    val array = json.optJSONArray("topics")
    val topics = (0 until (array?.length() ?: 0)).mapNotNull { i -> array?.optString(i)?.let { topicOf(TOPIC_PREFIX + it) } }.toSet()
    return SocialLists(peopleFromJson(json), topics)
}

/** What an import did: people added and changed ([PeopleImport]), topics added, and the lists after it. */
data class ListsImport(val people: PeopleImport, val topicsAdded: Int, val lists: SocialLists)

/** [current] with [imported] merged in, without losing anything ([mergePeople]; a topic followed on either side stays). */
fun mergeLists(current: SocialLists, imported: SocialLists): ListsImport {
    val people = mergePeople(current.people, imported.people)
    val topics = current.topics + imported.topics
    return ListsImport(people, topics.size - current.topics.size, SocialLists(people.people, topics))
}

private const val LISTS_VERSION = 2
