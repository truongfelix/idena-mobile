package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

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
        assertEquals(BigDecimal("5"), tip("t", a, "1", "5", "5", 10).validAmount)
        assertEquals("more sent than tipped", BigDecimal("5"), tip("t", a, "1", "5", "6.5", 10).validAmount)
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
        assertEquals(BigDecimal("5"), feed[0].tipTotal)
        assertEquals(BigDecimal("4"), feed[0].replies[0].tipTotal)
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
        assertEquals("the tip on my reply", BigDecimal("2"), items[0].amount)
        assertEquals("my reply was tipped", "my reply to b", items[0].what.message)
        assertEquals("every item opens post 1's thread", setOf(1L), items.map { it.threadId }.toSet())
        assertEquals("at the answer, or at what was liked or tipped", listOf(5L, 8L, 6L, 1L, 2L), items.map { it.focusId })
    }

    @Test
    fun aProfileListsWhatItsIdentityDid() {
        val posts = listOf(
            post("p1", a, "a's post", 100),
            post("p2", b, "b's reply", 110, replyTo = "1"),
            post("p3", a, LIKE, 120, replyTo = "2", channel = "discuss:2"),
            post("p4", a, "a's comment on b's reply", 130, replyTo = "2", channel = "discuss:2"),
            post("p5", c, "c's comment", 140, replyTo = "2", channel = "discuss:2"),
            post("p6", a, "a answers c's comment", 150, replyTo = "5", channel = "discuss:2"),
            post("p7", b, LIKE, 160, replyTo = "1"),
            post("p8", a, "a's reply with media", 170, replyTo = "1", hasMedia = true),
            post("p9", b, "b's post", 180),
            post("p10", a, "a's second post", 190),
        )
        val authors = posts.mapIndexed { i, p -> (i + 1).toLong() to p.author }.toMap()
        val tips = listOf(
            tip("t1", b, "1", "3", "3", 200),
            tip("t2", a, "9", "2", "2", 210),
            tip("t3", c, "5", "1", "1", 220),
        )
        val profile = socialProfile(socialFeed(posts, authors, tips), a.uppercase().replace("0X", "0x"))
        assertEquals(a, profile.address)
        assertEquals("newest first", listOf(10L, 1L), profile.posts.map { it.node.id })
        assertEquals(listOf(8L), profile.replies.map { it.node.id })
        assertEquals("p1", profile.replies[0].parent!!.hash)
        assertEquals(listOf(6L, 4L), profile.comments.map { it.node.id })
        assertEquals("a comment answering a comment", "p5", profile.comments[0].parent!!.hash)
        assertEquals("a comment on the reply", "p2", profile.comments[1].parent!!.hash)
        assertEquals("what a liked", listOf("p2"), profile.likes.map { it.node.call.hash })
        assertEquals("p3", profile.likes[0].like!!.hash)
        assertEquals(listOf(8L), profile.media.map { it.node.id })
        assertEquals("what a liked, not the like a received (p7)", listOf("p2"), profile.likes.map { it.node.call.hash })
        assertEquals("the tips a gave, not the one a received (t1) nor c's on c's own comment", listOf("t2"), profile.tips.map { it.tip!!.hash })
        assertEquals(1, profile.likesReceived)
        assertEquals(BigDecimal("3"), profile.tipsReceived)
        assertEquals(BigDecimal("2"), profile.tipsGiven)
        assertEquals("every entry opens post 1's thread or its own", setOf(1L, 10L, 9L), (profile.posts + profile.comments + profile.tips).map { it.threadId }.toSet())
    }

    @Test
    fun aProfileWithoutPostsIsEmpty() {
        val profile = socialProfile(socialFeed(listOf(post("p1", a, "a's post", 100)), mapOf(1L to a)), c)
        assertTrue(profile.posts.isEmpty() && profile.replies.isEmpty() && profile.comments.isEmpty())
        assertTrue(profile.likes.isEmpty() && profile.tips.isEmpty() && profile.media.isEmpty())
        assertEquals(0, profile.likesReceived)
        assertEquals(BigDecimal.ZERO, profile.tipsReceived + profile.tipsGiven)
        assertTrue("an empty feed", socialProfile(emptyList(), a).posts.isEmpty())
    }

    @Test
    fun theProfileIdentityComesFromDnaIdentity() {
        val human = ProfileIdentity.fromJson(JSONObject().put("state", "Human").put("age", 12).put("stake", "1234.5678"))
        assertEquals("Human", human.status)
        assertEquals(12, human.age)
        assertEquals(java.math.BigDecimal("1234.5678"), human.stake)
        // An address without an identity, as the node answers it.
        val none = ProfileIdentity.fromJson(JSONObject().put("state", "Undefined").put("age", 0).put("stake", "0"))
        assertEquals("Not validated", none.status)
        assertEquals(0, none.stake.signum())
        // The same names next to the authors of posts.
        assertEquals("Not validated", identityStatus(""))
        assertEquals("Candidate", identityStatus("Candidate"))
    }

    @Test
    fun theLatestActivityPutsTheBusiestThreadFirst() {
        val posts = listOf(
            post("p1", a, "old post", 100),
            post("p2", b, "newer post", 200),
            post("p3", c, "a reply to the old post", 300, replyTo = "1"),
            post("p4", a, "a comment under it", 400, replyTo = "3", channel = "discuss:3"),
            post("p5", c, "newest post", 450),
        )
        val authors = posts.mapIndexed { i, p -> (i + 1).toLong() to p.author }.toMap()
        val feed = socialFeed(posts, authors, listOf(tip("t1", b, "2", "1", "1", 500)))
        assertEquals(listOf(5L, 2L, 1L), sortFeed(feed, FeedSort.Newest).map { it.id })
        assertEquals("post 2 was tipped last, post 1 commented on before", listOf(2L, 5L, 1L), sortFeed(feed, FeedSort.Activity).map { it.id })
        assertEquals(400L, lastActivityHeight(feed.first { it.id == 1L }))
        assertTrue(sortFeed(emptyList(), FeedSort.Activity).isEmpty())
    }

    private fun activity(kind: ActivityKind, actor: String, height: Long, amount: Int? = null) =
        ActivityItem(kind, actor, height, 0, height, 1, 1, post("p1", a, "my post", 100), amount = amount?.toBigDecimal())

    @Test
    fun onlyNewActivityOfTheKindsOnNotifies() {
        val items = listOf(
            activity(ActivityKind.Tip, b, 300, amount = 5),
            activity(ActivityKind.Comment, c, 250),
            activity(ActivityKind.Like, b, 200),
            activity(ActivityKind.Reply, c, 150),
        )
        assertEquals(listOf(300L, 250L), newActivity(items, 200, NotifyKind.entries.toSet()).map { it.height })
        assertEquals("tips off", listOf(250L, 200L, 150L), newActivity(items, 100, setOf(NotifyKind.Likes, NotifyKind.Comments)).map { it.height })
        assertTrue("nothing on", newActivity(items, 0, emptySet()).isEmpty())
        assertTrue("nothing after", newActivity(items, 300, NotifyKind.entries.toSet()).isEmpty())
    }

    @Test
    fun theNoticeSaysWhatHappened() {
        val short = { address: String -> address.take(6) }
        assertEquals("0xbbbb tipped you 5 iDNA", noticeText(listOf(activity(ActivityKind.Tip, b, 300, amount = 5)), short))
        assertEquals("0xcccc commented on your post", noticeText(listOf(activity(ActivityKind.Comment, c, 300)), short))
        assertEquals(
            "4 new on your posts: 2 likes, 1 reply, 1 tip",
            noticeText(
                listOf(
                    activity(ActivityKind.Like, b, 4), activity(ActivityKind.Tip, c, 3, amount = 1),
                    activity(ActivityKind.Like, c, 2), activity(ActivityKind.Reply, c, 1),
                ),
                short,
            ),
        )
    }

    @Test
    fun aPostWithAnImageIsWrittenAsIdenaSocialWritesIt() {
        assertEquals(
            "{\"message\":\"look\",\"media\":[\"ipfs://bafkreiabc\"],\"mediaType\":[\"image/webp\"]}",
            makePostArgument("look", media = "ipfs://bafkreiabc", mediaType = "image/webp"),
        )
        assertEquals(
            "{\"message\":\"\",\"replyToPostId\":\"7\",\"channelId\":\"discuss:7\",\"media\":[\"ipfs://bafkreiabc\"],\"mediaType\":[\"image/webp\"]}",
            makePostArgument("", 7, "discuss:7", "ipfs://bafkreiabc", "image/webp"),
        )
        // The app reads it back as a post with media on IPFS.
        val parsed = PostCall.parse("0x01", 1, 1, 0, a, hex(makePostArgument("ipfs://bafkreitext", media = "ipfs://bafkreiimg", mediaType = "image/webp")))!!
        assertTrue(parsed.hasMedia)
        assertEquals("ipfs://bafkreiimg", parsed.media)
        assertEquals("image/webp", parsed.mediaType)
        assertEquals("bafkreitext", ipfsCid(parsed.message))
    }

    @Test
    fun theStoreToIpfsPayloadHoldsTheCidAndTheSize() {
        val cid = "bafkreifcg3m5p24psikerh4eqi3yeyyfbkvyrzukitfi2s3o2p4wwrlzpe"
        // Expected bytes from Python's base64.b32decode: CID v1, raw, sha2-256, 32 bytes.
        assertEquals("0x01551220a236d9d7eb8f9214489f8482378263050aab88e68a44ca8d4b6ed3f96b457979", bytesToHex(cidBytes(cid)))
        assertEquals(
            "0x0a2401551220a236d9d7eb8f9214489f8482378263050aab88e68a44ca8d4b6ed3f96b45797910f0a204",
            bytesToHex(storeToIpfsPayload(cid, 70_000)),
        )
        assertTrue(runCatching { cidBytes("QmYQEa2YYEcFRAnxuaVS5jBYKPQQFricdTHjtDMr3Ma2M3") }.isFailure)
    }

    @Test
    fun storingOnIpfsCostsAFifthOfItsSizeInTransactionBytes() {
        // 10^14 per gas (0.0001 iDNA): 10 gas per byte for 150 bytes plus a fifth of 50,000.
        assertEquals(java.math.BigDecimal("10.15"), storeToIpfsFeeEstimate(java.math.BigDecimal("100000000000000"), 50_000).stripTrailingZeros())
    }

    @Test
    fun theStatusDotFollowsTheNodeAndTheScan() {
        val node = NodeView(peers = 5, syncing = false, currentBlock = 1_000, highestBlock = 1_000, validation = false)
        val done = SocialCache(low = HISTORY_START, high = 11_000_000, posts = emptyList(), authors = emptyMap(), authorsHeight = 11_000_000)
        assertEquals(BarState(Health.Good, "Up to date"), scanState(node, true, 11_000_000, done))
        assertEquals("a few blocks behind is still up to date", Health.Good, scanState(node, true, 11_000_000L + UP_TO_DATE_LAG, done).health)
        assertEquals(BarState(Health.Warn, "4 blocks behind"), scanState(node, true, 11_000_004, done))
        assertEquals(BarState(Health.Bad, "No peers"), scanState(node.copy(peers = 0), true, 11_000_000, done))
        assertEquals(Health.Bad, scanState(null, true, null, done).health)
        assertEquals(Health.Warn, scanState(node.copy(syncing = true), true, 11_000_000, done).health)
        assertEquals(Health.Off, scanState(node.copy(validation = true), true, 11_000_000, done).health)
        assertEquals(BarState(Health.Off, "Scan off"), scanState(node, false, 11_000_100, done))
        assertEquals(BarState(Health.Warn, "Starting the scan"), scanState(node, true, 11_000_000, null))
        val half = done.copy(low = HISTORY_START + (11_000_000 - HISTORY_START) / 2)
        assertEquals(BarState(Health.Warn, "Reading older posts: 50%"), scanState(node, true, 11_000_000, half))
    }

    @Test
    fun socialBarLabelAddsThePeers() {
        assertEquals("Up to date · 18 peers", socialBarLabel(BarState(Health.Good, "Up to date"), 18))
        assertEquals("Up to date · 1 peer", socialBarLabel(BarState(Health.Good, "Up to date"), 1))
        assertEquals("No peers", socialBarLabel(BarState(Health.Bad, "No peers"), 0))
        assertEquals("The node does not answer", socialBarLabel(BarState(Health.Bad, "The node does not answer"), null))
    }

    @Test
    fun feedHasAnAdAfterEachFivePosts() {
        assertEquals(5, POSTS_PER_AD)
        assertEquals(0, feedRows(0))
        assertEquals(4, feedRows(4))
        assertEquals(6, feedRows(5))
        assertEquals(14, feedRows(12))
        // 12 posts: rows 0-4 posts 0-4, row 5 ad 0, rows 6-10 posts 5-9, row 11 ad 1, rows 12-13 posts 10-11.
        val rows = (0 until feedRows(12)).map { row -> feedAdAt(row)?.let { "ad$it" } ?: "p${feedPostAt(row)}" }
        assertEquals((0..4).map { "p$it" } + "ad0" + (5..9).map { "p$it" } + "ad1" + (10..11).map { "p$it" }, rows)
    }

    @Test
    fun theTipArgumentIsWrittenAsIdenaSocialWritesIt() {
        assertEquals("{\"postId\":\"102\",\"tipAmount\":\"5\"}", sendTipArgument(102, 5))
        val parsed = TipCall.parse("0x01", 1, 1, 0, "0xAB", "5", hex(sendTipArgument(102, 5)))!!
        assertEquals("102", parsed.postId)
        assertEquals(BigDecimal("5"), parsed.validAmount)
        assertEquals("0xab", parsed.from)
    }

    @Test
    fun aScanWithoutTipsIsDoneAgain() {
        val cache = SocialCache(SOCIAL_FIRST_BLOCK, SOCIAL_FIRST_BLOCK + 1, emptyList(), emptyMap(), SOCIAL_FIRST_BLOCK + 1, listOf(tip("t", a, "1", "1", "1", SOCIAL_FIRST_BLOCK + 1)))
        assertEquals(cache, SocialCache.fromJson(JSONObject(cache.toJson().toString())))
        assertNull("version 1 dropped the tips", SocialCache.fromJson(cache.toJson().put("version", 1)))
        assertNull("version 2 dropped the media", SocialCache.fromJson(cache.toJson().put("version", 2)))
        assertNull("no version is version 1", SocialCache.fromJson(cache.toJson().apply { remove("version") }))
        assertNull("a version to come", SocialCache.fromJson(cache.toJson().put("version", SocialCache.VERSION + 1)))
    }

    @Test
    fun aScanStartedBelowTheHistoryIsDropped() {
        // As 0.1.73 and earlier saved it on a new node: started at the node's first block.
        val walked = SocialCache(low = 4_871_138, high = 6_000_000, posts = emptyList(), authors = emptyMap(), authorsHeight = 4_871_137)
        assertTrue("it takes the history as read", walked.historyDone)
        assertNull(SocialCache.fromJson(JSONObject(walked.toJson().toString())))
        assertNull("one block below", SocialCache.fromJson(walked.copy(low = HISTORY_START - 1).toJson()))
        val read = walked.copy(low = HISTORY_START, high = 11_000_000, authorsHeight = 11_000_000)
        assertEquals("a history read to its first block", read, SocialCache.fromJson(JSONObject(read.toJson().toString())))
    }

    @Test
    fun theFirstScanWaitsForTheNodesNewestBlocks() {
        val now = 1_791_710_000L
        assertNull("a synced node's head", scanStartWait(11_423_708, now - 20, now))
        assertNull("an hour old at most", scanStartWait(11_423_708, now - MAX_START_BLOCK_AGE_SECONDS, now))
        assertNull("a phone clock behind the block's", scanStartWait(11_423_708, now + 600, now))
        // Mainnet's first block for a new node (resources/header.tar in idena-go).
        assertTrue(scanStartWait(4_871_137, 1_661_760_762, now)!!.startsWith("Waiting"))
        assertTrue("before the contract, even with a recent time", scanStartWait(SOCIAL_FIRST_BLOCK - 1, now, now)!!.contains("before idena.social"))
        assertEquals(
            "Waiting for the node's newest blocks: its newest one is 2 h old.",
            scanStartWait(11_000_000, now - 2 * 3_600, now),
        )
        assertTrue(scanStartWait(11_000_000, now - MAX_START_BLOCK_AGE_SECONDS - 1, now)!!.endsWith("60 min old."))
        assertTrue(scanStartWait(11_000_000, now - 3 * 86_400, now)!!.endsWith("3 days old."))
    }

    @Test
    fun aScanOfTheCurrentContractOnlyGoesOnBelowIt() {
        // As version 3 saved it: no olderAuthors, posts and tips without a version.
        val saved = SocialCache(SOCIAL_FIRST_BLOCK, 11_000_000, listOf(post("h", a, "hi", 1, height = 11_000_000)), mapOf(1L to a), 11_000_000, listOf(tip("t", b, "1", "1", "1", 11_000_000)))
            .toJson().put("version", 3).apply { remove("olderAuthors") }
        val cache = SocialCache.fromJson(JSONObject(saved.toString()))!!
        assertEquals(emptyMap<String, Map<Long, String>>(), cache.olderAuthors)
        assertEquals(listOf("h"), cache.feed().map { it.call.hash })
        assertFalse("the older contracts come next", cache.historyDone)
        assertEquals(SocialCache.VERSION, cache.toJson().getInt("version"))
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
    private class FakeSource(
        private val heads: List<Long>,
        private val postsAt: Map<Long, PostCall> = emptyMap(),
        private val batch: Long = 500,
    ) : SocialSource {
        val ranges = ArrayList<LongRange>()
        val versions = ArrayList<String>()
        val authorVersions = ArrayList<String>()
        var authorReads = 0
        private var headCalls = 0

        override fun batchSize() = batch

        override fun head(): Long = heads[minOf(headCalls++, heads.size - 1)]

        override fun calls(from: Long, to: Long, version: SocialVersion): BlockCalls {
            ranges += from..to
            versions += version.name
            return BlockCalls((from..to).mapNotNull { postsAt[it] }, emptyList())
        }

        override fun authors(version: SocialVersion): Map<Long, String> {
            authorReads++
            authorVersions += version.name
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
    fun theHistoryGoesVersionByVersionToItsFirstBlock() {
        var cache = SocialCache(
            low = SOCIAL_FIRST_BLOCK + 700, high = SOCIAL_FIRST_BLOCK + 800,
            posts = emptyList(), authors = emptyMap(), authorsHeight = 0,
        )
        val source = FakeSource(listOf(0))
        cache = scanOlderBlocks(cache, source) {}
        cache = scanOlderBlocks(cache, source) {}
        cache = scanOlderBlocks(cache, source) {}
        assertEquals(
            "a batch stops at the version's first block",
            listOf((SOCIAL_FIRST_BLOCK + 200)..(SOCIAL_FIRST_BLOCK + 699), SOCIAL_FIRST_BLOCK..(SOCIAL_FIRST_BLOCK + 199), (SOCIAL_FIRST_BLOCK - 500)..(SOCIAL_FIRST_BLOCK - 1)),
            source.ranges,
        )
        assertEquals(listOf("v12", "v12", "v11"), source.versions)
        assertEquals("the current contract's authors come with the new blocks", listOf("v11"), source.authorVersions)
        assertFalse(cache.historyDone)

        // With batches larger than any version: one batch per version, each older version's authors read once.
        val whole = FakeSource(listOf(0), batch = 1_000_000)
        while (!cache.historyDone) cache = scanOlderBlocks(cache, whole) {}
        assertEquals(listOf("v11", "v10", "v9", "v5", "v1"), whole.versions)
        assertEquals(OLDER_VERSIONS.map { (it.from)..((it.to ?: 0) - 1) }.drop(1), whole.ranges.drop(1))
        assertEquals(listOf("v10", "v9", "v5", "v1"), whole.authorVersions)
        assertEquals(OLDER_VERSIONS.map { it.name }.toSet(), cache.olderAuthors.keys)
        assertEquals(HISTORY_START, cache.low)
        assertEquals(1.0, cache.scannedShare, 0.0)
        assertEquals("nothing more to read", cache, scanOlderBlocks(cache, whole) {})
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
