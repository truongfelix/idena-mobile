package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialUnreadTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"
    private val group = "0123456789abcdef0123456789abcdef"

    private fun msg(from: String, to: String, height: Long, index: Int = 0) =
        ChatMessage("0x$height-$index", height, height * 20, index, from, listOf(from, to), "x")

    private fun withB(vararg messages: ChatMessage) = conversations(messages.toList(), a, emptyMap()).single()

    @Test
    fun answeringReadsWhatCameBefore() {
        val conversation = withB(msg(b, a, 10), msg(b, a, 11), msg(a, b, 12), msg(b, a, 13), msg(b, a, 14))
        assertEquals(listOf(13L, 14L), unreadMessages(conversation, a, null).map { it.height })
        assertEquals("a read point after my answer", listOf(14L), unreadMessages(conversation, a, 13L to 0).map { it.height })
        assertEquals("a read point before my answer", listOf(13L, 14L), unreadMessages(conversation, a, 10L to 0).map { it.height })
        assertEquals("never answered", 3, unreadCount(withB(msg(b, a, 10), msg(b, a, 11), msg(b, a, 12)), a, null))
        assertEquals("only mine", 0, unreadCount(withB(msg(a, b, 10)), a, null))
    }

    @Test
    fun anAnswerInTheSameBlockReadsOnlyWhatCameBeforeIt() {
        val conversation = withB(msg(b, a, 10, 0), msg(a, b, 10, 1), msg(b, a, 10, 2))
        assertEquals(listOf(2), unreadMessages(conversation, a, null).map { it.index })
    }

    @Test
    fun messagesBeforeTheFirstLoadAreRead() {
        val conversation = withB(msg(b, a, 10), msg(b, a, 20), msg(b, a, 30))
        assertEquals("from its block time on", listOf(20L, 30L), unreadMessages(conversation, a, null, 20 * 20).map { it.height })
        assertEquals("no limit", 3, unreadCount(conversation, a, null))
        assertEquals("a read point still counts", listOf(30L), unreadMessages(conversation, a, 20L to 0, 10 * 20).map { it.height })
        val store = ChatStore(messages = conversation.messages, readBefore = 25 * 20)
        assertEquals(1, store.unreadCount(conversation, a))
    }

    @Test
    fun aKeyMessageOfMineReadsTheGroupBeforeIt() {
        val members = listOf(a, b, c)
        val parts = members.map { "part-for-$it" }
        fun key(height: Long) = acceptGroupKey(
            MessageCall("0xk$height", height, height * 20, 0, a, parts, groupMessageHash(parts)),
            groupKeyPlain(a, group, (height / 10).toInt(), newGroupKey(), "Family", members), listOf(0), a,
        )!!
        val first = key(10)
        val fromB = ChatMessage("0xb", 11, 220, 0, b, members, "hi", group = first.conversation)
        val second = key(20)
        val store = ChatStore(messages = listOf(fromB), groupKeys = listOf(first, second))
        val conversation = conversations(store.messages, a, emptyMap(), store.groupKeys).single()
        assertEquals("my renewed key comes after the message", 0, store.unreadCount(conversation, a))
    }

    @Test
    fun aFirstStoreReadsWhatIsOlderThanThreeDays() {
        val now = 1_800_000_000L
        assertEquals(now - 3 * 24 * 3600, firstStore(now).readBefore)
        val saved = ChatStore.fromJson(JSONObject(firstStore(now).toJson().toString()))
        assertEquals(now - 3 * 24 * 3600, saved.readBefore)
    }

    @Test
    fun aFileOfAnEarlierBuildKeepsItsReadPoints() {
        // As the build before B-141 wrote it: no readBefore.
        val earlier = JSONObject("""{"tried":[],"messages":[],"seen":{"k":[10,0]},"groupKeys":[],"groupTried":[],"hiddenChats":[],"groupNames":{},"memberNames":{}}""")
        val store = ChatStore.fromJson(earlier)
        assertNull(store.readBefore)
        assertEquals(mapOf("k" to (10L to 0)), store.seen)
        assertEquals("written back without it", false, store.toJson().has("readBefore"))
    }
}
