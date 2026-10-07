package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** idena.social's older contracts: their call formats, numbering and place in the feed (SocialVersions.kt). */
class SocialOlderTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val v1 = versionNamed("v1")
    private val v5 = versionNamed("v5")
    private val v9 = versionNamed("v9")
    private val v11 = versionNamed("v11")

    private fun hex(text: String) = "0x" + text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun post(hash: String, author: String, message: String, height: Long, replyTo: String = "", channel: String = "", version: SocialVersion = CURRENT_VERSION) =
        PostCall(hash, height, height, 0, author, message, replyTo, channel, hasMedia = false, version = if (version.isCurrent) "" else version.name)

    @Test
    fun theVersionsFollowEachOther() {
        assertEquals(listOf("v12", "v11", "v10", "v9", "v5", "v1"), SOCIAL_VERSIONS.map { it.name })
        SOCIAL_VERSIONS.zipWithNext().forEach { (newer, older) -> assertEquals(newer.from, older.to) }
        assertEquals(10_135_621L, HISTORY_START)
        assertEquals(CURRENT_VERSION, versionAt(SOCIAL_FIRST_BLOCK))
        assertEquals(v11, versionAt(SOCIAL_FIRST_BLOCK - 1))
        assertEquals(v1, versionAt(HISTORY_START))
        assertNull(versionAt(HISTORY_START - 1))
        assertEquals(CURRENT_VERSION, versionNamed(""))
    }

    @Test
    fun v1PostsNeedAMessageAndTheirRepliesMayNameHex() {
        val reply = PostCall.parse("0x1", 10_200_000, 1, 0, a, hex("""{"message":"hi","replyToPostId":"0x2e000000000000000000000000000000","mediaType":"image/png"}"""), v1)!!
        assertEquals("46", reply.replyTo)
        assertEquals("v1", reply.version)
        assertTrue(reply.isOlder)
        assertFalse("v1's media fields were never used", reply.hasMedia)
        assertEquals("46", PostCall.olderReplyTo("0x2e"))
        assertEquals("12", PostCall.olderReplyTo("12"))
        assertNull("the contract refuses an empty message", PostCall.parse("0x2", 10_200_000, 1, 0, a, hex("""{"message":""}"""), v1))
        val current = PostCall.parse("0x3", SOCIAL_FIRST_BLOCK, 1, 0, a, hex("""{"message":"","media":["ipfs://bafy"],"mediaType":["image/png"]}"""))!!
        assertTrue(current.hasMedia)
        assertEquals("", current.version)
    }

    @Test
    fun tipsInEachVersionsFormat() {
        val plain = TipCall.parse("0x1", 1, 1, 0, a, "0.5", hex(" 12 "), version = v1)!!
        assertEquals("12", plain.postId)
        assertTrue(plain.sent)
        assertEquals("all that was sent", BigDecimal("0.5"), plain.validAmount)
        assertNull("not a post id", TipCall.parse("0x1", 1, 1, 0, a, "1", hex("""{"postId":"12"}"""), version = v1))

        val v9Tip = TipCall.parse("0x2", 1, 1, 0, a, "3", hex("""{"postId":"7"}"""), version = v9)!!
        assertEquals("7", v9Tip.postId)
        assertEquals(BigDecimal("3"), v9Tip.validAmount)
        assertNull("nothing sent", TipCall.parse("0x2", 1, 1, 0, a, "0", hex("""{"postId":"7"}"""), version = v9)!!.validAmount)

        val atomic = TipCall.parse("0x3", 1, 1, 0, a, "0", hex("""{"postId":"4","tipAmount":"500000000000000000"}"""), "sendTipFromBalance", v5)!!
        assertTrue(atomic.fromBalance)
        assertEquals("0.5", atomic.tipAmount)
        assertEquals(BigDecimal("0.5"), atomic.validAmount)
        val whole = TipCall.parse("0x4", 1, 1, 0, a, "0", hex("""{"postId":"4","tipAmount":"2"}"""), "sendTipFromBalance", v1)!!
        assertEquals(BigDecimal("2"), whole.validAmount)
        assertNull("no tips balance in v11", TipCall.parse("0x5", 1, 1, 0, a, "0", hex("""{"postId":"4","tipAmount":"2"}"""), "sendTipFromBalance", v11))

        val v11Tip = TipCall.parse("0x6", 1, 1, 0, a, "5", hex("""{"postId":"4","tipAmount":"2"}"""), version = v11)!!
        assertFalse(v11Tip.sent)
        assertEquals("only the tip goes to the author", BigDecimal("2"), v11Tip.validAmount)
        assertEquals(v11Tip, TipCall.fromJson(JSONObject(v11Tip.toJson().toString())))
        assertEquals(atomic, TipCall.fromJson(JSONObject(atomic.toJson().toString())))
    }

    @Test
    fun tipAmountsShowAtMostThreeDecimals() {
        assertEquals("500", tipText(BigDecimal("500")))
        assertEquals("2.5", tipText(BigDecimal("2.50")))
        assertEquals("26380.455", tipText(BigDecimal("26380.455")))
        assertEquals("0.3", tipText(BigDecimal("0.30000000000000004")))
        assertEquals("0.000", tipText(BigDecimal("0.000000000000000001")))
        assertEquals("0", tipText(BigDecimal.ZERO))
    }

    @Test
    fun refusedCallsAreLeftOut() {
        fun call(hash: String, method: String, arg: String, success: Boolean?) = JSONObject()
            .put("height", 10_700_000).put("timestamp", 1).put("index", 0).put("hash", hash).put("from", a)
            .put("amount", "1").put("method", method).put("args", JSONArray().put(hex(arg)))
            .apply { if (success != null) put("success", success) }
        val calls = JSONArray()
            .put(call("0x1", "makePost", """{"message":"out of gas"}""", false))
            .put(call("0x2", "makePost", """{"message":"retry"}""", true))
            .put(call("0x3", "sendTip", """{"postId":"1"}""", true))
            .put(call("0x4", "makePost", """{"message":"no mark"}""", null))
            .put(call("0x5", "deposit", "", true))
        val found = callsToActivity(calls, v9)
        assertEquals(listOf("0x2", "0x4"), found.posts.map { it.hash })
        assertEquals(listOf("v9", "v9"), found.posts.map { it.version })
        assertEquals(listOf("0x3"), found.tips.map { it.hash })
    }

    @Test
    fun anOlderVersionCountsFromItsFirstPost() {
        // Ids 1-3 by a, b, a; the last post (id 4, by b) came after the next version and is not read.
        val posts = listOf(post("p1", a, "one", 10), post("p2", b, "two", 11), post("p3", a, "three", 12))
        val authors = mapOf(1L to a, 2L to b, 3L to a, 4L to b)
        assertEquals(mapOf("p1" to 1L, "p2" to 2L, "p3" to 3L), postIds(posts, authors, fromOldest = true))
        assertEquals("from the newest, every id would be off", mapOf("p2" to 4L, "p1" to 3L), postIds(posts, authors))
    }

    @Test
    fun olderPostsFollowTheCurrentOnesWithTheirOwnIds() {
        val posts = listOf(
            post("c1", a, "current", SOCIAL_FIRST_BLOCK + 10),
            post("o1", a, "older post", 10_800_000, version = v11),
            post("o2", b, "older reply", 10_800_001, replyTo = "1", version = v11),
            post("o3", a, "older comment", 10_800_002, replyTo = "2", channel = "discuss:2", version = v11),
            post("o4", b, LIKE, 10_800_003, replyTo = "1", version = v11),
        )
        val tips = listOf(TipCall("t1", 10_800_004, 10_800_004, 0, b, "1", "2", "2", version = "v11"))
        val read = SocialCache(
            low = v11.from, high = SOCIAL_FIRST_BLOCK + 10, posts = posts, authors = mapOf(1L to a), authorsHeight = SOCIAL_FIRST_BLOCK + 10, tips = tips,
            olderAuthors = mapOf("v11" to mapOf(1L to a, 2L to b, 3L to a, 4L to b)),
        )
        val feed = read.feed()
        assertEquals(listOf("c1", "o1"), feed.map { it.call.hash })
        assertEquals(1L, feed[0].id)
        val older = feed[1]
        assertEquals(1L - OLDER_ID_SPAN, older.id)
        assertTrue(older.isOlder)
        assertEquals(1, older.likes)
        assertEquals(BigDecimal("2"), older.tipTotal)
        assertEquals((1L - OLDER_ID_SPAN).toString(), older.tips.single().postId)
        val reply = older.replies.single()
        assertEquals(2L - OLDER_ID_SPAN, reply.id)
        assertEquals((1L - OLDER_ID_SPAN).toString(), reply.call.replyTo)
        val comment = reply.replies.single()
        assertEquals("discuss:${2L - OLDER_ID_SPAN}", comment.call.channel)
        assertEquals("the thread nests it under the reply", listOf(comment.id), commentTree(reply.id, reply.replies).map { it.comment.id })
        assertEquals("ordered by id, the older posts come first", listOf(older.id, feed[0].id), feed.map { it.id }.sorted())

        assertEquals("a version not read to its first block is not shown", listOf("c1"), read.copy(low = v11.from + 1).feed().map { it.call.hash })
        assertEquals("nor one without its authors", listOf("c1"), read.copy(olderAuthors = emptyMap()).feed().map { it.call.hash })
        assertEquals(read, SocialCache.fromJson(JSONObject(read.toJson().toString())))
    }

    @Test
    fun eachOlderVersionHasItsOwnIds() {
        assertEquals(5L, CURRENT_VERSION.appId(5))
        val ids = SOCIAL_VERSIONS.map { it.appId(1) }
        assertEquals("newer versions above older ones", ids.sortedDescending(), ids)
        assertTrue(versionNamed("v11").appId(OLDER_ID_SPAN - 1) < 0)
    }
}
