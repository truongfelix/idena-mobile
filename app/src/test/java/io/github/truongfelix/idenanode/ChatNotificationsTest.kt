package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationsTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"
    private val group = "0123456789abcdef0123456789abcdef"

    private fun direct(hash: String, height: Long, from: String, to: String, text: String) =
        ChatMessage(hash, height, height * 20, 0, from, listOf(from, to), text)

    private fun keyFor(me: String, hash: String = "0xk", height: Long = 10, keyId: Int = 0, members: List<String> = listOf(a, b, c), name: String = "Family"): GroupKey {
        val parts = members.map { "part-for-$it" }
        val call = MessageCall(hash, height, height * 20, 0, a, parts, groupMessageHash(parts))
        return acceptGroupKey(call, groupKeyPlain(a, group, keyId, newGroupKey(), name, members), listOf(members.indexOf(me)), me)!!
    }

    private fun inGroup(key: GroupKey, hash: String, height: Long, from: String, text: String) =
        ChatMessage(hash, height, height * 20, 0, from, key.members, text, group = key.conversation)

    private val known = mapOf(a to Person(a, name = "Alice", message = true), c to Person(c, name = "Carol", message = true))

    @Test
    fun aConversationNotifiesItsUnreadMessagesUnderItsWritersName() {
        val store = ChatStore(messages = listOf(direct("0x1", 11, a, b, "hello"), direct("0x2", 12, b, a, "mine"), direct("0x3", 13, a, b, "again")))
        val notice = chatNotices(store, b, known).single()
        assertEquals("Alice", notice.title)
        assertEquals(listOf("Alice" to "hello", "Alice" to "again"), notice.lines)
        assertEquals(2, notice.count)
        assertEquals(13L to 0, notice.newest)
        val read = store.copy(seen = mapOf(notice.key to (11L to 0)))
        assertEquals(listOf("Alice" to "again"), chatNotices(read, b, known).single().lines)
        assertTrue("all read", chatNotices(store.copy(seen = mapOf(notice.key to (13L to 0))), b, known).isEmpty())
    }

    @Test
    fun aGroupNotifiesUnderItsNameOnThePhoneAndItsMembersNamesInIt() {
        val key = keyFor(b)
        val store = ChatStore(
            messages = listOf(inGroup(key, "0x1", 11, c, "hi all")),
            groupKeys = listOf(key),
            groupNames = mapOf(key.conversation to "Home"),
            memberNames = mapOf(key.conversation to mapOf(c to "Grandma")),
        )
        val notice = chatNotices(store, b, known).single()
        assertEquals("Home", notice.title)
        assertTrue(notice.isGroup)
        assertEquals(listOf("Alice" to "Alice added you to the group “Family”", "Grandma" to "hi all"), notice.lines)
        assertEquals("the admin's name without one on the phone", "Family", chatNotices(store.copy(groupNames = emptyMap()), b, known).single().title)
    }

    @Test
    fun noNotificationForAHiddenConversationARequestOrABlockedAccount() {
        val key = keyFor(b)
        val messages = listOf(direct("0x1", 11, a, b, "hello"), inGroup(key, "0x2", 12, c, "hi all"))
        val store = ChatStore(messages = messages, groupKeys = listOf(key))
        assertEquals(2, chatNotices(store, b, known).size)
        val direct = conversationKey(listOf(a, b))
        assertTrue("both hidden", chatNotices(store.copy(hiddenChats = setOf(direct, key.conversation)), b, known).isEmpty())
        assertEquals(listOf(key.conversation), chatNotices(store.copy(hiddenChats = setOf(direct)), b, known).map { it.key })
        assertTrue("strangers: Requests", chatNotices(store, b, emptyMap()).isEmpty())
        val blocked = known + (a to known.getValue(a).copy(blocked = true))
        assertTrue("the admin blocked: neither", chatNotices(store, b, blocked).isEmpty())
    }

    @Test
    fun onlyANewerMessageSounds() {
        val n1 = ChatNotice("k1", "A", false, listOf("A" to "x"), listOf(1), 1, 10L to 0)
        val n2 = ChatNotice("k2", "B", false, listOf("B" to "y"), listOf(1), 1, 20L to 1)
        assertEquals(setOf("k1", "k2"), noticesToSound(listOf(n1, n2), emptyMap()))
        assertEquals(setOf("k2"), noticesToSound(listOf(n1, n2), mapOf("k1" to (10L to 0), "k2" to (20L to 0))))
        assertEquals(emptySet<String>(), noticesToSound(listOf(n1, n2), mapOf("k1" to (10L to 0), "k2" to (20L to 1))))
    }

    @Test
    fun whatTheServiceOpenedIsNotOpenedTwice() {
        val key = keyFor(b)
        val saved = ChatStore(tried = setOf("0x1"), messages = listOf(direct("0x1", 11, a, b, "hello")), seen = mapOf("x" to (1L to 0)))
        val opened = saved.copy(tried = setOf("0x1", "0xk"), groupKeys = listOf(key), groupTried = setOf("0x9"), seen = emptyMap())
        val merged = withOpened(saved, opened)
        assertEquals(setOf("0x1", "0xk"), merged.tried)
        assertEquals(1, merged.messages.size)
        assertEquals(listOf(key), merged.groupKeys)
        assertEquals(setOf("0x9"), merged.groupTried)
        assertEquals("the app's own: what was seen", saved.seen, merged.seen)
        assertEquals(saved, withOpened(saved, null))
    }
}
