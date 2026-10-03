package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class FlipMakingTest {
    private fun frames() = (0 until FLIP_FRAMES).map { i -> ByteArray(100 + i * 70) { (it * 7 + i).toByte() } }

    @Test
    fun rlpEncodingDecodesBack() {
        val long = ByteArray(300) { it.toByte() }
        val item = Rlp.decode(Rlp.encodeList(listOf(Rlp.encodeBytes(long), Rlp.encodeInt(0), Rlp.encodeInt(3), Rlp.encodeBytes(byteArrayOf(0x7f)))))
        val list = item.list()
        assertArrayEquals(long, list[0].bytes())
        assertEquals(0, list[1].bytes().size)
        assertArrayEquals(byteArrayOf(3), list[2].bytes())
        assertArrayEquals(byteArrayOf(0x7f), list[3].bytes())
    }

    @Test
    fun anEncodedFlipDecodesToItsStoryAndShuffle() {
        val frames = frames()
        val shuffled = listOf(2, 0, 3, 1)
        repeat(50) { seed ->
            val hex = encodeFlip(frames, shuffled, Random(seed))
            val flip = decodeFlip("h", hex.publicHex, hex.privateHex)
            val orders = listOf(flip.leftOrder, flip.rightOrder).map { order -> order.map { flip.images[it] } }
            val story = orders.single { o -> o.indices.all { o[it].contentEquals(frames[it]) } }
            val other = orders.single { it !== story }
            shuffled.forEachIndexed { i, position -> assertArrayEquals(frames[position], other[i]) }
        }
    }

    @Test
    fun theFramesAndOrdersAreMixed() {
        val frames = frames()
        val firstPublic = (0 until 40).map { encodeFlip(frames, listOf(1, 0, 2, 3), Random(it)).publicHex }.toSet()
        assertTrue("the public part changes with the random order", firstPublic.size > 5)
    }

    @Test
    fun shufflesMustBreakTheStory() {
        assertFalse("the story itself", isValidShuffle(listOf(0, 1, 2, 3), null))
        assertFalse("not an order", isValidShuffle(listOf(0, 0, 2, 3), null))
        assertTrue(isValidShuffle(listOf(1, 0, 2, 3), null))
        assertFalse("the story with only the anti-AI frame moved", isValidShuffle(listOf(0, 1, 3, 2), adversarial = 3))
        assertFalse(isValidShuffle(listOf(2, 0, 1, 3), adversarial = 2))
        assertTrue(isValidShuffle(listOf(1, 0, 2, 3), adversarial = 3))
        repeat(100) { assertTrue(isValidShuffle(randomShuffle(it % 4, Random(it)), it % 4)) }
    }

    @Test
    fun theAntiAiFrameGoesAnywhere() {
        val slots = (0 until 200).map { randomAdversarialSlot(Random(it)) }.toSet()
        assertEquals(setOf(0, 1, 2, 3), slots)
    }

    @Test
    fun keywordPairsAndQuotaFromTheIdentity() {
        val identity = JSONObject(
            """{"state":"Newbie","requiredFlips":3,"availableFlips":3,"madeFlips":1,
               "flipKeyWordPairs":[{"id":0,"words":[12,800],"used":true},{"id":1,"words":[5,6],"used":false}]}""",
        )
        assertEquals(listOf(KeywordPair(0, listOf(12, 800), true), KeywordPair(1, listOf(5, 6), false)), keywordPairs(identity))
        val quota = FlipQuota("Newbie", 3, 3, 1)
        assertTrue(quota.canMake)
        assertEquals(2, quota.missing)
        assertEquals("Killed", quota.missingOutcome)
        assertEquals("Suspended", FlipQuota("Human", 3, 5, 0).missingOutcome)
        assertFalse("all made", FlipQuota("Verified", 3, 4, 4).canMake)
        assertFalse(FlipQuota("Undefined", 0, 0, 0).canMake)
    }

    @Test
    fun theDeadlineIsTheFlipLottery() {
        assertEquals(Instant.parse("2026-10-09T13:00:00Z"), flipDeadline(Instant.parse("2026-10-09T15:00:00Z"), 7200.0))
    }

    @Test
    fun duckDuckGoTokenAndThumbnailsGoThroughItsProxy() {
        assertEquals("4-2791170464", duckToken("""...,vqd="4-2791170464",..."""))
        assertEquals("4-1", duckToken("""x vqd=4-1&p=1"""))
        assertNull(duckToken("no token"))
        val json = """{"results":[{"thumbnail":"https://tse4.mm.bing.net/th/id/OIP.a?r=0&pid=Api"},{"thumbnail":"http://plain.example/x"},
            {"thumbnail":"https://tse4.mm.bing.net/th/id/OIP.a?r=0&pid=Api"},{"image":"https://x"}]}"""
        val thumbs = duckThumbnails(json)
        assertEquals(1, thumbs.size)
        assertEquals("https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Ftse4.mm.bing.net%2Fth%2Fid%2FOIP.a%3Fr%3D0%26pid%3DApi&f=1", thumbs[0])
        assertTrue(duckThumbnails("not json").isEmpty())
        assertTrue(duckResultsUrl("apple pie", "4-1").startsWith("https://duckduckgo.com/i.js?l=us-en&o=json&q=apple+pie&vqd=4-1"))
    }

    @Test
    fun refusalsAreExplained() {
        assertEquals("This identity state cannot submit flips.", flipRefusal("not a candidate"))
        assertNotEquals("x", flipRefusal("insufficient flips"))
    }

    @Test
    fun aSentFlipCountsBeforeItsBlock() {
        val quota = FlipQuota("Newbie", 3, 3, 1)
        val pairs = listOf(KeywordPair(0, listOf(1, 2), true), KeywordPair(1, listOf(3, 4), false), KeywordPair(2, listOf(5, 6), false))
        val sent = SentFlip("0xtx", "bafy", pairId = 1, sentAt = 0, epoch = 230)
        val (q, p) = countSent(quota, pairs, sent, 230)
        assertEquals("one more made at once", 2, q.made)
        assertTrue("its pair is no longer offered", p.single { it.id == 1 }.used)
        assertFalse(p.single { it.id == 2 }.used)
        // Once the node counts it (the pair used in the state), it is not counted twice.
        val inBlock = pairs.map { if (it.id == 1) it.copy(used = true) else it }
        assertTrue(isCounted(sent, inBlock))
        assertEquals(quota.copy(made = 2) to inBlock, countSent(quota.copy(made = 2), inBlock, sent, 230))
        assertEquals(quota to pairs, countSent(quota, pairs, null, 230))
        assertEquals(sent, SentFlip.fromJson(sent.toJson()))
    }

    @Test
    fun aSentFlipNeverBlocksForGood() {
        val pairs = listOf(KeywordPair(0, listOf(1, 2), false), KeywordPair(1, listOf(3, 4), false))
        val sent = SentFlip("0xtx", "bafy", pairId = 1, sentAt = 1_000, epoch = 230)
        assertEquals(SentFlipCheck.Wait, checkSent(sent, 230, pairs, now = 2_000))
        val inBlock = pairs.map { if (it.id == 1) it.copy(used = true) else it }
        assertEquals(SentFlipCheck.Counted, checkSent(sent, 230, inBlock, now = 2_000))
        // Mined last epoch and the screen closed before the block: pair 1 of the new epoch is another pair.
        assertEquals(SentFlipCheck.OtherEpoch, checkSent(sent, 231, pairs, now = 2_000))
        assertEquals(SentFlipCheck.OtherEpoch, checkSent(sent, 231, inBlock, now = 2_000))
        val quota = FlipQuota("Newbie", 3, 3, 0)
        assertEquals("not counted in another epoch", quota to pairs, countSent(quota, pairs, sent, 231))
        // Still unknown to the state after the timeout, even if the node knows its transaction.
        assertEquals(SentFlipCheck.TimedOut, checkSent(sent, 230, pairs, now = 1_000 + SENT_FLIP_TIMEOUT_MS + 1))
        // A record of app 0.1.31 has no epoch: it is from no current epoch.
        val old = SentFlip.fromJson(sent.toJson().apply { remove("epoch") })!!
        assertEquals(SentFlipCheck.OtherEpoch, checkSent(old, 230, pairs, now = 2_000))
    }

    @Test
    fun theLastAllowedFlipSentStopsMore() {
        val quota = FlipQuota("Newbie", 3, 3, 2)
        val pairs = listOf(KeywordPair(4, listOf(1, 2), false))
        assertFalse(countSent(quota, pairs, SentFlip("0xtx", "h", 4, 0, 230), 230).first.canMake)
    }

    @Test
    fun theFingerprintFollowsThePictures() {
        val frames = frames()
        val a = draftFingerprint(frames, 3)
        assertEquals(a, draftFingerprint(frames.map { it.copyOf() }, 3))
        assertNotEquals("another anti-AI slot", a, draftFingerprint(frames, 2))
        assertNotEquals("one picture changed", a, draftFingerprint(frames.mapIndexed { i, f -> if (i == 1) f.copyOf().also { it[0] = 99 } else f }, 3))
        assertNotEquals("a picture missing", a, draftFingerprint(frames.mapIndexed { i, f -> if (i == 0) null else f }, 3))
    }

    @Test
    fun lateDraftWritesNeverWin() {
        val order = DraftWriteOrder()
        val first = order.next()
        val second = order.next()
        assertTrue(order.claim(second))
        assertFalse("an older write after a newer one", order.claim(first))
        val beforeSend = order.next()
        order.clear()
        assertFalse("a write asked before the flip was sent", order.claim(beforeSend))
        assertTrue("the next draft is written", order.claim(order.next()))
    }
}
