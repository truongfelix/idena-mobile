package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class WalletTest {
    @Test
    fun addressesAre0xAnd40HexDigits() {
        assertTrue(isAddress("0x5f3a9c1e7b2d4a6f8e0C1B3D5A7F9e2c4b6d8a01"))
        assertTrue("spaces around are ignored", isAddress(" 0x5f3a9c1e7b2d4a6f8e0C1B3D5A7F9e2c4b6d8a01 "))
        assertFalse(isAddress("5f3a9c1e7b2d4a6f8e0C1B3D5A7F9e2c4b6d8a01"))
        assertFalse(isAddress("0x5f3a9c1e7b2d4a6f8e0C1B3D5A7F9e2c4b6d8a0"))
        assertFalse(isAddress("0x5f3a9c1e7b2d4a6f8e0C1B3D5A7F9e2c4b6d8a0g"))
    }

    @Test
    fun amountsArePositiveWithAtMost18Decimals() {
        assertEquals(BigDecimal("12.5"), parseIdna("12.5"))
        assertEquals("a French keyboard's comma", BigDecimal("12.5"), parseIdna("12,5"))
        assertEquals(BigDecimal("0.000000000000000001"), parseIdna("0.000000000000000001"))
        assertNull("below the smallest unit", parseIdna("0.0000000000000000001"))
        assertNull(parseIdna("0"))
        assertNull(parseIdna("-1"))
        assertNull(parseIdna("+1"))
        assertNull(parseIdna(""))
        assertNull(parseIdna("1e3"))
        assertNull(parseIdna("abc"))
    }

    @Test
    fun aCandidateLosesEverythingAddedIfItMissesOrFails() {
        val preview = StakePreview.of("Candidate", 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("50"), BigDecimal("1000"))
        assertEquals(BigDecimal("50"), preview.newStake)
        assertEquals(0.0, preview.keptIfMissed!!, 0.0)
        assertEquals(0.0, preview.keptIfFailed!!, 0.0)
        assertFalse("a candidate is not validated: no discrimination", preview.belowThreshold)
    }

    @Test
    fun aHumanKeepsItsStakeAndAddedStakeIsNotLocked() {
        // 1,000 staked with 400 locked, add 600: a Human is only suspended, whatever happens.
        val human = StakePreview.of("Human", 10, BigDecimal("1000"), BigDecimal("400"), BigDecimal("600"), BigDecimal("1500"))
        assertEquals(1.0, human.keptIfMissed!!, 0.0)
        assertEquals(1.0, human.keptIfFailed!!, 0.0)
        assertFalse("1,600 is above the threshold", human.belowThreshold)
        // A Verified identity that fails is killed: the locked 400 burn, the added stake does not lock.
        val verified = StakePreview.of("Verified", 5, BigDecimal("1000"), BigDecimal("400"), BigDecimal("600"), null)
        assertEquals(1.0, verified.keptIfMissed!!, 0.0)
        assertEquals(0.0, verified.keptIfFailed!!, 0.0)
    }

    @Test
    fun aValidatedIdentityBelowTheThresholdIsDiscriminated() {
        assertTrue(StakePreview.of("Newbie", 1, BigDecimal("100"), BigDecimal.ZERO, BigDecimal("50"), BigDecimal("200")).belowThreshold)
        assertFalse(StakePreview.of("Newbie", 1, BigDecimal("100"), BigDecimal.ZERO, BigDecimal("100"), BigDecimal("200")).belowThreshold)
        assertFalse("no threshold set", StakePreview.of("Newbie", 1, BigDecimal("1"), BigDecimal.ZERO, BigDecimal("1"), BigDecimal.ZERO).belowThreshold)
    }

    @Test
    fun onlyLivingIdentitiesReceiveStake() {
        assertTrue(canReceiveStake("Candidate"))
        assertTrue(canReceiveStake("Human"))
        assertFalse(canReceiveStake("Undefined"))
        assertFalse(canReceiveStake("Killed"))
    }

    @Test
    fun anEmptyCommentSendsNoPayload() {
        assertNull(commentPayload(commentBytes("")))
        assertNull(commentPayload(commentBytes("  \n ")))
    }

    @Test
    fun aCommentIsItsTrimmedUtf8BytesAsTheDesktopWritesThem() {
        val invoice = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        // The desktop's test of the same comment (renderer/screens/wallets/utils.test.js), in lower case.
        assertEquals("0x" + invoice.toByteArray().joinToString("") { "%02x".format(it) }, commentPayload(commentBytes(" $invoice ")))
        assertEquals("0x636166c3a9", commentPayload(commentBytes("café")))
    }

    @Test
    fun aCommentsSizeIsCountedInBytes() {
        assertEquals(MAX_COMMENT_BYTES, commentBytes("é".repeat(MAX_COMMENT_BYTES / 2))!!.size)
        assertEquals(MAX_COMMENT_BYTES + 2, commentBytes("é".repeat(MAX_COMMENT_BYTES / 2 + 1))!!.size)
    }
}
