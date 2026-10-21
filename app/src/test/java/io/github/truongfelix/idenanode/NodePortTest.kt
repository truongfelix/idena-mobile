package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePortTest {
    @Test
    fun thePortsAreTheOfficialOneAndTheFourAfterIt() {
        assertEquals(40405, DEFAULT_NODE_PORT)
        assertEquals(listOf(40405, 40406, 40407, 40408, 40409), NODE_PORTS)
    }

    @Test
    fun aSavedPortIsReadOnlyWhenItIsOneOfThem() {
        assertEquals(40407, parseNodePort("40407"))
        assertEquals(40406, parseNodePort(" 40406\n"))
        // None saved (the app before the setting), damaged, or out of the list: the default.
        assertEquals(DEFAULT_NODE_PORT, parseNodePort(null))
        assertEquals(DEFAULT_NODE_PORT, parseNodePort(""))
        assertEquals(DEFAULT_NODE_PORT, parseNodePort("4040x"))
        assertEquals(DEFAULT_NODE_PORT, parseNodePort("50506"))
    }

    @Test
    fun theNodeListensOnThePortChosenAndNeverMovesOffIt() {
        val ipfs = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions(nodePort = 40406))).getJSONObject("IpfsConf")
        assertEquals(40406, ipfs.getInt("IpfsPort"))
        assertTrue(ipfs.getBoolean("StaticPort"))
        val default = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions())).getJSONObject("IpfsConf")
        assertEquals(40405, default.getInt("IpfsPort"))
    }

    @Test
    fun anotherPortWaitsForTheNextStart() {
        val running = RunningOptions(
            peerLevel = PeerLevel.DEFAULT,
            ipfsConnections = IpfsConnections.DEFAULT,
            directPeers = emptyList(),
            nodePort = 40405,
        )
        assertEquals(
            listOf(PendingChange("Node port", "40406")),
            pendingChanges(syncEnabled = true, NodeOptions(nodePort = 40406), running),
        )
        assertEquals(emptyList<PendingChange>(), pendingChanges(syncEnabled = true, NodeOptions(), running))
        // A start of an app before the setting recorded no port: nothing is offered.
        assertEquals(emptyList<PendingChange>(), pendingChanges(syncEnabled = true, NodeOptions(nodePort = 40406), running.copy(nodePort = null)))
    }

    @Test
    fun theFreePortIsTheNextOneNoOtherDeviceHolds() {
        val others = mutableSetOf(40405)
        val owner = { port: Int -> if (port in others) MappingOwner.Other else MappingOwner.None }
        assertEquals(40406, nextFreePort(40405, owner))
        others += 40406
        assertEquals(40407, nextFreePort(40405, owner))
        // After the last port, from the first; the phone's own forwarding counts as free.
        others.clear()
        others += listOf(40409, 40405)
        assertEquals(40406, nextFreePort(40409, owner))
        assertEquals(40406, nextFreePort(40405) { if (it == 40406) MappingOwner.Phone else MappingOwner.Other })
        // All held by other devices: none to offer.
        assertNull(nextFreePort(40405) { MappingOwner.Other })
    }
}
