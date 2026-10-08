package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SocialSendingTest {
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
