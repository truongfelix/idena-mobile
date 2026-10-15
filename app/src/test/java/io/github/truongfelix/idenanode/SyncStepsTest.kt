package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStepsTest {
    @Test
    fun fastSyncStepsFromTheHeadersAndTheChain() {
        // A fast sync from scratch: the headers download, the chain stays.
        assertEquals(SyncStep(SyncPhase.Headers, 6_000_000, 11_430_000), syncStep(1, 6_000_000, 11_430_000, chainMoving = false))
        // Galaxy, 2026-10-12 19:30: headers at the snapshot height, chain in 2022, its only peer syncing too.
        assertEquals(SyncStep(SyncPhase.Snapshot, 4_873_470, 11_429_095), syncStep(4_873_470, 11_429_095, 4_875_200, chainMoving = false))
        // Headers within a snapshot range of the top: all there.
        assertEquals(SyncPhase.Snapshot, syncStep(4_873_470, 11_429_095, 11_430_011, chainMoving = false).phase)
        // Xiaomi, 2026-10-12 22:30: headers stopped below the manifest, full sync slices of 2022 blocks.
        assertEquals(SyncStep(SyncPhase.Blocks, 4_875_200, 11_430_011), syncStep(4_875_200, 11_087_150, 11_430_011, chainMoving = true))
        // After the snapshot: the chain at the headers, then the last blocks.
        assertEquals(SyncStep(SyncPhase.Blocks, 11_429_095, 11_430_011), syncStep(11_429_095, 11_429_095, 11_430_011, chainMoving = false))
        // A full sync without headers.
        assertEquals(SyncStep(SyncPhase.Blocks, 8_200, 10_000), syncStep(8_200, 8_200, 10_000, chainMoving = true))
    }

    @Test
    fun theLabelNamesTheStep() {
        assertEquals("Downloading headers 52% · about 3 h 02 min", syncLabel(SyncStep(SyncPhase.Headers, 6_000_000, 11_460_000), 500.0))
        assertEquals("not measured yet", "Downloading headers 52%", syncLabel(SyncStep(SyncPhase.Headers, 6_000_000, 11_460_000), null))
        assertEquals("no time left while waiting", "Waiting for the snapshot", syncLabel(SyncStep(SyncPhase.Snapshot, 4_873_470, 11_429_095), 10.0))
        assertEquals("Syncing 82% · about 3 min", syncLabel(SyncStep(SyncPhase.Blocks, 8_200, 10_000), 10.0))
        assertEquals("one block short", "Syncing 99%", syncLabel(SyncStep(SyncPhase.Blocks, 9_999, 10_000), null))
    }

    @Test
    fun anOldHeadIsStale() {
        val now = 1_760_000_000L
        assertFalse(headIsStale(now - 30, now))
        assertFalse(headIsStale(now - STALE_HEAD_SECONDS, now))
        assertTrue(headIsStale(now - STALE_HEAD_SECONDS - 1, now))
        assertFalse("not read", headIsStale(null, now))
    }
}
