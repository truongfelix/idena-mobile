package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialGroupsTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"
    private val group = "0123456789abcdef0123456789abcdef"

    /** A key message's call: one part per member (their text stands for the node's encryption). */
    private fun keyCall(hash: String, height: Long, from: String, members: List<String>, hashOf: (List<String>) -> String = ::groupMessageHash): MessageCall {
        val parts = members.map { "part-for-$it" }
        return MessageCall(hash, height, height * 20, 0, from, parts, hashOf(parts))
    }

    private fun keyOf(hash: String, height: Long, keyId: Int, members: List<String>, key: String = newGroupKey(), admin: String = a, me: String = members.last()): GroupKey {
        val plain = groupKeyPlain(admin, group, keyId, key, "Family", members)
        return acceptGroupKey(keyCall(hash, height, admin, members), plain, listOf(members.indexOf(me)), me)!!
    }

    private fun groupCall(hash: String, height: Long, from: String, key: GroupKey, text: String = "hi", keyId: Int = key.keyId): MessageCall {
        val parts = listOf(encryptGroupPart(groupMessagePlain(key.admin, key.group, keyId, text), key.key))
        return MessageCall(hash, height, height * 20, 0, from, parts, groupMessageHash(parts))
    }

    @Test
    fun aKeyMessageIsReadByEachMemberFromTheirOwnPart() {
        val members = listOf(a, b, c)
        val key = newGroupKey()
        val plain = groupKeyPlain(a, group, 0, key, " Family ", members)
        val call = keyCall("0xk", 10, a, members)
        val read = acceptGroupKey(call, plain, listOf(1), b)!!
        assertEquals(listOf(a, b, c), read.members)
        assertEquals("Family", read.name)
        assertEquals(key, read.key)
        assertEquals(groupConversationKey(a, group), read.conversation)
        assertEquals(read, GroupKey.fromJson(JSONObject(read.toJson().toString())))
        assertNotNull("the admin's own copy", acceptGroupKey(call, plain, listOf(0), a))

        assertNull("a hash of the text, as the website writes it", acceptGroupKey(keyCall("0xk", 10, a, members) { keccak256Hex(plain) }, plain, listOf(1), b))
        assertNull("sent by another than the admin", acceptGroupKey(keyCall("0xk", 10, c, members), plain, listOf(1), b))
        assertNull("not my part", acceptGroupKey(call, plain, listOf(2), b))
        assertNull("two parts opened", acceptGroupKey(call, plain, listOf(1, 2), b))
        assertNull("not a member", acceptGroupKey(call, plain, listOf(1), "0xdddd000000000000000000000000000000000004"))
        assertNull("parts and members differ", acceptGroupKey(keyCall("0xk", 10, a, listOf(a, b)), plain, listOf(1), b))
        val twice = groupKeyPlain(a, group, 0, key, "F", listOf(a, b, b))
        assertNull("a member twice", acceptGroupKey(keyCall("0xk", 10, a, listOf(a, b, b)), twice, listOf(1), b))
        val adminNotFirst = groupKeyPlain(a, group, 0, key, "F", listOf(b, a, c))
        assertNull("the admin not first", acceptGroupKey(call, adminNotFirst, listOf(0), b))
        val shortKey = groupKeyPlain(a, group, 0, "AAAA", "F", members)
        assertNull("a key of 3 bytes", acceptGroupKey(call, shortKey, listOf(1), b))
        val many = listOf(a) + (1..MAX_GROUP_MEMBERS + 1).map { "0x" + "%040x".format(it) }
        val tooMany = groupKeyPlain(a, group, 0, key, "F", many)
        assertNull("more than 64 members besides the admin", acceptGroupKey(keyCall("0xk", 10, a, many), tooMany, listOf(1), many[1]))
        val most = many.dropLast(1)
        assertNotNull("64 members besides the admin", acceptGroupKey(keyCall("0xk", 10, a, most), groupKeyPlain(a, group, 0, key, "F", most), listOf(1), most[1]))
        assertNull("idena.social's plain message", acceptGroupKey(call, plainMessage(a, listOf(b, c), "hi"), listOf(1), b))
    }

    @Test
    fun aGroupMessageOpensOnlyWithItsKeyAndFromAMember() {
        val key = keyOf("0xk", 10, 0, listOf(a, b, c))
        val message = acceptGroupMessage(groupCall("0xm", 11, b, key), key)!!
        assertEquals("hi", message.text)
        assertEquals(key.conversation, message.conversation)
        assertEquals(listOf(a, b, c), message.participants)
        assertEquals(message, ChatMessage.fromJson(JSONObject(message.toJson().toString())))

        val other = keyOf("0xk2", 10, 0, listOf(a, b, c))
        assertNull("another key", acceptGroupMessage(groupCall("0xm", 11, b, key), other))
        assertNull("from someone not in the group", acceptGroupMessage(groupCall("0xm", 11, "0xdddd000000000000000000000000000000000004", key), key))
        assertNull("another key id", acceptGroupMessage(groupCall("0xm", 11, b, key, keyId = 1), key))
        assertNull("no text", acceptGroupMessage(groupCall("0xm", 11, b, key, text = ""), key))
        val call = groupCall("0xm", 11, b, key)
        assertNull("a hash that is not the part's", acceptGroupMessage(call.copy(messageHash = "0".repeat(64)), key))
        assertNull("two parts", acceptGroupMessage(call.copy(parts = call.parts + call.parts, messageHash = groupMessageHash(call.parts + call.parts)), key))
    }

    @Test
    fun onePartIsKeptByTheScanTwoAreLeftForTheWebsite() {
        val part = encryptGroupPart("x", newGroupKey())
        val arg = sendMessageArgument(listOf(part), groupMessageHash(listOf(part)))
        val call = MessageCall.parse("0xm", 1, 20, 0, a, bytesToHex(arg.toByteArray()))
        assertEquals(listOf(part), call?.parts)
        assertNull("no part", MessageCall.parse("0xm", 1, 20, 0, a, bytesToHex(sendMessageArgument(emptyList(), "0".repeat(64)).toByteArray())))
    }

    @Test
    fun aRemovedMemberCountsOnlyWithinTheGraceAfterTheChange() {
        val k0 = keyOf("0xk0", 100, 0, listOf(a, b, c), me = b)
        val k1 = keyOf("0xk1", 200, 1, listOf(a, b), me = b)
        val before = acceptGroupMessage(groupCall("0xc1", 150, c, k0), k0)!!
        val inGrace = acceptGroupMessage(groupCall("0xc2", 200 + GROUP_GRACE_BLOCKS, c, k0), k0)!!
        val after = acceptGroupMessage(groupCall("0xc3", 201 + GROUP_GRACE_BLOCKS, c, k0), k0)!!
        val fromB = acceptGroupMessage(groupCall("0xb1", 300, b, k1), k1)!!
        val (lines, state) = groupLines(listOf(k1, k0), listOf(after, fromB, inGrace, before))
        assertEquals(listOf("0xk0", "0xc1", "0xk1", "0xc2", "0xb1"), lines.map { it.hash })
        assertEquals(listOf(a, b), state!!.members)
    }

    @Test
    fun aKeyOfAnotherAdminMakesAnotherGroupAndAnOlderKeyIsIgnored() {
        val k0 = keyOf("0xk0", 100, 0, listOf(a, b, c), me = b)
        val k2 = keyOf("0xk2", 110, 2, listOf(a, b, c), me = b)
        val older = keyOf("0xk1", 120, 1, listOf(a, b), me = b)
        val (lines, state) = groupLines(listOf(k0, k2, older), emptyList())
        assertEquals(listOf("0xk0", "0xk2"), lines.map { it.hash })
        assertEquals(k2, state!!.key)
        // The same group id from another admin: their own group, never a change of this one.
        val forged = keyOf("0xkx", 130, 3, listOf(c, b), admin = c, me = b)
        val groups = conversations(emptyList(), b, emptyMap(), listOf(k0, k2, older, forged))
        assertEquals(setOf(k0.conversation to listOf(a, c), forged.conversation to listOf(c)), groups.map { it.key to it.others }.toSet())
    }

    @Test
    fun aNewKeyAloneKeepsTheMembersAndIsALineOfItsOwn() {
        val k0 = keyOf("0xk0", 100, 0, listOf(a, b, c), me = b)
        val renewed = keyOf("0xk1", 110, 1, listOf(a, b, c), me = b)
        val conversation = conversations(emptyList(), b, emptyMap(), listOf(k0, renewed)).single()
        assertEquals(renewed, conversation.group!!.key)
        assertEquals(listOf(a, c), conversation.others)
        assertEquals("0xk0", previousKeyLine(conversation, conversation.last)?.hash)
        assertNull(previousKeyLine(conversation, conversation.messages.first()))
    }

    @Test
    fun aMessageReadBeforeItsKeyArrivesIsReadWithTheKey() {
        val members = listOf(a, b, c)
        val key = newGroupKey()
        val plain = groupKeyPlain(a, group, 0, key, "Family", members)
        val keyCall = keyCall("0xk", 10, a, members)
        val k = acceptGroupKey(keyCall, plain, listOf(1), b)!!
        val message = groupCall("0xm", 12, c, k, text = "early")
        var asked = 0
        val decrypt = { chunk: List<MessageCall> ->
            asked += chunk.size
            chunk.map { if (it == keyCall) JSONObject().put("indexes", JSONArray().put(1)).put("message", plain) else null }
        }
        // The message first, alone, while the account holds another group's key only: it does not open it yet.
        val another = keyOf("0xo", 5, 0, listOf(c, b), admin = c, me = b)
        var store = readNewMessages(ChatStore(groupKeys = listOf(another)), listOf(message), b, decrypt)
        assertTrue(store.messages.isEmpty())
        assertEquals("a one-part call never goes to the node", 0, asked)
        store = readNewMessages(store, listOf(message, keyCall), b, decrypt)
        assertEquals(listOf("early"), store.messages.map { it.text })
        assertEquals(2, store.groupKeys.size)
        assertEquals(store, readNewMessages(store, listOf(message, keyCall), b, decrypt))
        assertEquals(store, ChatStore.fromJson(JSONObject(store.toJson().toString())))

        val conversation = conversations(store.messages, b, emptyMap(), store.groupKeys).single { it.key == k.conversation }
        assertEquals(k.conversation, conversation.key)
        assertEquals(listOf(a, c), conversation.others)
        assertEquals(listOf(ChatMessage.KEY, ""), conversation.messages.map { it.kind })
        assertTrue("a stranger's group waits in Requests", conversation.isRequest)
        assertFalse(conversations(store.messages, b, mapOf(a to Person(a, message = true)), store.groupKeys).single { it.key == k.conversation }.isRequest)
        assertEquals("being added counts as unread", 2, unreadCount(conversation, b, null))
    }

    @Test
    fun aBlockedMemberIsHiddenInTheGroupABlockedAdminHidesIt() {
        val k = keyOf("0xk", 10, 0, listOf(a, b, c), me = b)
        val fromC = acceptGroupMessage(groupCall("0xc", 11, c, k), k)!!
        val fromA = acceptGroupMessage(groupCall("0xa", 12, a, k), k)!!
        val all = conversations(listOf(fromC, fromA), b, emptyMap(), listOf(k))
        assertEquals(listOf("0xk", "0xa"), visibleConversations(all, setOf(c)).single().messages.map { it.hash })
        assertTrue(visibleConversations(all, setOf(a)).isEmpty())
    }

    @Test
    fun aStoreOfTheAppBeforeGroupsTriesItsFailedCallsAgain() {
        val members = listOf(a, b, c)
        val plain = groupKeyPlain(a, group, 0, newGroupKey(), "Family", members)
        val keyCall = keyCall("0xk", 10, a, members)
        val oneToOne = MessageCall("0x1", 9, 180, 0, a, listOf("p0", "p1"), keccak256Hex(plainMessage(a, listOf(b), "hi")))
        val decrypt = { chunk: List<MessageCall> ->
            chunk.map {
                when (it) {
                    keyCall -> JSONObject().put("indexes", JSONArray().put(1)).put("message", plain)
                    oneToOne -> JSONObject().put("indexes", JSONArray().put(1)).put("message", plainMessage(a, listOf(b), "hi"))
                    else -> null
                }
            }
        }
        // As the app before groups saved it: both tried, the key message read as nothing.
        val old = readNewMessages(ChatStore(), listOf(oneToOne), b, decrypt).let { it.copy(tried = it.tried + "0xk") }
            .toJson().apply { remove("groupKeys"); remove("groupTried"); remove("leftGroups"); remove("groupNames"); remove("memberNames") }
        val store = ChatStore.fromJson(old)
        assertEquals(setOf("0x1"), store.tried)
        val next = readNewMessages(store, listOf(oneToOne, keyCall), b, decrypt)
        assertEquals(listOf("0xk"), next.groupKeys.map { it.hash })
        assertEquals("the message read once", listOf("hi"), next.messages.map { it.text })
    }

    @Test
    fun whatIsOfThisPhoneOnlyIsKept() {
        val key = groupConversationKey(a, group)
        val store = ChatStore(leftGroups = setOf(key), groupNames = mapOf(key to "Us"), memberNames = mapOf(key to mapOf(c to "Grandma")))
        assertEquals(store, ChatStore.fromJson(JSONObject(store.toJson().toString())))
        val k = keyOf("0xk", 10, 0, listOf(a, b, c), me = b)
        assertEquals("Us", groupTitle(GroupState(k), b, emptyMap(), "Us"))
        assertEquals("Family", groupTitle(GroupState(k), b, emptyMap()))
    }

    /** A node with a one-part message at each height of [messagesAt]. */
    private class Source(private val messagesAt: Set<Long>, private val batch: Long = 1000) : SocialSource {
        val ranges = ArrayList<LongRange>()
        override fun batchSize() = batch
        override fun head() = 0L
        override fun calls(from: Long, to: Long, version: SocialVersion): BlockCalls {
            ranges += from..to
            return BlockCalls(emptyList(), emptyList(), messagesAt.filter { it in from..to }.map {
                MessageCall("0xm$it", it, it * 20, 0, "0xaaaa000000000000000000000000000000000001", listOf("p0"), "0".repeat(64))
            })
        }
        override fun authors(version: SocialVersion) = emptyMap<Long, String>()
    }

    @Test
    fun aVersion5ScanReadsTheBlocksSinceGroupsAgainThenGoesOnWhereItStood() {
        val floor = SocialCache.GROUPS_FROM
        val high = floor + 2500
        // Version 5, its history scan down to [low] with the messages read down to [stood], still going.
        val low = CURRENT_VERSION.from
        val stood = floor - 3000
        val v5 = SocialCache(low = low, high = high, posts = emptyList(), authors = emptyMap(), authorsHeight = high, messagesLow = stood)
            .toJson().apply { put("version", 5); remove("messagesFloor"); remove("messagesResume") }
        var cache = SocialCache.fromJson(v5)!!
        assertEquals(high + 1, cache.messagesLow)
        val source = Source(setOf(floor + 10, floor + 2400, floor - 10))
        while (cache.messagesLow > floor) cache = scanOlderMessages(cache, source) {}
        assertEquals("the blocks since groups, newest first", listOf(floor + 1501..high, floor + 501..floor + 1500, floor..floor + 500), source.ranges)
        assertEquals(listOf(floor + 10, floor + 2400), cache.messages.map { it.height }.sorted())
        assertEquals("back where it stood", stood, cache.messagesLow)
        assertEquals(0L, cache.messagesFloor)
        assertTrue("its own pass goes on", cache.messagesPending)
        cache = scanOlderMessages(cache, source) {}
        assertEquals(stood - 1000..stood - 1, source.ranges.last())
        assertEquals(cache, SocialCache.fromJson(JSONObject(cache.toJson().toString())))

        // A version 5 scan that had read everything: only the blocks since groups, then done.
        val done = SocialCache(low = low, high = high, posts = emptyList(), authors = emptyMap(), authorsHeight = high, messagesLow = low)
            .toJson().apply { put("version", 5); remove("messagesFloor"); remove("messagesResume") }
        var again = SocialCache.fromJson(done)!!
        while (again.messagesPending) again = scanOlderMessages(again, Source(emptySet())) {}
        assertEquals(low, again.messagesLow)
        // A version 6 scan stays as it is.
        assertEquals(again, SocialCache.fromJson(JSONObject(again.toJson().toString())))
    }
}
