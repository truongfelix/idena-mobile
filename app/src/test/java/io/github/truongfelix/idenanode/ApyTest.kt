package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ApyTest {
    @Test
    fun finalCommitteeIs70PercentOfTheValidators() {
        assertEquals(5, finalCommitteeSize(5))
        assertEquals("all of them up to 8", 8, finalCommitteeSize(8))
        assertEquals(6, finalCommitteeSize(9))
        assertEquals(30, finalCommitteeSize(43))
        assertEquals("at most 100", 100, finalCommitteeSize(200))
    }

    @Test
    fun equalStakesShareTheWholeBlockRewardEqually() {
        // Each block mints 6 iDNA to the proposer and the committee: with equal weights every validator
        // expects 6 / N per block.
        for (validators in listOf(3, 9, 43, 200)) {
            val weight = stakeWeight(5000.0)
            assertEquals("$validators validators", 6.0 / validators, miningRewardPerBlock(weight, validators, weight), 1e-9)
        }
    }

    @Test
    fun withEqualStakesTheProposerGetsTheBlockRewardAndMembersTheCommitteeReward() {
        val weight = stakeWeight(5000.0)
        assertEquals(1.0, blockReward(weight, proposer = true, member = false, committee = 30, averageWeight = weight), 1e-9)
        assertEquals(5.0 / 30, blockReward(weight, proposer = false, member = true, committee = 30, averageWeight = weight), 1e-9)
        assertEquals(1.0 + 5.0 / 30, blockReward(weight, proposer = true, member = true, committee = 30, averageWeight = weight), 1e-9)
    }

    @Test
    fun aBiggerStakeEarnsMoreMiningButLessThanProportionally() {
        val average = stakeWeight(1000.0)
        val single = miningRewardPerBlock(stakeWeight(1000.0), 43, average)
        val tenTimes = miningRewardPerBlock(stakeWeight(10_000.0), 43, average)
        assertTrue(tenTimes > single)
        assertTrue("stake^0.9 and the fixed shares", tenTimes < 10 * single)
    }

    @Test
    fun stakingShareOfOneDay() {
        // One day = 4,320 blocks x 6 iDNA x 18% = 4,665.6 iDNA shared by weight; a quarter of the weight here.
        val weight = stakeWeight(1000.0)
        val net = NetworkRewards(totalStakingWeight = 4 * weight, validators = 0, averageValidatorWeight = 0.0, epochDays = 1.0, proposedBlockShare = 1.0)
        val result = apy(1000.0, net)!!
        assertEquals(1166.4, result.stakingPerEpoch, 1e-6)
        assertEquals(0.0, result.miningPerEpoch, 0.0)
        assertEquals(1166.4 / 1000 * 365, result.yearly, 1e-6)
    }

    @Test
    fun miningCountsOnlyProposedBlocks() {
        val weight = stakeWeight(1000.0)
        val net = NetworkRewards(1e12, validators = 43, averageValidatorWeight = weight, epochDays = 1.0, proposedBlockShare = 0.9)
        assertEquals(4320 * 0.9 * 6.0 / 43, apy(1000.0, net)!!.miningPerEpoch, 1e-6)
    }

    @Test
    fun noApyWithoutData() {
        val net = NetworkRewards(1.0, 10, 1.0, 5.0, 1.0)
        assertNull(apy(0.0, net))
        assertNull(apy(100.0, net.copy(epochDays = 0.0)))
        assertNull(apy(100.0, net.copy(totalStakingWeight = 0.0)))
    }

    @Test
    fun stakeWeightIsStakeToThePowerOf09() {
        assertEquals(1000.0.pow(0.9), stakeWeight(1000.0), 1e-9)
        assertEquals(0.0, stakeWeight(0.0), 0.0)
    }
}
