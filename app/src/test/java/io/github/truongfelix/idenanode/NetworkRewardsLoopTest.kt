package io.github.truongfelix.idenanode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRewardsLoopTest {
    /** Runs the loop on a fake clock until it has paused [turns] times; returns the times it read. */
    private fun run(turns: Int, ready: (Long) -> Boolean, last: Long? = null): List<Long> {
        var clock = 0L
        var pauses = 0
        val reads = mutableListOf<Long>()
        try {
            runBlocking {
                readEveryTenMinutes(
                    ready = { ready(clock) },
                    last = { reads.lastOrNull() ?: last },
                    now = { clock },
                    pause = {
                        clock += it
                        if (++pauses >= turns) throw CancellationException("done")
                    },
                    read = { reads += clock },
                )
            }
        } catch (_: CancellationException) {
        }
        return reads
    }

    @Test
    fun readsOnceTheNodeIsReadyAfterTheScreenOpened() {
        // B-113: the Node tab opened before the node answered; it is synced after 1 minute.
        val reads = run(turns = 3, ready = { it >= 60_000L })
        assertEquals(listOf(60_000L), reads)
    }

    @Test
    fun readsAgainEveryTenMinutes() {
        val reads = run(turns = 3, ready = { true })
        assertEquals(listOf(0L, 600_000L, 1_200_000L), reads)
    }

    @Test
    fun aRestartWaitsOutTheTenMinutesOfTheKeptRead() {
        // The panel closed and opened again 4 minutes after the last read: the next read is 6 minutes later.
        val reads = run(turns = 2, ready = { true }, last = -240_000L)
        assertEquals(listOf(360_000L), reads)
    }

    @Test
    fun neverReadsWhileTheNodeSyncs() {
        assertEquals(emptyList<Long>(), run(turns = 10, ready = { false }))
    }
}
