package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialNewChatTest {
    private val me = "0x0000000000000000000000000000000000000001"
    private val alice = "0xaaaa000000000000000000000000000000000002"
    private val bob = "0xbbbb000000000000000000000000000000000003"
    private val carol = "0xcccc000000000000000000000000000000000004"
    private val dave = "0xdddd000000000000000000000000000000000005"

    private val people = mapOf(
        alice to Person(alice, name = "Alice", following = true),
        bob to Person(bob, name = "Bob"),
        carol to Person(carol, following = true),
    )

    private fun chat(vararg others: String) = Conversation(conversationKey(others.toList() + me), others.toList(), emptyList(), isRequest = false)

    @Test
    fun aBlankQueryListsTheLatestConversationsThenThePeople() {
        // dave is only in a conversation; carol is in one and in the list: once, at her conversation's place.
        val conversations = listOf(chat(dave), chat(carol), chat(alice, bob))
        assertEquals(listOf(dave, carol, alice, bob), chatCandidates("", people, conversations, me))
    }

    @Test
    fun aQueryMatchesANameOrAnAddressInAnyCase() {
        assertEquals(listOf(alice), chatCandidates("ALI", people, emptyList(), me))
        assertEquals(listOf(carol), chatCandidates("0xCCCC", people, emptyList(), me))
        assertEquals(emptyList<String>(), chatCandidates("zoe", people, emptyList(), me))
    }

    @Test
    fun aWholeAddressTypedComesFirstEvenWhenUnknown() {
        val stranger = "0xEEEE000000000000000000000000000000000006"
        assertEquals(listOf(stranger.lowercase()), chatCandidates("  $stranger ", people, emptyList(), me))
        assertEquals(listOf(alice), chatCandidates(alice.uppercase().replace("0X", "0x"), people, emptyList(), me))
    }

    @Test
    fun theAccountItselfIsNeverOffered() {
        val withMe = people + (me to Person(me, name = "Me"))
        assertEquals(emptyList<String>(), chatCandidates(me, withMe, emptyList(), me))
        assertEquals(listOf(alice, bob, carol), chatCandidates("", withMe, emptyList(), me).filter { it != me })
        assertEquals(false, me in chatCandidates("", withMe, emptyList(), me))
    }

    @Test
    fun aConversationIsFoundByAnyOfItsPeople() {
        val names = peopleNames(people)
        val conversations = listOf(chat(dave), chat(alice, bob), chat(carol))
        assertEquals(listOf(chat(alice, bob)), findConversations(conversations, "bob", names))
        assertEquals(listOf(chat(dave)), findConversations(conversations, "0xdddd", names))
        assertEquals(conversations, findConversations(conversations, " ", names))
    }
}
