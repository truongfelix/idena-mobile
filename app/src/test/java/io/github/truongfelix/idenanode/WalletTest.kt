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
        assertTrue(isAddress("0x022353d8a3e5852f2eB8DF3e23a7b0f738752536"))
        assertTrue("spaces around are ignored", isAddress(" 0x022353d8a3e5852f2eB8DF3e23a7b0f738752536 "))
        assertFalse(isAddress("022353d8a3e5852f2eB8DF3e23a7b0f738752536"))
        assertFalse(isAddress("0x022353d8a3e5852f2eB8DF3e23a7b0f73875253"))
        assertFalse(isAddress("0x022353d8a3e5852f2eB8DF3e23a7b0f73875253g"))
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
}
