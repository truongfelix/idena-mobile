package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    private fun protection(state: String, age: Int, outcome: Outcome, stake: Double = 1000.0, locked: Double = 0.0) =
        stakeProtection(state, age, stake, locked, outcome)

    @Test
    fun suspendedInsteadOfKilledKeepsTheWholeStake() {
        // Verified and Human are suspended when they miss, Suspended becomes Zombie, Human is only suspended when it fails.
        for ((state, outcome) in listOf("Verified" to Outcome.Miss, "Human" to Outcome.Miss, "Suspended" to Outcome.Miss, "Human" to Outcome.Fail)) {
            assertEquals("$state $outcome", 1.0, protection(state, 5, outcome, locked = 400.0)!!, 0.0)
        }
    }

    @Test
    fun killedNewIdentitiesLoseEverything() {
        for (state in listOf("Candidate", "Newbie")) {
            assertEquals(0.0, protection(state, 1, Outcome.Miss)!!, 0.0)
            assertEquals(0.0, protection(state, 1, Outcome.Fail)!!, 0.0)
        }
        assertEquals("a Verified identity that fails is killed", 0.0, protection("Verified", 3, Outcome.Fail)!!, 0.0)
    }

    @Test
    fun oldSuspendedOrZombieKeepsMostOfItsFreeStake() {
        assertEquals("age 4: all burnt", 0.0, protection("Zombie", 4, Outcome.Miss)!!, 0.0)
        assertEquals("age 5: 5% burnt", 0.95, protection("Suspended", 5, Outcome.Fail)!!, 1e-9)
        assertEquals("age 9: 1% burnt", 0.99, protection("Zombie", 9, Outcome.Miss)!!, 1e-9)
        assertEquals("age 12: nothing burnt", 1.0, protection("Zombie", 12, Outcome.Fail)!!, 1e-9)
        // The locked stake is always burnt: 95% of the 600 free coins are kept out of 1,000.
        assertEquals(0.57, protection("Suspended", 5, Outcome.Fail, locked = 400.0)!!, 1e-9)
    }

    @Test
    fun noProtectionWithoutStakeOrForDeadIdentities() {
        assertNull(protection("Human", 20, Outcome.Miss, stake = 0.0))
        assertNull(protection("Killed", 20, Outcome.Miss))
        assertNull(protection("Undefined", 0, Outcome.Fail))
    }

    @Test
    fun taskBetweenValidations() {
        assertEquals("Create 3 required flips", currentTask("None", "Verified", 3, 5, 0, false))
        assertEquals("Create 1 required flip", currentTask("None", "Human", 3, 5, 2, false))
        assertEquals("Wait for the validation or create 2 optional flips", currentTask("None", "Human", 3, 5, 3, false))
        assertEquals("Wait for the validation", currentTask("None", "Newbie", 3, 3, 3, false))
        assertEquals("Wait for the validation", currentTask("None", "Candidate", 0, 0, 0, false))
        assertEquals("Accept the invitation (on app.idena.io)", currentTask("None", "Invite", 0, 0, 0, false))
    }

    @Test
    fun taskDuringTheCeremony() {
        assertEquals("Shuffling flips…", currentTask("FlipLottery", "Human", 3, 5, 3, false))
        assertEquals("Validate", currentTask("ShortSession", "Verified", 3, 5, 3, false))
        assertEquals("Wait for the validation end", currentTask("LongSession", "Verified", 3, 5, 3, true))
        assertEquals("Wait for the validation end", currentTask("AfterLongSession", "Verified", 3, 5, 3, false))
    }

    @Test
    fun miningActionsFollowTheNodesChecks() {
        fun actions(period: String = "None", state: String = "Human", online: Boolean = false, isPool: Boolean = false,
                    delegated: Boolean = false, delegationEpoch: Int = 0, epoch: Int = 229, penalty: Long = 0) =
            MiningActions.of(period, state, online, isPool, delegated, delegationEpoch, epoch, penalty)

        assertEquals(MiningActions(canStartMining = true, canStopMining = false, canDelegate = true, canUndelegate = false), actions())
        assertEquals(MiningActions(false, false, false, false), actions(period = "LongSession"))
        assertTrue(actions(online = true).canStopMining)
        assertFalse("a candidate cannot mine", actions(state = "Candidate").canStartMining)
        assertFalse("a delegated identity cannot mine itself", actions(delegated = true, delegationEpoch = 200).canStartMining)
        assertFalse("undelegating waits for the next epoch", actions(delegated = true, delegationEpoch = 229).canUndelegate)
        assertTrue(actions(delegated = true, delegationEpoch = 228).canUndelegate)
        assertFalse("no delegation with a mining penalty", actions(penalty = 3600).canDelegate)
        assertFalse("a pool cannot delegate", actions(isPool = true).canDelegate)
    }
}
