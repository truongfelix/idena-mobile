package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStartTest {
    // Lines of a PC node's output.log (2026-09-29), the format of the phone's: an earlier run, a start, its compaction.
    private val earlier = listOf("INFO [09-28|10:47:56.016] Reduction started                        block=0x421f7cd5e87fdc17bbf19cb8218e99c3d24b64034c815ea5492a263162e6dd67", "INFO [09-29|05:24:40.389] Idena node is starting                   version=0.0.1").joinToString("\n")
    private val start = "INFO [09-29|05:24:40.389] initial HTTP endpoint opened             url=http://localhost:29009 cors=* vhosts=localhost"
    private val compactionStarts = "INFO [09-29|05:24:41.570] Start compacting DB "
    private val compactionEnds = "INFO [09-29|05:33:35.753] DB compacted                             d=8m55.188065614s"
    private val ipfsUp = "INFO [09-29|05:33:42.646] Ipfs initialized                         peerId=QmbgyFxBqitpPWKdnPchJECgDpGBm9nRXpBGCWhu7a3A3y"

    private fun log(vararg lines: String) = (listOf(earlier) + lines).joinToString("\n")

    @Test
    fun aCompactionRunsFromItsFirstLineToItsLast() {
        assertFalse(compacting(log(start)))
        assertTrue(compacting(log(start, compactionStarts)))
        assertFalse(compacting(log(start, compactionStarts, compactionEnds, ipfsUp)))
    }

    @Test
    fun onlyTheLastStartCounts() {
        // A start killed during its compaction, then a start that skips it.
        assertFalse(compacting(log(start, compactionStarts, start)))
        assertTrue(compacting(log(start, compactionStarts, compactionEnds, start, compactionStarts)))
    }

    @Test
    fun aLogWithoutItsStartTellsNoCompaction() {
        assertFalse(compacting(compactionStarts))
        assertFalse(compacting(""))
    }

    @Test
    fun theStepComesFromTheLogThenTheFirstRpcsMessage() {
        val opening = "Initializing database..."
        assertEquals(StartPhase.Compacting, startPhase(opening, log(start, compactionStarts)))
        assertEquals(StartPhase.Opening, startPhase(opening, log(start)))
        assertEquals(StartPhase.Opening, startPhase(opening, null))
        assertEquals(StartPhase.Starting, startPhase("", log(start, compactionStarts, compactionEnds)))
    }

    @Test
    fun aCompactionCountsItsMinutes() {
        assertEquals("Compacting the database · 4 min", startLabel(StartPhase.Compacting, 4 * 60_000L + 59_000))
        assertEquals("Compacting the database", startLabel(StartPhase.Compacting, null))
        assertEquals("Opening the database…", startLabel(StartPhase.Opening, 4 * 60_000L))
    }

    @Test
    fun theNotificationNamesTheStartThenANodeThatDoesNotAnswer() {
        assertEquals("Compacting the database · 2 min", notificationText(null, StartPhase.Compacting, 2 * 60_000L))
        assertEquals("Starting the node…", notificationText(null, null, 60_000L))
        assertEquals("Starting the node…", notificationText(null, null, null))
        assertEquals("The node does not answer", notificationText(null, null, PEER_GRACE_MS))
        // A long compaction outlasts the grace.
        assertEquals("Compacting the database · 25 min", notificationText(null, StartPhase.Compacting, 25 * 60_000L))
    }
}
