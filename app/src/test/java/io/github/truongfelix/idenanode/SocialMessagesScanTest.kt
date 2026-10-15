package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialMessagesScanTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"

    private fun message(height: Long, from: String = a) =
        MessageCall("0xm$height", height, height * 20, 0, from, listOf("p0", "p1"), "0".repeat(64))

    /** A node with a message at each height of [messagesAt]. */
    private class Source(private val messagesAt: Set<Long>, private val batch: Long = 1000) : SocialSource {
        val ranges = ArrayList<LongRange>()
        override fun batchSize() = batch
        override fun head() = 0L
        override fun calls(from: Long, to: Long, version: SocialVersion): BlockCalls {
            ranges += from..to
            return BlockCalls(emptyList(), emptyList(), messagesAt.filter { it in from..to }.map {
                MessageCall("0xm$it", it, it * 20, 0, "0xaaaa000000000000000000000000000000000001", listOf("p0", "p1"), "0".repeat(64))
            })
        }
        override fun authors(version: SocialVersion) = emptyMap<Long, String>()
    }

    @Test
    fun aScanMadeBeforeMessagesReadsThemOnce() {
        val from = CURRENT_VERSION.from
        // A version 4 scan: the whole history read, no message.
        val v4 = SocialCache(low = HISTORY_START, high = from + 2500, posts = emptyList(), authors = emptyMap(), authorsHeight = from + 2500)
            .toJson().apply { remove("messages"); remove("messagesLow"); put("version", 4) }
        var cache = SocialCache.fromJson(v4)!!
        assertTrue(cache.messagesPending)
        assertEquals(from + 2501, cache.messagesLow)

        val source = Source(setOf(from + 10, from + 1500, from + 2400))
        while (cache.messagesPending) cache = scanOlderMessages(cache, source) {}
        assertEquals("the current contract's blocks only, newest first", listOf(from + 1501..from + 2500, from + 501..from + 1500, from..from + 500), source.ranges)
        assertEquals(listOf(from + 10, from + 1500, from + 2400), cache.messages.map { it.height })
        assertEquals(cache, SocialCache.fromJson(JSONObject(cache.toJson().toString())))
        assertFalse(SocialCache.fromJson(JSONObject(cache.toJson().toString()))!!.messagesPending)
    }

    @Test
    fun aNewScanReadsMessagesWithEveryBlock() {
        val cache = SocialCache(low = 12_000_001, high = 12_000_000, posts = emptyList(), authors = emptyMap(), authorsHeight = 0)
        assertFalse(cache.messagesPending)
        val more = cache.plus(BlockCalls(emptyList(), emptyList(), listOf(message(12_000_005))))
        assertEquals(1, more.plus(BlockCalls(emptyList(), emptyList(), listOf(message(12_000_005)))).messages.size)
    }

    @Test
    fun eachCallIsTriedOnceAndOnlyMineAreKept() {
        val plain = plainMessage(a, listOf(b), "hi")
        val mine = MessageCall("0x1", 10, 200, 0, a, listOf("p0", "p1"), keccak256Hex(plain))
        val other = MessageCall("0x2", 11, 220, 0, a, listOf("p0", "p1"), keccak256Hex(plain))
        var asked = 0
        val decrypt = { chunk: List<MessageCall> ->
            asked += chunk.size
            chunk.map { if (it == mine) JSONObject().put("indexes", JSONArray().put(1)).put("message", plain) else null }
        }
        val store = readNewMessages(ChatStore(), listOf(mine, other), b, decrypt)
        assertEquals(listOf("hi"), store.messages.map { it.text })
        assertEquals(setOf("0x1", "0x2"), store.tried)
        assertEquals(store, readNewMessages(store, listOf(mine, other), b, decrypt))
        assertEquals("not asked again", 2, asked)
        assertEquals(store, ChatStore.fromJson(JSONObject(store.toJson().toString())))
    }
}
