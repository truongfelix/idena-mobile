package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"

    private fun hex(text: String) = "0x" + text.toByteArray().joinToString("") { "%02x".format(it) }

    private fun post(
        hash: String, author: String, message: String, time: Long,
        replyTo: String = "", channel: String = "", height: Long = time, index: Int = 0, hasMedia: Boolean = false,
    ) = PostCall(hash, height, time, index, author, message, replyTo, channel, hasMedia)

    @Test
    fun parseReadsTheArgumentIdenaSocialSends() {
        val arg = """{"message":"hi","replyToPostId":"5","media":["AAAA"],"mediaType":["image/png"]}"""
        val call = PostCall.parse("0x01", 7, 1000, 2, "0xAAAA000000000000000000000000000000000001", hex(arg))!!
        assertEquals("hi", call.message)
        assertEquals("5", call.replyTo)
        assertEquals("", call.channel)
        assertTrue(call.hasMedia)
        assertEquals("the author is kept lowercase", a, call.author)
        assertFalse(PostCall.parse("0x01", 7, 1000, 0, a, hex("""{"message":"x","media":["AAAA"]}"""))!!.hasMedia)
        assertNull("not JSON", PostCall.parse("0x01", 7, 1000, 0, a, hex("hello")))
    }

    @Test
    fun parseKeepsTheFirstMedia() {
        val onIpfs = PostCall.parse("0x01", 7, 1000, 0, a, hex("""{"message":"","media":["ipfs://bafkreiabc123","x"],"mediaType":["image/png","image/gif"]}"""))!!
        assertEquals("ipfs://bafkreiabc123", onIpfs.media)
        assertEquals("image/png", onIpfs.mediaType)
        val inline = PostCall.parse("0x01", 7, 1000, 0, a, hex("""{"message":"hi","media":["iVBORw0KGgo="],"mediaType":["image/png"]}"""))!!
        assertEquals("iVBORw0KGgo=", inline.media)
        val big = "A".repeat(PostCall.MAX_INLINE_MEDIA + 1)
        val tooLarge = PostCall.parse("0x01", 7, 1000, 0, a, hex("""{"message":"","media":["$big"],"mediaType":["image/png"]}"""))!!
        assertTrue("still a post with media", tooLarge.hasMedia)
        assertEquals("but not kept", "", tooLarge.media)
    }

    @Test
    fun onlyPlainIpfsIdsAreFetched() {
        assertEquals("bafkreifcg3m5p24psikerh4eqi3yeyyfbkvyrzukitfi2s3o2p4wwrlzpe", ipfsCid("ipfs://bafkreifcg3m5p24psikerh4eqi3yeyyfbkvyrzukitfi2s3o2p4wwrlzpe"))
        assertNull("it names a file: no path", ipfsCid("ipfs://../../node/datadir/keystore"))
        assertNull(ipfsCid("ipfs://bafk rei"))
        assertNull(ipfsCid("bafkreifcg3m5p24psikerh4eqi3yeyyfb"))
        assertNull(ipfsCid("ipfs://"))
    }

    @Test
    fun postIdsFollowTheContractsAuthors() {
        val posts = listOf(post("h1", a, "1", 10), post("h2", b, "2", 20), post("h3", a, "3", 30))
        assertEquals(mapOf("h1" to 1L, "h2" to 2L, "h3" to 3L), postIds(posts, mapOf(1L to a, 2L to b, 3L to a)))
    }

    @Test
    fun aFailedCallGetsNoId() {
        // c's call failed: the contract numbered only a's and b's posts.
        val posts = listOf(post("h1", a, "1", 10), post("h2", c, "failed", 20), post("h3", b, "2", 30))
        assertEquals(mapOf("h1" to 1L, "h3" to 2L), postIds(posts, mapOf(1L to a, 2L to b)))
    }

    @Test
    fun newestPostsGetTheirIdsBeforeTheHistoryIsComplete() {
        // Only the two newest posts are scanned so far; post 1 is in older blocks.
        val posts = listOf(post("h2", b, "2", 20), post("h3", a, "3", 30))
        assertEquals(mapOf("h2" to 2L, "h3" to 3L), postIds(posts, mapOf(1L to a, 2L to b, 3L to a)))
    }

    @Test
    fun postsOfOneBlockAreNumberedInTheirBlockOrder() {
        val posts = listOf(post("second", b, "2", 10, index = 1), post("first", a, "1", 10, index = 0))
        assertEquals(mapOf("first" to 1L, "second" to 2L), postIds(posts, mapOf(1L to a, 2L to b)))
    }

    @Test
    fun theFeedFollowsIdenaSocialsRules() {
        // In chain order, so post n gets id n.
        val posts = listOf(
            post("p1", a, "hello", 100),
            post("p2", b, "same block time as its post", 100, replyTo = "1", index = 1),
            post("p3", b, LIKE, 110, replyTo = "1"),
            post("p4", c, "nice", 120, replyTo = "1"),
            post("p5", a, "thanks", 130, replyTo = "4", channel = "discuss:4"),
            post("p6", c, "reply to a like", 140, replyTo = "3"),
            post("p7", a, "", 150),
            post("p8", b, "other channel", 160, channel = "news"),
            post("p9", c, "reply to an unknown post", 170, replyTo = "99"),
            post("p10", b, "second post", 200),
            post("p11", a, LIKE, 210, replyTo = "4", channel = "discuss:4"),
            post("p12", b, LIKE, 220, replyTo = "5", channel = "discuss:4"),
            post("p13", c, "answer to a comment, main channel", 230, replyTo = "5"),
            post("p14", c, "comment in the channel of a post, not a reply", 240, channel = "discuss:1"),
        )
        val authors = posts.mapIndexed { i, p -> (i + 1).toLong() to p.author }.toMap()
        val feed = socialFeed(posts, authors)
        assertEquals("newest first", listOf(10L, 1L), feed.map { it.id })
        val first = feed[1]
        assertEquals(1, first.likes)
        assertEquals("the replies of post 1", listOf(4L), first.replies.map { it.id })
        val reply = first.replies[0]
        assertEquals("a like on the reply, in its discussion channel", 1, reply.likes)
        assertEquals("the comments of reply 4", listOf(5L, 13L), reply.replies.map { it.id })
        assertEquals("a like on a comment", 1, reply.replies[0].likes)
        assertEquals("who liked the post", listOf(b), first.likedBy)
        assertEquals(3, replyCount(first))
        assertEquals(0, replyCount(feed[0]))
    }

    private fun tip(hash: String, from: String, postId: String, tipAmount: String, amount: String, height: Long, index: Int = 0) =
        TipCall(hash, height, height, index, from, postId, tipAmount, amount)

    @Test
    fun onlyTipsTheContractAcceptsAreValid() {
        assertEquals(5L, tip("t", a, "1", "5", "5", 10).validAmount)
        assertEquals("more sent than tipped", 5L, tip("t", a, "1", "5", "6.5", 10).validAmount)
        assertNull("a fraction: the contract reads 0 and refuses", tip("t", a, "1", "0.5", "0.5", 10).validAmount)
        assertNull(tip("t", a, "1", "0", "1", 10).validAmount)
        assertNull("more tipped than sent", tip("t", a, "1", "5", "4.99", 10).validAmount)
        assertNull(tip("t", a, "1", "", "1", 10).validAmount)
    }

    @Test
    fun tipsCountOnTheirPostAfterIt() {
        val posts = listOf(post("p1", a, "hello", 100), post("p2", b, "reply", 110, replyTo = "1"))
        val authors = mapOf(1L to a, 2L to b)
        val tips = listOf(
            tip("t1", b, "1", "3", "3", 105),
            tip("t2", c, "1", "2", "2", 120),
            tip("t3", c, "1", "1", "1", 90),
            tip("t4", c, "2", "0.5", "0.5", 120),
            tip("t5", c, "9", "1", "1", 120),
            tip("t6", a, "2", "4", "4", 130),
        )
        val feed = socialFeed(posts, authors, tips)
        assertEquals("before the post (t3), a fraction (t4) and an unknown post (t5) do not count", listOf("t1", "t2"), feed[0].tips.map { it.hash })
        assertEquals(5L, feed[0].tipTotal)
        assertEquals(4L, feed[0].replies[0].tipTotal)
    }

    @Test
    fun activityListsWhatOthersDidOnMyPosts() {
        val posts = listOf(
            post("p1", a, "my post", 100),
            post("p2", b, "a reply to me", 110, replyTo = "1"),
            post("p3", c, LIKE, 120, replyTo = "1"),
            post("p4", a, LIKE, 125, replyTo = "1"),
            post("p5", a, "my reply to b", 130, replyTo = "1"),
            post("p6", c, "comment on my reply", 140, replyTo = "5", channel = "discuss:5"),
            post("p7", a, "my comment", 150, replyTo = "2", channel = "discuss:2"),
            post("p8", b, "answer to my comment", 160, replyTo = "7", channel = "discuss:2"),
            post("p9", b, "someone else's post", 170),
        )
        val authors = posts.mapIndexed { i, p -> (i + 1).toLong() to p.author }.toMap()
        val tips = listOf(tip("t1", c, "5", "2", "2", 180), tip("t2", a, "9", "1", "1", 190))
        val items = socialActivity(socialFeed(posts, authors, tips), a)
        assertEquals(
            listOf(
                ActivityKind.Tip to c, ActivityKind.Comment to b, ActivityKind.Comment to c,
                ActivityKind.Like to c, ActivityKind.Reply to b,
            ),
            items.map { it.kind to it.actor },
        )
        assertEquals("the tip on my reply", 2L, items[0].amount)
        assertEquals("my reply was tipped", "my reply to b", items[0].what.message)
        assertEquals("every item opens post 1's thread", setOf(1L), items.map { it.threadId }.toSet())
    }

    @Test
    fun theTipArgumentIsWrittenAsIdenaSocialWritesIt() {
        assertEquals("{\"postId\":\"102\",\"tipAmount\":\"5\"}", sendTipArgument(102, 5))
        val parsed = TipCall.parse("0x01", 1, 1, 0, "0xAB", "5", hex(sendTipArgument(102, 5)))!!
        assertEquals("102", parsed.postId)
        assertEquals(5L, parsed.validAmount)
        assertEquals("0xab", parsed.from)
    }

    @Test
    fun aScanWithoutTipsIsDoneAgain() {
        val cache = SocialCache(1, 2, emptyList(), emptyMap(), 2, listOf(tip("t", a, "1", "1", "1", 2)))
        assertEquals(cache, SocialCache.fromJson(JSONObject(cache.toJson().toString())))
        assertNull("version 1 dropped the tips", SocialCache.fromJson(cache.toJson().put("version", 1)))
        assertNull("version 2 dropped the media", SocialCache.fromJson(cache.toJson().put("version", 2)))
        assertNull("no version is version 1", SocialCache.fromJson(cache.toJson().apply { remove("version") }))
    }

    @Test
    fun postAuthorsReadsTheContractMap() {
        // An item of contract_iterateMap(idena.social, "p:", ..., "hex", "hex") on mainnet: post 1.
        val items = JSONArray().put(
            JSONObject()
                .put("key", "0x01000000000000000000000000000000")
                .put("value", "0x307862313262313232326430636537306430336264393834613836316338323561383661633637383938"),
        ).put(JSONObject().put("key", "0x66000000000000000000000000000000").put("value", hex("0xABC")))
        assertEquals(mapOf(1L to "0xb12b1222d0ce70d03bd984a861c825a86ac67898", 102L to "0xabc"), postAuthors(items))
    }

    @Test
    fun theLastMapPageEndsTheIteration() {
        // As the node answered on mainnet: a token after the first 100 posts, then "0x" after the last one.
        assertEquals("0x703a65000000000000000000000000000000", nextPageToken("0x703a65000000000000000000000000000000"))
        assertNull(nextPageToken("0x"))
        assertNull(nextPageToken(""))
        assertNull(nextPageToken(null))
        assertNull(nextPageToken(JSONObject.NULL))
    }

    /** A node whose head moves as [heads] says, one value per head() call (the last one repeats). */
    private class FakeSource(private val heads: List<Long>, private val postsAt: Map<Long, PostCall> = emptyMap()) : SocialSource {
        val ranges = ArrayList<LongRange>()
        var authorReads = 0
        private var headCalls = 0

        override fun batchSize() = SOCIAL_BATCH

        override fun head(): Long = heads[minOf(headCalls++, heads.size - 1)]

        override fun calls(from: Long, to: Long): BlockCalls {
            ranges += from..to
            return BlockCalls((from..to).mapNotNull { postsAt[it] }, emptyList())
        }

        override fun authors(): Map<Long, String> {
            authorReads++
            return mapOf(1L to "0x$authorReads")
        }
    }

    @Test
    fun theFirstCheckStartsAtTheHead() {
        val source = FakeSource(listOf(12_000_000))
        val cache = checkNewBlocks(null, source) {}
        assertTrue("no block scanned", source.ranges.isEmpty())
        assertEquals(12_000_001, cache.low)
        assertEquals(12_000_000, cache.high)
        assertEquals(12_000_000, cache.authorsHeight)
        assertEquals(0.0, cache.scannedShare, 0.0)
    }

    @Test
    fun newBlocksAreScannedInBatchesUpToTheHead() {
        val start = SocialCache(low = 11_000_001, high = 11_000_000, posts = emptyList(), authors = emptyMap(), authorsHeight = 11_000_000)
        val source = FakeSource(listOf(11_001_200), mapOf(11_000_700L to post("h", a, "hi", 1, height = 11_000_700)))
        var saves = 0
        val cache = checkNewBlocks(start, source) { saves++ }
        assertEquals(
            listOf(11_000_001L..11_000_500L, 11_000_501L..11_001_000L, 11_001_001L..11_001_200L),
            source.ranges,
        )
        assertEquals(11_001_200, cache.high)
        assertEquals(11_001_200, cache.authorsHeight)
        assertEquals(listOf("h"), cache.posts.map { it.hash })
        assertEquals("each batch, then the authors", 4, saves)
    }

    @Test
    fun aStoppedScanKeepsItsBatchesAndTheOldAuthors() {
        val start = SocialCache(low = 1_001, high = 1_000, posts = emptyList(), authors = mapOf(1L to a), authorsHeight = 1_000)
        val source = FakeSource(listOf(2_200))
        var batches = 0
        // Unselected during the second batch: the scan stops before the third.
        val cache = checkNewBlocks(start, source, keepGoing = { batches++ < 2 }) {}
        assertEquals(listOf(1_001L..1_500L, 1_501L..2_000L), source.ranges)
        assertEquals(2_000, cache.high)
        assertEquals("the authors are not read", 0, source.authorReads)
        assertEquals(1_000, cache.authorsHeight)
        assertEquals(mapOf(1L to a), cache.authors)
    }

    @Test
    fun theAuthorsAreOnlyTakenAtAStableHead() {
        val start = SocialCache(low = 101, high = 100, posts = emptyList(), authors = emptyMap(), authorsHeight = 100)
        // The head moves from 110 to 111 while the authors are read: the new block is scanned first.
        val source = FakeSource(listOf(110, 111, 111, 111))
        val cache = checkNewBlocks(start, source) {}
        assertEquals(listOf(101L..110L, 111L..111L), source.ranges)
        assertEquals(111, cache.authorsHeight)
        assertEquals("the second read", mapOf(1L to "0x2"), cache.authors)
    }

    @Test
    fun theHistoryStopsAtTheContractsFirstBlock() {
        var cache = SocialCache(
            low = SOCIAL_FIRST_BLOCK + 700, high = SOCIAL_FIRST_BLOCK + 800,
            posts = emptyList(), authors = emptyMap(), authorsHeight = 0,
        )
        val source = FakeSource(listOf(0))
        cache = scanOlderBlocks(cache, source) {}
        cache = scanOlderBlocks(cache, source) {}
        cache = scanOlderBlocks(cache, source) {}
        assertEquals(
            listOf((SOCIAL_FIRST_BLOCK + 200)..(SOCIAL_FIRST_BLOCK + 699), SOCIAL_FIRST_BLOCK..(SOCIAL_FIRST_BLOCK + 199)),
            source.ranges,
        )
        assertTrue(cache.historyDone)
        assertEquals(1.0, cache.scannedShare, 0.0)
    }

    @Test
    fun theCacheSurvivesItsJson() {
        val cache = SocialCache(
            low = 11_000_000, high = 11_000_500,
            posts = listOf(post("h", a, "hé \"quoted\"\nline", 42, replyTo = "3", channel = "discuss:1", height = 11_000_010, index = 2, hasMedia = true)),
            authors = mapOf(1L to a, 2L to b), authorsHeight = 11_000_500,
        )
        assertEquals(cache, SocialCache.fromJson(JSONObject(cache.toJson().toString())))
        assertNull("another contract", SocialCache.fromJson(cache.toJson().put("contract", "0x0")))
    }

    @Test
    fun theArgumentIsWrittenAsJavaScriptWritesIt() {
        assertEquals("\"a/b \\\"q\\\" \\\\ \\n\\t\\u0001 é ❤️ \uD83D\uDE00\"", jsonString("a/b \"q\" \\ \n\t\u0001 é ❤️ \uD83D\uDE00"))
        assertEquals("a lone surrogate is escaped", "\"\\ud83d\"", jsonString("\uD83D"))
        assertEquals("{\"message\":\"see https://idena.io\"}", makePostArgument("see https://idena.io"))
        assertEquals("{\"message\":\"❤️\",\"replyToPostId\":\"12\"}", makePostArgument(LIKE, replyTo = 12))
        // A like of comment 15 under reply 12, as idena.social writes it.
        assertEquals(
            "{\"message\":\"❤️\",\"replyToPostId\":\"15\",\"channelId\":\"discuss:12\"}",
            PostTarget.onComment(12, 15).let { makePostArgument(LIKE, it.replyTo, it.channel) },
        )
        // The argument parses back to the same post.
        val parsed = PostCall.parse("0x01", 1, 1, 0, a, hex(makePostArgument("line 1\nline \"2\"", replyTo = 7)))!!
        assertEquals("line 1\nline \"2\"", parsed.message)
        assertEquals("7", parsed.replyTo)
    }
}
