package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLevelTest {
    @Test
    fun ecoNormalHubWithNormalByDefault() {
        assertEquals(listOf("Eco", "Normal", "Hub"), PeerLevel.entries.map { it.label })
        assertEquals(PeerLevel.Normal, PeerLevel.DEFAULT)
        assertEquals(listOf(7, 18, 30), PeerLevel.entries.map { it.maxPeers })
    }

    @Test
    fun ecoHasIdenaGosLowPowerNumbers() {
        // idena-go config/flags.go LowPower*: own shard 3 in / 2 out, other shards 1 / 1.
        with(PeerLevel.Eco) {
            assertEquals(listOf(3, 1, 2, 1), listOf(inboundOwnShard, inboundOtherShards, outboundOwnShard, outboundOtherShards))
        }
    }

    @Test
    fun normalHasIdenaGosDefaults() {
        // idena-go config/flags.go Default*: own shard 8 in / 4 out, other shards 4 / 2.
        with(PeerLevel.Normal) {
            assertEquals(listOf(8, 4, 4, 2), listOf(inboundOwnShard, inboundOtherShards, outboundOwnShard, outboundOtherShards))
        }
    }

    @Test
    fun hubDoublesOnlyTheIncomingSlots() {
        assertEquals(PeerLevel.Normal.inbound * 2, PeerLevel.Hub.inbound)
        assertEquals(24, PeerLevel.Hub.inbound)
        assertEquals(PeerLevel.Normal.outboundOwnShard, PeerLevel.Hub.outboundOwnShard)
        assertEquals(PeerLevel.Normal.outboundOtherShards, PeerLevel.Hub.outboundOtherShards)
    }

    @Test
    fun aFileHoldsAnOfferedLevelElseTheDefault() {
        assertEquals(PeerLevel.Eco, PeerLevel.parse("eco"))
        assertEquals(PeerLevel.Hub, PeerLevel.parse(" hub\n"))
        assertEquals(PeerLevel.DEFAULT, PeerLevel.parse(null))
        assertEquals(PeerLevel.DEFAULT, PeerLevel.parse(""))
        assertEquals(PeerLevel.DEFAULT, PeerLevel.parse("Eco"))
        assertEquals(PeerLevel.DEFAULT, PeerLevel.parse("full"))
    }

    @Test
    fun ipfsLimitsInIncreasingOrderWithTheOfficialNodesByDefault() {
        assertEquals(listOf("20", "50", "100", "No limit"), IpfsConnections.entries.map { it.label })
        // idena-go's CLI default (LowWater 30, HighWater 50), as the desktop app.
        assertEquals(IpfsConnections.C50, IpfsConnections.DEFAULT)
        assertEquals(30, IpfsConnections.DEFAULT.low)
        // 0 / 0: Kubo's connection manager closes nothing, as before the setting.
        assertEquals(0, IpfsConnections.NoLimit.high)
        assertEquals(0, IpfsConnections.NoLimit.low)
        IpfsConnections.entries.filter { it != IpfsConnections.NoLimit }.forEach { assertTrue(it.low < it.high) }
    }

    @Test
    fun aFileHoldsAnOfferedLimitElseTheDefault() {
        assertEquals(IpfsConnections.C20, IpfsConnections.parse("20"))
        assertEquals(IpfsConnections.C100, IpfsConnections.parse("100\n"))
        assertEquals(IpfsConnections.NoLimit, IpfsConnections.parse("0"))
        assertEquals(IpfsConnections.DEFAULT, IpfsConnections.parse(null))
        assertEquals(IpfsConnections.DEFAULT, IpfsConnections.parse("30"))
    }

    @Test
    fun aLimitLeavesRoomForTheLevelsPeers() {
        // As the info says: 20 needs Eco, 50 Eco or Normal.
        assertEquals(listOf(PeerLevel.Eco), PeerLevel.entries.filter { IpfsConnections.C20.allows(it) })
        assertEquals(listOf(PeerLevel.Eco, PeerLevel.Normal), PeerLevel.entries.filter { IpfsConnections.C50.allows(it) })
        assertEquals(PeerLevel.entries, PeerLevel.entries.filter { IpfsConnections.C100.allows(it) })
        assertEquals(PeerLevel.entries, PeerLevel.entries.filter { IpfsConnections.NoLimit.allows(it) })
        IpfsConnections.entries.forEach { limit ->
            PeerLevel.entries.filter { limit.allows(it) && limit != IpfsConnections.NoLimit }.forEach { level ->
                assertTrue(limit.low >= level.maxPeers + IPFS_CONNECTIONS_MARGIN)
            }
        }
    }

    @Test
    fun aLimitTooLowForTheLevelIsRaisedToTheNextItAllows() {
        assertEquals(IpfsConnections.C20, IpfsConnections.C20.atLeastFor(PeerLevel.Eco))
        assertEquals(IpfsConnections.C50, IpfsConnections.C20.atLeastFor(PeerLevel.Normal))
        assertEquals(IpfsConnections.C100, IpfsConnections.C20.atLeastFor(PeerLevel.Hub))
        assertEquals(IpfsConnections.C100, IpfsConnections.C50.atLeastFor(PeerLevel.Hub))
        // Never lowered.
        assertEquals(IpfsConnections.NoLimit, IpfsConnections.NoLimit.atLeastFor(PeerLevel.Eco))
        assertEquals(IpfsConnections.C100, IpfsConnections.C100.atLeastFor(PeerLevel.Eco))
    }

    @Test
    fun theDefaultConfigHasTheOfficialNodesPeersAndIpfsLimit() {
        val config = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions()))
        val p2p = config.getJSONObject("P2P")
        assertEquals(8, p2p.getInt("MaxInboundOwnShardPeers"))
        assertEquals(4, p2p.getInt("MaxInboundPeers"))
        assertEquals(4, p2p.getInt("MaxOutboundOwnShardPeers"))
        assertEquals(2, p2p.getInt("MaxOutboundPeers"))
        val ipfs = config.getJSONObject("IpfsConf")
        assertEquals(30, ipfs.getInt("LowWater"))
        assertEquals(50, ipfs.getInt("HighWater"))
        assertEquals("40s", ipfs.getString("GracePeriod"))
    }

    @Test
    fun noLimitStaysOnOffer() {
        val ipfs = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions(ipfsConnections = IpfsConnections.NoLimit))).getJSONObject("IpfsConf")
        assertEquals(0, ipfs.getInt("LowWater"))
        assertEquals(0, ipfs.getInt("HighWater"))
    }

    @Test
    fun theConfigCarriesTheLevelAndTheLimit() {
        val config = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions(peerLevel = PeerLevel.Hub, ipfsConnections = IpfsConnections.C100)))
        val p2p = config.getJSONObject("P2P")
        assertEquals(16, p2p.getInt("MaxInboundOwnShardPeers"))
        assertEquals(8, p2p.getInt("MaxInboundPeers"))
        assertEquals(4, p2p.getInt("MaxOutboundOwnShardPeers"))
        assertEquals(2, p2p.getInt("MaxOutboundPeers"))
        val ipfs = config.getJSONObject("IpfsConf")
        assertEquals(50, ipfs.getInt("LowWater"))
        assertEquals(100, ipfs.getInt("HighWater"))
        // The other settings stay.
        assertEquals(4, ipfs.getInt("DatastoreWriteBufferMiB"))
        assertTrue(ipfs.getBoolean("StaticPort"))
    }

    @Test
    fun theConfigNeverHoldsALimitTooLowForTheLevel() {
        val config = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions(peerLevel = PeerLevel.Hub, ipfsConnections = IpfsConnections.C20)))
        assertEquals(100, config.getJSONObject("IpfsConf").getInt("HighWater"))
    }

    @Test
    fun pendingListsWhatTheNodeRunsWithout() {
        val chosen = NodeOptions(
            writeBuffers = mapOf(NodeDatabase.Chain to WriteBuffer.MiB32, NodeDatabase.Ipfs to WriteBuffer.MiB4),
            peerLevel = PeerLevel.Hub,
            ipfsConnections = IpfsConnections.C100,
        )
        val running = RunningOptions(
            writeBuffers = mapOf(NodeDatabase.Chain to WriteBuffer.MiB4, NodeDatabase.Ipfs to WriteBuffer.MiB4),
            peerLevel = PeerLevel.Normal,
            ipfsConnections = IpfsConnections.C100,
        )
        assertEquals(
            listOf(PendingChange("Peer level", "Hub"), PendingChange("Chain database buffer", "32 MiB")),
            pendingChanges(syncEnabled = true, chosen, running),
        )
        assertEquals(emptyList<PendingChange>(), pendingChanges(syncEnabled = false, chosen, running))
    }

    @Test
    fun aSettingNoStartRecordedIsNotPending() {
        // The node was started by an app without the peer level: nothing to compare.
        val chosen = NodeOptions(peerLevel = PeerLevel.Eco, ipfsConnections = IpfsConnections.C20)
        val running = RunningOptions(writeBuffers = mapOf(NodeDatabase.Chain to WriteBuffer.MiB4, NodeDatabase.Ipfs to WriteBuffer.MiB4))
        assertEquals(emptyList<PendingChange>(), pendingChanges(syncEnabled = true, chosen, running))
        assertFalse(settingPending(syncEnabled = true, running = running.peerLevel, chosen = chosen.peerLevel))
    }
}
