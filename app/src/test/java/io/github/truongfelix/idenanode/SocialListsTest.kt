package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialListsTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"

    @Test
    fun theFileHoldsNamesPeopleTopicsBlocksAndHiddenWords() {
        val lists = SocialLists(
            mapOf(a to Person(a, "Anna", following = true), b to Person(b, "Bob"), c to Person(c, blocked = true)),
            setOf("idena", "flips"),
            setOf("spam", "free coins"),
        )
        val json = socialListsToJson(lists)
        assertEquals(3, json.getInt("version"))
        assertEquals("sorted", "[\"free coins\",\"spam\"]", json.getJSONArray("hiddenWords").toString())
        assertEquals("sorted", "[\"flips\",\"idena\"]", json.getJSONArray("topics").toString())
        assertEquals(lists, socialListsFromJson(JSONObject(json.toString())))
    }

    @Test
    fun aContactsFileOfVersion1StillImports() {
        val v1 = JSONObject(peopleToJson(mapOf(a to Person(a, "Anna", following = true))).toString())
        assertEquals(1, v1.getInt("version"))
        assertEquals(SocialLists(mapOf(a to Person(a, "Anna", following = true)), emptySet()), socialListsFromJson(v1))
        assertNull("not an export", socialListsFromJson(JSONObject("""{"topics":["idena"]}""")))
    }

    @Test
    fun aVersion2FileImportsWithoutHiddenWords() {
        val json = JSONObject("""{"version":2,"people":[{"address":"$a","name":"Anna","following":true,"message":false}],"topics":["idena"]}""")
        assertEquals(SocialLists(mapOf(a to Person(a, "Anna", following = true)), setOf("idena")), socialListsFromJson(json))
    }

    @Test
    fun anImportAddsBlocksAndHiddenWords() {
        val current = SocialLists(mapOf(a to Person(a, "Anna", following = true)), emptySet(), setOf("spam"))
        val imported = SocialLists(mapOf(a to Person(a, blocked = true), b to Person(b, blocked = true)), emptySet(), setOf("spam", "scam"))
        val result = mergeLists(current, imported)
        assertEquals(Person(a, "Anna", following = true, blocked = true), result.lists.people[a])
        assertEquals(Person(b, blocked = true), result.lists.people[b])
        assertEquals(setOf("spam", "scam"), result.lists.words)
        assertEquals(1, result.wordsAdded)
    }

    @Test
    fun invalidHiddenWordsAreLeftOutOfAFile() {
        val json = JSONObject("""{"version":3,"people":[],"hiddenWords":["spam","   ","  free   coins "]}""")
        assertEquals(setOf("spam", "free coins"), socialListsFromJson(json)!!.words)
    }

    @Test
    fun invalidTopicsAreLeftOut() {
        val json = JSONObject("""{"version":2,"people":[],"topics":["idena","r/idena","Idena","ab",7,"ok_name"]}""")
        assertEquals(setOf("idena", "ok_name"), socialListsFromJson(json)!!.topics)
    }

    @Test
    fun anImportAddsWithoutRemoving() {
        val current = SocialLists(mapOf(a to Person(a, "Anna"), b to Person(b, following = true)), setOf("idena"))
        val imported = SocialLists(mapOf(a to Person(a, following = true), c to Person(c, "Cleo")), setOf("idena", "flips"))
        val result = mergeLists(current, imported)
        assertEquals(1, result.people.added)
        assertEquals(1, result.people.changed)
        assertEquals(1, result.topicsAdded)
        assertEquals(setOf("idena", "flips"), result.lists.topics)
        assertEquals(Person(a, "Anna", following = true), result.lists.people[a])
        assertEquals(Person(b, following = true), result.lists.people[b])
    }

    @Test
    fun followedPeopleInNameOrder() {
        val people = mapOf(
            a to Person(a, "Zed", following = true),
            b to Person(b, "Anna"),
            c to Person(c, following = true),
        )
        assertEquals(listOf(a, c), followedPeople(people).map { it.address })
    }
}
