package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialKeysTest {
    // 2026-10-15: 0xb12b… wrote to the Galaxy's account; it is no identity, so dna_identity gave no key and the answer
    // could not be encrypted, although its own sendMessage call carries its key.
    private val stranger = "0xb12b1222d0ce70d03bd984a861c825a86ac67898"
    private val me = "0xaaaa000000000000000000000000000000000001"
    private val key = "04" + "ab".repeat(64)
    private val identityKey = "04" + "cd".repeat(64)

    private fun hex(text: String) = "0x" + text.toByteArray().joinToString("") { "%02x".format(it) }

    @Test
    fun aKeyIsFoundForAnAddressThatIsNoIdentity() {
        val asked = ArrayList<Long>()
        val found = findPublicKey(stranger, identityKey = { "" }, scannedKeys = emptyMap(), callHeights = listOf(11_400_100, 11_400_000)) { height ->
            asked += height
            if (height == 11_400_000L) mapOf(stranger to "0x$key") else emptyMap()
        }
        assertEquals("from a block where it called the contract", key, found)
        assertEquals(listOf(11_400_100L, 11_400_000L), asked)
    }

    @Test
    fun theIdentityKeyComesFirstThenTheScannedOnes() {
        val never = { _: Long -> error("no block read") }
        assertEquals(identityKey, findPublicKey(stranger, { "0x$identityKey" }, mapOf(stranger to key), listOf(1), never))
        assertEquals(key, findPublicKey(stranger, { "" }, mapOf(stranger to key), listOf(1), never))
    }

    @Test
    fun noKeyForAnAddressThatNeverSignedAnything() {
        assertNull(findPublicKey(stranger, { "" }, emptyMap(), emptyList()) { emptyMap() })
        var reads = 0
        assertNull("a few blocks at most", findPublicKey(stranger, { "" }, emptyMap(), (1L..10L).toList()) { reads++; emptyMap() })
        assertEquals(KEY_LOOKUP_BLOCKS, reads)
    }

    @Test
    fun keyFormats() {
        assertEquals(key, publicKeyHex("0x" + key.uppercase()))
        assertEquals("33 bytes", "02" + "ab".repeat(32), publicKeyHex("02" + "ab".repeat(32)))
        assertEquals("64 bytes", "ab".repeat(64), publicKeyHex("ab".repeat(64)))
        assertNull(publicKeyHex(""))
        assertNull(publicKeyHex("04" + "zz".repeat(64)))
        assertNull(publicKeyHex("04abcd"))
    }

    @Test
    fun theScanKeepsEachCallersKeyRefusedCallsIncluded() {
        val calls = JSONArray()
            .put(
                JSONObject().put("hash", "0x1").put("height", 10).put("timestamp", 200).put("index", 0).put("from", stranger.uppercase().replace("0X", "0x"))
                    .put("method", "sendMessage").put("args", JSONArray().put(hex("{}"))).put("success", true).put("senderPubKey", "0x$key"),
            )
            .put(
                JSONObject().put("hash", "0x2").put("height", 10).put("timestamp", 200).put("index", 1).put("from", me)
                    .put("method", "makePost").put("args", JSONArray().put(hex("{}"))).put("success", false).put("senderPubKey", "0x$identityKey"),
            )
            .put(JSONObject().put("hash", "0x3").put("height", 10).put("timestamp", 200).put("index", 2).put("from", "0xcccc000000000000000000000000000000000003").put("method", "makePost").put("args", JSONArray().put(hex("{}"))))
        val read = callsToActivity(calls)
        assertEquals(mapOf(stranger to key, me to identityKey), read.keys)

        val cache = SocialCache(low = 11_000_000, high = 11_000_009, posts = emptyList(), authors = emptyMap(), authorsHeight = 11_000_009).plus(read)
        assertEquals(cache.keys, SocialCache.fromJson(JSONObject(cache.toJson().toString()))!!.keys)
    }

    @Test
    fun theBlocksOfAnAddressesCallsNewestFirst() {
        val message = MessageCall("0xm", 30, 600, 0, stranger, listOf("a", "b"), "0".repeat(64))
        val post = PostCall("0xp", 20, 400, 0, stranger, "hi", "", "", false)
        val tip = TipCall("0xt", 25, 500, 0, stranger, "1", "1", "1")
        val other = PostCall("0xo", 40, 800, 0, me, "x", "", "", false)
        val cache = SocialCache(low = 1, high = 50, posts = listOf(post, other), authors = emptyMap(), authorsHeight = 50, tips = listOf(tip), messages = listOf(message))
        assertEquals(listOf(30L, 25L, 20L), cache.callHeightsOf(stranger))
    }

    @Test
    fun aStrangersConversationStaysARequestUntilAMessageOfMineIsInIt() {
        val theirs = ChatMessage("0x1", 10, 200, 0, stranger, listOf(stranger, me), "hello")
        // An answer that failed sends nothing: the conversation still holds only theirs, nobody is marked.
        assertTrue(conversations(listOf(theirs), me, emptyMap()).single().isRequest)
        val mine = ChatMessage("0x2", 12, 240, 0, me, listOf(me, stranger), "hi")
        assertTrue("answered", !conversations(listOf(theirs, mine), me, emptyMap()).single().isRequest)
    }
}
