package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StatusTest {
    private val synced = NodeView(peers = 6, syncing = false, currentBlock = 11_380_000, highestBlock = 11_380_000, validation = false)
    private val running = NodeBarInput(
        syncEnabled = true, hasKey = true, startError = null, node = synced,
        runningForMs = 60 * 60_000L, speed = null, backgroundIssue = null,
    )

    @Test
    fun aSyncedNodeWithPeersIsGreen() {
        assertEquals(BarState(Health.Good, "Synced · 6 peers"), nodeBarState(running))
        assertEquals("Synced · 1 peer", nodeBarState(running.copy(node = synced.copy(peers = 1))).label)
    }

    @Test
    fun noPeersIsOrangeDuringTheGraceThenRed() {
        val alone = running.copy(node = synced.copy(peers = 0))
        assertEquals(BarState(Health.Warn, "Looking for peers · 3 min"), nodeBarState(alone.copy(runningForMs = 3 * 60_000L + 5_000)))
        assertEquals(BarState(Health.Warn, "Looking for peers…"), nodeBarState(alone.copy(runningForMs = null)))
        assertEquals(BarState(Health.Bad, "No peers"), nodeBarState(alone.copy(runningForMs = PEER_GRACE_MS)))
    }

    @Test
    fun aNodeThatDoesNotAnswerIsStartingThenRed() {
        assertEquals(BarState(Health.Warn, "Starting the node…"), nodeBarState(running.copy(node = null, runningForMs = 30_000)))
        assertEquals(BarState(Health.Bad, "The node does not answer"), nodeBarState(running.copy(node = null)))
    }

    @Test
    fun syncingShowsItsShareAndTimeLeft() {
        val syncing = running.copy(node = synced.copy(syncing = true, currentBlock = 8_200, highestBlock = 10_000), speed = 10.0)
        assertEquals(BarState(Health.Warn, "Syncing 82% · about 3 min"), nodeBarState(syncing))
        assertEquals("not measured yet", "Syncing 82%", nodeBarState(syncing.copy(speed = null)).label)
        // A node 1 block short of the head says 99%, not 100%.
        assertEquals("Syncing 99%", nodeBarState(syncing.copy(node = synced.copy(syncing = true, currentBlock = 9_999, highestBlock = 10_000), speed = null)).label)
    }

    @Test
    fun errorsAndSwitchesComeFirst() {
        assertEquals(Health.Bad, nodeBarState(running.copy(startError = "database locked")).health)
        assertEquals(BarState(Health.Off, "Sync off"), nodeBarState(running.copy(syncEnabled = false)))
        assertEquals("Sync off: import a key first", nodeBarState(running.copy(syncEnabled = false, hasKey = false)).label)
        assertEquals(BarState(Health.Bad, "The phone's clock is wrong"), nodeBarState(running.copy(node = synced.copy(wrongTime = true))))
    }

    @Test
    fun aBackgroundIssueTurnsASyncedNodeOrange() {
        assertEquals(BarState(Health.Warn, "Synced · battery saver on"), nodeBarState(running.copy(backgroundIssue = backgroundIssue(false))))
        assertNull(backgroundIssue(true))
    }

    @Test
    fun theCountdownGivesDaysHoursAndMinutes() {
        val now = Instant.parse("2026-10-01T15:00:00Z")
        val ceremony = Instant.parse("2026-10-04T15:00:00Z")
        assertEquals("in 3 d 0 h", countdown(now, ceremony))
        assertEquals("in 5 h 12 min", countdown(ceremony - Duration.ofMinutes(5 * 60 + 12), ceremony))
        assertEquals("in 12 min", countdown(ceremony - Duration.ofMinutes(12), ceremony))
        assertEquals("in 1 min", countdown(ceremony - Duration.ofSeconds(20), ceremony))
        assertEquals("now", countdown(ceremony, ceremony))
    }

    @Test
    fun theValidationComesFirstWithinADayOrWhileItRuns() {
        val ceremony = Instant.parse("2026-10-04T15:00:00Z")
        assertFalse(validationIsClose(ceremony - Duration.ofDays(2), ceremony, "None"))
        assertTrue(validationIsClose(ceremony - Duration.ofHours(23), ceremony, "None"))
        assertTrue(validationIsClose(ceremony + Duration.ofMinutes(5), Instant.parse("2026-10-09T15:00:00Z"), "LongSession"))
        assertFalse("the period is not known yet", validationIsClose(ceremony - Duration.ofDays(2), null, ""))
    }
}
