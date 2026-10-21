package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The removal notice of a group (ruling 2026-10-21, notes/group-chat-design.md in the workspace). */
class SocialGroupNoticeTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"
    private val d = "0xdddd000000000000000000000000000000000004"
    private val group = "0123456789abcdef0123456789abcdef"
    private val names = mapOf(a to "Ann")

    private fun keyOf(hash: String, height: Long, keyId: Int, members: List<String>, me: String, key: String = newGroupKey()): GroupKey {
        val parts = members.map { "part-for-$it" }
        val call = MessageCall(hash, height, height * 20, 0, a, parts, groupMessageHash(parts))
        return acceptGroupKey(call, groupKeyPlain(a, group, keyId, key, "Family", members), listOf(members.indexOf(me)), me)!!
    }

    private fun oneCall(hash: String, height: Long, from: String, plain: String, key: GroupKey): MessageCall {
        val parts = listOf(encryptGroupPart(plain, key.key))
        return MessageCall(hash, height, height * 20, 0, from, parts, groupMessageHash(parts))
    }

    private fun noticeCall(hash: String, height: Long, key: GroupKey, removed: List<String>, from: String = a, keyId: Int = key.keyId) =
        oneCall(hash, height, from, groupNoticePlain(key.admin, key.group, keyId, removed), key)

    @Test
    fun aNoticeCountsOnlyFromTheAdminNamingOthersOfItsKey() {
        val k0 = keyOf("0xk0", 100, 0, listOf(a, b, c), me = c)
        val notice = acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(c)), k0)!!
        assertEquals(ChatMessage.REMOVED, notice.kind)
        assertEquals(listOf(c), notice.removed)
        assertEquals(k0.conversation, notice.group)
        assertEquals(notice, ChatMessage.fromJson(JSONObject(notice.toJson().toString())))

        assertNull("from a member, not the admin", acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(c), from = b), k0))
        assertNull("naming the admin", acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(a, c)), k0))
        assertNull("naming someone not in the key", acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(d)), k0))
        assertNull("naming nobody", acceptGroupMessage(noticeCall("0xn", 120, k0, emptyList()), k0))
        assertNull("naming someone twice", acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(c, c)), k0))
        assertNull("another key id", acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(c), keyId = 1), k0))
        val other = keyOf("0xo", 100, 0, listOf(a, b, c), me = c)
        assertNull("another key", acceptGroupMessage(noticeCall("0xn", 120, k0, listOf(c)), other))
        val plain = JSONObject(groupNoticePlain(a, group, 0, listOf(c))).put("type", "left").toString()
        assertNull("another type", acceptGroupMessage(oneCall("0xn", 120, a, plain, k0), k0))
    }

    @Test
    fun theRemovedMemberReadsTheNoticeTheOthersReadTheChange() {
        // c holds only the key before the change; b holds both.
        val k0c = keyOf("0xk0", 100, 0, listOf(a, b, c), me = c)
        val k0b = keyOf("0xk0", 100, 0, listOf(a, b, c), me = b, key = k0c.key)
        val k1b = keyOf("0xk1", 110, 1, listOf(a, b), me = b)
        val call = noticeCall("0xn", 111, k0c, listOf(c))

        val forC = conversations(listOfNotNull(acceptGroupMessage(call, k0c)), c, emptyMap(), listOf(k0c)).single()
        assertEquals(listOf("0xk0", "0xn"), forC.messages.map { it.hash })
        assertTrue(forC.removedMe)
        assertEquals("Ann removed you from the group", groupEventText(forC.last, previousKeyLine(forC, forC.last), c, names))
        assertEquals("a notice is news", 2, unreadCount(forC, c, null))

        val forB = conversations(listOfNotNull(acceptGroupMessage(call, k0b)), b, emptyMap(), listOf(k0b, k1b)).single()
        assertEquals("the others do not see it", listOf("0xk0", "0xk1"), forB.messages.map { it.hash })
        assertFalse(forB.removedMe)

        // Added again: the newer key names c, the notice before it stays a line.
        val k2c = keyOf("0xk2", 130, 2, listOf(a, b, c), me = c)
        val again = conversations(listOfNotNull(acceptGroupMessage(call, k0c)), c, emptyMap(), listOf(k0c, k2c)).single()
        assertEquals(listOf("0xk0", "0xn", "0xk2"), again.messages.map { it.hash })
        assertFalse(again.removedMe)
    }

    @Test
    fun noticesAreKeptApartFromTheMessages() {
        val k0 = keyOf("0xk0", 100, 0, listOf(a, b, c), me = c)
        val message = oneCall("0xm", 105, b, groupMessagePlain(a, group, 0, "hi"), k0)
        val notice = noticeCall("0xn", 111, k0, listOf(c))
        val store = readGroupMessages(ChatStore(groupKeys = listOf(k0)), listOf(message, notice), emptyList())
        assertEquals(listOf("0xm"), store.messages.map { it.hash })
        assertEquals(listOf("0xn"), store.groupNotices.map { it.hash })
        assertEquals(listOf("0xm", "0xn"), store.chatLines.map { it.hash })
        assertEquals(store, ChatStore.fromJson(JSONObject(store.toJson().toString())))
        // The release before notices reads "messages" only: no empty message there.
        val messages = store.toJson().getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals(store, readGroupMessages(store, listOf(message, notice), emptyList()))
        assertEquals("the other reading's notice merged once", store, withOpened(store, store))
    }

    @Test
    fun aStoreOfTheBuildBeforeNoticesTriesItsOnePartCallsAgain() {
        val k0 = keyOf("0xk0", 100, 0, listOf(a, b, c), me = c)
        val notice = noticeCall("0xn", 111, k0, listOf(c))
        // As v0.2.1 saved it: the notice tried with every key and dropped, no list of notices.
        val old = ChatStore(groupKeys = listOf(k0), groupTried = setOf("0xn")).toJson().apply { remove("groupNotices") }
        val store = ChatStore.fromJson(old)
        assertTrue(store.groupTried.isEmpty())
        val next = readNewMessages(store, listOf(notice), c) { chunk -> chunk.map { null } }
        assertEquals(listOf("0xn"), next.groupNotices.map { it.hash })
        assertEquals(setOf("0xn"), next.groupTried)
        // Saved by this build, the calls tried stay tried.
        val saved = ChatStore.fromJson(JSONObject(ChatStore(groupKeys = listOf(k0), groupTried = setOf("0xn")).toJson().toString()))
        assertEquals(setOf("0xn"), saved.groupTried)
        assertEquals(JSONArray().toString(), saved.toJson().getJSONArray("groupNotices").toString())
    }
}
