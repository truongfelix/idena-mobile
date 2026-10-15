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
    fun theFileHoldsNamesPeopleAndTopics() {
        val lists = SocialLists(
            mapOf(a to Person(a, "Anna", following = true), b to Person(b, "Bob")),
            setOf("idena", "flips"),
        )
        val json = socialListsToJson(lists)
        assertEquals(2, json.getInt("version"))
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
