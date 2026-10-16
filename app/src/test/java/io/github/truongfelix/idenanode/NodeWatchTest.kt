package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class NodeWatchTest {
    private val read = 15_000L
    private val minute = 60_000L

    /** A watch over a node that answered at [answeredAt] (or never), then failed [failures] reads up to [now]. */
    private fun watch(answeredAt: Long?, failures: Int): NodeWatch = NodeWatch(startedAt = 0).apply {
        answeredAt?.let { answered(it) }
        repeat(failures) { failed() }
    }

    @Test
    fun aNodeThatAnsweredIsStoppedAfterFiveSilentMinutes() {
        val answeredAt = 30 * minute
        assertFalse(watch(answeredAt, 19).frozen(answeredAt + FROZEN_AFTER_ANSWER_MS - read))
        assertTrue(watch(answeredAt, 20).frozen(answeredAt + FROZEN_AFTER_ANSWER_MS))
    }

    @Test
    fun aNodeThatNeverAnsweredHasFifteenMinutes() {
        assertFalse(watch(null, 59).frozen(FROZEN_AFTER_ANSWER_MS * 2))
        assertTrue(watch(null, 60).frozen(FROZEN_WITHOUT_ANSWER_MS))
    }

    @Test
    fun aFewFailedReadsAreNoStop() {
        // Reads that wait for their timeouts come further apart: the silence alone is not enough.
        assertFalse(watch(0, MIN_FAILED_READS - 1).frozen(FROZEN_AFTER_ANSWER_MS * 3))
        assertTrue(watch(0, MIN_FAILED_READS).frozen(FROZEN_AFTER_ANSWER_MS * 3))
    }

    @Test
    fun anAnswerStartsTheCountOver() {
        val w = watch(0, 30)
        assertTrue(w.frozen(10 * minute))
        w.answered(10 * minute)
        assertFalse(w.frozen(10 * minute + read))
        assertEquals(read, w.silentFor(10 * minute + read))
    }

    @Test
    fun atMostThreeRestartsInSixHours() {
        val now = 100 * RESTART_WINDOW_MS
        val hour = 3_600_000L
        assertTrue(restartAllowed(now, listOf(now - 2 * hour, now - hour)))
        assertFalse(restartAllowed(now, listOf(now - 5 * hour, now - 2 * hour, now - hour)))
        // A restart over six hours ago no longer counts.
        assertTrue(restartAllowed(now, listOf(now - RESTART_WINDOW_MS, now - 2 * hour, now - hour)))
    }

    @Test
    fun theSectionSaysWhenTheAppLastRestartedTheNode() {
        val utc = ZoneId.of("UTC")
        assertEquals("The app has not had to restart the node.", lastRestartText(emptyList(), utc))
        // 2026-10-16 09:44 UTC, after one on the 15th.
        val restarts = listOf(1_792_057_440_000L, 1_792_143_840_000L)
        assertEquals("The app restarted the node on 16 Oct 09:44: it had stopped answering.", lastRestartText(restarts, utc))
    }

    @Test
    fun reportNamesSortByTime() {
        assertEquals("node-report-2026-10-16-094400.txt", reportName(1_792_143_840_000L))
        assertTrue(reportName(1_792_143_840_000L) > reportName(1_792_057_440_000L))
    }

    @Test
    fun aReportSaysWhenTheGoroutinesWereNotWritten() {
        val stopped = reportText(0, "0.2.0-beta.9 (89)", "The node had not answered for 5 min.", null, null, "INFO [10-16|09:39:41.894] Selected as proposer")
        assertTrue(stopped.contains("Why: The node had not answered for 5 min."))
        assertTrue(stopped.contains("Not written within 15 s: the node's Go runtime was stopped as a whole."))
        assertFalse(stopped.contains("== Go crash output"))
        assertTrue(stopped.trimEnd().endsWith("Selected as proposer"))

        val running = reportText(0, "x", "Made from the app.", "goroutine 1 [running]:", "fatal error: x", null)
        assertTrue(running.contains("== Goroutines\ngoroutine 1 [running]:"))
        assertTrue(running.contains("== Go crash output (crash.txt, its end)\nfatal error: x"))
        assertTrue(running.contains("Not read."))
    }
}
