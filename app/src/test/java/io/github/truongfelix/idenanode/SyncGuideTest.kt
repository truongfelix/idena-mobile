package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGuideTest {
    private val now = 1_760_000_000L

    /** A phone with a key, Sync on, on Wi-Fi, the battery setting allowed, not a Xiaomi; [node] as given. */
    private fun input(
        node: NodeView?,
        hasKey: Boolean = true,
        syncEnabled: Boolean = true,
        runningForMs: Long? = 60_000,
        onWifi: Boolean = true,
        battery: Boolean = true,
        xiaomi: Boolean = false,
        chainMoving: Boolean = false,
        startError: String? = null,
    ) = GuideInput(
        hasKey = hasKey, syncEnabled = syncEnabled, startError = startError, node = node, runningForMs = runningForMs,
        chainMoving = chainMoving, speed = null, startPhase = null, onWifi = onWifi, ignoringBatteryOptimizations = battery,
        xiaomi = xiaomi, nowSeconds = now,
    )

    private fun node(peers: Int, syncing: Boolean, chain: Long, headers: Long, highest: Long, headTime: Long? = now - 20) =
        NodeView(peers, syncing, headers, highest, validation = false, chainBlock = chain, headTime = headTime)

    @Test
    fun theStepsInOrder() {
        assertEquals(GuideStep.Account, guideStep(input(null, hasKey = false, syncEnabled = false)))
        assertEquals(GuideStep.Start, guideStep(input(null, syncEnabled = false)))
        assertEquals("could not start", GuideStep.Start, guideStep(input(null, startError = "disk full")))
        assertEquals("not answering yet", GuideStep.Peers, guideStep(input(null)))
        assertEquals(GuideStep.Peers, guideStep(input(node(0, true, 1, 1, 0))))
        assertEquals("headers", GuideStep.Network, guideStep(input(node(3, true, 1, 6_000_000, 11_430_000))))
        // Galaxy, 2026-10-12 19:30: headers at the top, the chain in 2022: waiting for the snapshot.
        assertEquals(GuideStep.Network, guideStep(input(node(1, true, 4_873_470, 11_429_095, 11_430_011))))
        assertEquals("after the snapshot", GuideStep.Blocks, guideStep(input(node(5, true, 11_429_095, 11_429_095, 11_430_011))))
        assertEquals("synced but its head is old", GuideStep.Blocks, guideStep(input(node(5, false, 11_430_011, 11_430_011, 11_430_011, headTime = now - 3600))))
        assertEquals(GuideStep.Reading, guideStep(input(node(5, false, 11_430_011, 11_430_011, 11_430_011))))
    }

    @Test
    fun aStepFindingNoPeersExplainsWhy() {
        val noPeers = node(0, true, 1, 1, 0)
        assertEquals("not before 2 minutes", emptyList<GuideHint>(), syncGuide(input(noPeers, runningForMs = 60_000), 0).hints)
        assertEquals(listOf(GuideHint.PeersTakeTime, GuideHint.PriorityPeers), syncGuide(input(noPeers, runningForMs = 3 * 60_000), 0).hints)
        // On mobile data (or a hotspot), other nodes cannot reach the phone: Wi-Fi helps.
        assertEquals(listOf(GuideHint.WifiHelps, GuideHint.PriorityPeers), syncGuide(input(noPeers, runningForMs = 3 * 60_000, onWifi = false), 0).hints)
    }

    @Test
    fun theSnapshotWaitIsExplainedAndALongOneToo() {
        val waiting = node(1, true, 4_873_470, 11_429_095, 11_430_011)
        assertEquals(listOf(GuideHint.SnapshotWaits), syncGuide(input(waiting), 60_000).hints)
        // 2026-10-12: a stale snapshot held a phone at 30% for hours.
        assertEquals(listOf(GuideHint.SnapshotWaits, GuideHint.SnapshotLong, GuideHint.PriorityPeers), syncGuide(input(waiting), GUIDE_SNAPSHOT_LONG_MS).hints)
        assertEquals("Waiting for the snapshot", syncGuide(input(waiting), 0).line)
        assertTrue(GuideHint.SnapshotLong.text(42).contains("42"))
    }

    @Test
    fun theBackgroundSettingsWhileTheNodeRuns() {
        val syncing = node(5, true, 11_429_095, 11_429_095, 11_430_011)
        assertEquals(listOf(GuideHint.CatchesUp), syncGuide(input(syncing), 0).hints)
        assertEquals(listOf(GuideHint.CatchesUp, GuideHint.Background), syncGuide(input(syncing, battery = false), 0).hints)
        // MIUI stops the app after the screen locks unless Autostart is on, the battery setting allowed or not.
        assertEquals(listOf(GuideHint.CatchesUp, GuideHint.Background), syncGuide(input(syncing, xiaomi = true), 0).hints)
        assertEquals("not before the node runs", emptyList<GuideHint>(), syncGuide(input(null, syncEnabled = false, battery = false, xiaomi = true), 0).hints)
        assertEquals("nor once up to date", emptyList<GuideHint>(), syncGuide(input(node(5, false, 11_430_011, 11_430_011, 11_430_011), battery = false), 0).hints)
    }

    @Test
    fun theLineNamesTheStepsProgress() {
        assertEquals("Sync is off", syncGuide(input(null, syncEnabled = false), 0).line)
        assertEquals("The node could not start: disk full", syncGuide(input(null, startError = "disk full"), 0).line)
        assertEquals("Looking for peers · 3 min", syncGuide(input(node(0, true, 1, 1, 0), runningForMs = 3 * 60_000), 0).line)
        assertEquals("Downloading headers 52%", syncGuide(input(node(3, true, 1, 6_000_000, 11_460_000)), 0).line)
        assertNull(syncGuide(input(null, hasKey = false, syncEnabled = false), 0).line)
        assertNull(syncGuide(input(node(5, false, 11_430_011, 11_430_011, 11_430_011)), 0).line)
    }
}
