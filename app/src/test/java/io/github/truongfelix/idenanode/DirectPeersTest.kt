package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPeersTest {
    // Valid and invalid ids as idena-go's peer.Decode (go-libp2p v0.48) takes them, checked 2026-10-08.
    private val rsaId = "QmNqkSwad5HTShxVzFcYLQkRCRjrs9ZhQykqrRTQcdR7xp"
    private val ed25519Id = "12D3KooWKgas1i8Ua9oBEC8wiKfhfdo47idCUXnmratPUpLjthm8"
    private val otherEd25519Id = "12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN"
    private val thirdId = "QmTseSBwV9xPN2iEn6ViZbdPbk5MBk1HAD9SKy8B2EgSrY"

    @Test
    fun peerIdsAsTheNodeTakesThem() {
        assertTrue(isPeerId(rsaId))
        assertTrue(isPeerId(ed25519Id))
        assertTrue(isPeerId(otherEd25519Id))
        // peer.Decode refuses each of these.
        assertFalse(isPeerId(rsaId.dropLast(1)))
        assertFalse(isPeerId(rsaId + "p"))
        assertFalse(isPeerId(rsaId.replace("R7xp", "R70p")))
        assertFalse(isPeerId(""))
        assertFalse(isPeerId("/ip4/1.2.3.4/tcp/40405/p2p/$rsaId"))
        assertFalse(isPeerId("0x34004a3e0000000000000000000000000000b850"))
    }

    @Test
    fun codeCarriesTheIdAndTheName() {
        assertEquals("idena-peer:$rsaId#Felix desktop", peerCode(rsaId, "Felix desktop"))
        assertEquals("idena-peer:$rsaId", peerCode(rsaId, "  "))
        assertEquals(DirectPeer(rsaId, "Felix desktop"), parsePeerCode(peerCode(rsaId, "Felix desktop")))
        assertEquals(DirectPeer(ed25519Id, ""), parsePeerCode(peerCode(ed25519Id, "")))
    }

    @Test
    fun pastedTextIsReadLeniently() {
        assertEquals(DirectPeer(rsaId, ""), parsePeerCode("  $rsaId\n"))
        assertEquals(DirectPeer(rsaId, "Anna"), parsePeerCode("IDENA-PEER:$rsaId # Anna "))
        assertEquals(DirectPeer(rsaId, "A#B"), parsePeerCode("idena-peer:$rsaId#A#B"))
        assertNull(parsePeerCode("idena-peer:"))
        assertNull(parsePeerCode("idena-peer:${rsaId.dropLast(1)}#Anna"))
        assertNull(parsePeerCode("hello"))
    }

    @Test
    fun namesAreOneShortLine() {
        assertEquals("Anna desktop", cleanPeerName("  Anna desktop "))
        assertEquals("Anna desktop", cleanPeerName("Anna\ndesktop"))
        assertEquals("Anna", cleanPeerName("Anna\n"))
        assertEquals(40, cleanPeerName("x".repeat(100)).length)
    }

    @Test
    fun theListRefusesOwnNodeDuplicatesAndAFourth() {
        val list = listOf(DirectPeer(rsaId, "A"), DirectPeer(ed25519Id, "B"))
        assertEquals("This is your own node's code.", directPeerProblem(DirectPeer(thirdId, ""), list, ownId = thirdId))
        assertEquals("This node is already in the list.", directPeerProblem(DirectPeer(rsaId, "again"), list, ownId = null))
        assertNull(directPeerProblem(DirectPeer(thirdId, "C"), list, ownId = null))
        val full = list + DirectPeer(thirdId, "C")
        assertEquals("The list holds at most 3 nodes.", directPeerProblem(DirectPeer(otherEd25519Id, "D"), full, ownId = null))
    }

    @Test
    fun ownIdComesFromTheIpfsAddress() {
        assertEquals(rsaId, ownPeerId("/ip4/0.0.0.0/tcp/40405/ipfs/$rsaId"))
        assertEquals(ed25519Id, ownPeerId("/ip4/0.0.0.0/tcp/40405/p2p/$ed25519Id"))
        assertNull(ownPeerId(null))
        assertNull(ownPeerId("/ip4/0.0.0.0/tcp/40405"))
    }

    @Test
    fun storedListDropsWhatTheNodeWouldRefuse() {
        val stored = JSONArray()
            .put(JSONObject().put("id", rsaId).put("name", "A"))
            .put(JSONObject().put("id", "not-an-id").put("name", "bad"))
            .put(JSONObject().put("id", rsaId).put("name", "twice"))
            .put(JSONObject().put("id", ed25519Id).put("name", "B"))
            .put(JSONObject().put("id", thirdId).put("name", "C"))
            .put(JSONObject().put("id", otherEd25519Id).put("name", "D"))
            .toString()
        assertEquals(listOf(rsaId, ed25519Id, thirdId), parseDirectPeers(stored).map { it.id })
        assertEquals(emptyList<DirectPeer>(), parseDirectPeers(null))
        assertEquals(emptyList<DirectPeer>(), parseDirectPeers("{broken"))
        val list = listOf(DirectPeer(rsaId, "A"), DirectPeer(ed25519Id, ""))
        assertEquals(list, parseDirectPeers(directPeersJson(list)))
    }

    @Test
    fun nodeConfigCarriesTheIds() {
        val none = JSONObject(NodeSettings.nodeConfig("abc123", NodeOptions())).getJSONObject("P2P")
        assertEquals(0, none.getJSONArray("DirectPeers").length())
        val options = NodeOptions(directPeers = listOf(DirectPeer(rsaId, "A"), DirectPeer(ed25519Id, "B")))
        val p2p = JSONObject(NodeSettings.nodeConfig("abc123", options)).getJSONObject("P2P")
        val ids = p2p.getJSONArray("DirectPeers")
        assertEquals(listOf(rsaId, ed25519Id), (0 until ids.length()).map { ids.getString(it) })
        // The peer level's slots stay as they were.
        assertEquals(PeerLevel.DEFAULT.inboundOwnShard, p2p.getInt("MaxInboundOwnShardPeers"))
    }

    @Test
    fun aNewListAsksForARestartButARenameDoesNot() {
        val chosen = NodeOptions(directPeers = listOf(DirectPeer(rsaId, "renamed")))
        val pending = pendingChanges(true, chosen, RunningOptions(directPeers = emptyList()))
        assertEquals(listOf(PendingChange(DIRECT_PEERS_TITLE, "renamed")), pending)
        assertEquals(emptyList<PendingChange>(), pendingChanges(true, chosen, RunningOptions(directPeers = listOf(rsaId))))
        // An older app recorded no list: nothing to compare with.
        assertEquals(emptyList<PendingChange>(), pendingChanges(true, chosen, RunningOptions(directPeers = null)))
        val removed = pendingChanges(true, NodeOptions(), RunningOptions(directPeers = listOf(rsaId)))
        assertEquals(listOf(PendingChange(DIRECT_PEERS_TITLE, "none")), removed)
    }

    @Test
    fun rowStateFollowsTheNode() {
        val running = RunningOptions(directPeers = listOf(rsaId, ed25519Id))
        val node = status(peerIds = setOf(rsaId, thirdId))
        val state = ScreenState(status = node, runningOptions = running)
        assertEquals(DirectPeerState.Connected, directPeerState(rsaId, state))
        assertEquals(DirectPeerState.NotConnected, directPeerState(ed25519Id, state))
        assertEquals(DirectPeerState.AfterRestart, directPeerState(otherEd25519Id, state))
        assertEquals(DirectPeerState.NodeStopped, directPeerState(rsaId, state.copy(status = null)))
    }

    @Test
    fun peerIdsFromNetPeers() {
        val answer = JSONArray()
            .put(JSONObject().put("id", rsaId).put("addr", "/ip4/1.2.3.4/tcp/40405").put("direct", true))
            .put(JSONObject().put("id", thirdId).put("addr", "/ip4/5.6.7.8/tcp/40405"))
        assertEquals(setOf(rsaId, thirdId), peerIds(answer))
        assertEquals(emptySet<String>(), peerIds(null))
    }

    private fun status(peerIds: Set<String>) = NodeStatus(
        address = "0x1", currentBlock = 100, highestBlock = 100, stateBlock = 100, syncing = false, wrongTime = false,
        peers = peerIds.size, identityState = "Candidate", age = 0, online = false, delegatee = null, isPool = false,
        stake = "0", balance = "0", nextValidation = "", period = "None", requiredFlips = 0, madeFlips = 0,
        availableFlips = 0, lockedStake = "0", epoch = 1, delegationEpoch = 0, penaltySeconds = 0, peerIds = peerIds,
    )
}
