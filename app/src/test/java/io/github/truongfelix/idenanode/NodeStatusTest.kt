package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeStatusTest {
    private fun status(syncing: Boolean, online: Boolean = false, delegatee: String? = null) = NodeStatus(
        address = "0x1", currentBlock = 100, highestBlock = 100, stateBlock = 100, syncing = syncing, wrongTime = false,
        peers = 4, identityState = "Candidate", age = 0, online = online, delegatee = delegatee, isPool = false,
        stake = "0", balance = "0", nextValidation = "", period = "None", requiredFlips = 0, madeFlips = 0,
        availableFlips = 0, lockedStake = "0", epoch = 1, delegationEpoch = 0, penaltySeconds = 0,
    )

    @Test
    fun offlineIsOnlySaidWhileSyncing() {
        assertEquals("Not mining", status(syncing = false).miningStatus)
        assertEquals("Not mining (offline)", status(syncing = true).miningStatus)
        assertEquals("Mining (online)", status(syncing = false, online = true).miningStatus)
        assertEquals("Delegated to pool 0xpool", status(syncing = false, delegatee = "0xpool").miningStatus)
    }
}
