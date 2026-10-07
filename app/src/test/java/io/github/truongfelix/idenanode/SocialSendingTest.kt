package io.github.truongfelix.idenanode

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

class SocialSendingTest {
    private val inBlock = JSONObject().put("blockHash", "0x" + "ab".repeat(32))
    private val inPool = JSONObject().put("blockHash", "0x" + "00".repeat(32))

    /** A node answering each lookup of the transaction with the next of [answers] (null: unknown, an exception: no answer). */
    private class NodeAnswers(private val answers: List<Any?>, private val receipt: JSONObject? = null) : TxLookup {
        var lookups = 0
        val pauses = ArrayList<Long>()

        override fun transaction(hash: String): JSONObject? {
            val answer = answers[minOf(lookups++, answers.lastIndex)]
            if (answer is Exception) throw answer
            return answer as JSONObject?
        }

        override fun blockHeight(blockHash: String): Long = 11_400_000

        override fun receipt(hash: String): JSONObject? = receipt
    }

    private fun wait(node: NodeAnswers): InBlock? = runBlocking { waitForBlock(node, "0x01") { node.pauses += it } }

    @Test
    fun aSendInABlockGivesItsHeight() {
        val node = NodeAnswers(listOf(inPool, inPool, inBlock), JSONObject().put("success", true))
        assertEquals(InBlock(11_400_000, null), wait(node))
        assertEquals(3, node.lookups)
        assertEquals(listOf(BLOCK_POLL_MS, BLOCK_POLL_MS), node.pauses)
    }

    @Test
    fun aCallTheContractRefusedIsReportedWithItsError() {
        val node = NodeAnswers(listOf(inBlock), JSONObject().put("success", false).put("error", "insufficient funds"))
        assertEquals(InBlock(11_400_000, "insufficient funds"), wait(node))
        // Without the contract's words.
        assertEquals("the contract refused it", wait(NodeAnswers(listOf(inBlock), JSONObject().put("success", false)))?.error)
        // No receipt: nothing says it was refused.
        assertNull(wait(NodeAnswers(listOf(inBlock), null))?.error)
    }

    @Test
    fun aSendTheNodeDropsEndsTheWait() {
        val node = NodeAnswers(listOf(inPool, null, null, null, inBlock))
        assertNull(wait(node))
        assertEquals(1 + UNKNOWN_LOOKUPS, node.lookups)
    }

    @Test
    fun aNodeThatDoesNotAnswerIsAskedAgain() {
        // Two unknowns, then no answer (a restart): the count starts again, and the send comes in a block.
        val node = NodeAnswers(listOf(null, null, IOException("connection refused"), null, null, inBlock))
        assertEquals(InBlock(11_400_000, null), wait(node))
        assertEquals(6, node.lookups)
    }

    @Test
    fun aLikeStaysPendingUntilTheScanReadsItsBlock() {
        val sent = SentList()
        sent.add("0xlike", likeOf = 42)
        sent.add("0xpost", likeOf = null)
        assertEquals(setOf(42L), sent.likes)
        // A scan before its block is known keeps it.
        sent.scanned(11_399_990)
        assertEquals(setOf(42L), sent.likes)
        sent.inBlock("0xlike", 11_400_000)
        assertEquals(setOf(42L), sent.likes)
        sent.scanned(11_399_999)
        assertEquals(setOf(42L), sent.likes)
        sent.scanned(11_400_000)
        assertTrue(sent.likes.isEmpty())
        assertEquals(listOf("0xpost"), sent.items.map { it.hash })
    }

    @Test
    fun aLikeInABlockTheScanReadAlreadyLeavesAtOnce() {
        val sent = SentList()
        sent.scanned(11_400_005)
        sent.add("0xlike", likeOf = 42)
        sent.inBlock("0xlike", 11_400_003)
        assertTrue(sent.items.isEmpty())
    }

    @Test
    fun aDroppedLikeCanBeSentAgain() {
        val sent = SentList()
        sent.add("0xlike", likeOf = 42)
        sent.remove("0xlike")
        assertFalse(42L in sent.likes)
    }

    @Test
    fun aFileStoredByAnEarlierTryCostsNoNewFee() {
        val fee = PostFee(BigDecimal("0.03"), BigDecimal("0.06"))
        val image = IpfsFile("image", ByteArray(120_000), "bafyimage", BigDecimal.ZERO, stored = true)
        val text = IpfsFile("text", ByteArray(3_000), "bafytext", BigDecimal("0.02"))
        val pending = PendingPost("Post", SocialPosting.post("{}"), "0xaaaa000000000000000000000000000000000001", fee, null, listOf(text, image))
        val shown = confirmText(pending)
        assertTrue(shown.contains("Storing the text on IPFS (3 KB): about 0.02 iDNA"))
        assertTrue(shown.contains("The image is already stored on IPFS: no new fee."))
        assertFalse(shown.contains("Storing the image"))
        assertEquals(BigDecimal("0.05"), pending.totalFee)
    }
}
