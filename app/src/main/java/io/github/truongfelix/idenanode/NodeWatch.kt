package io.github.truongfelix.idenanode

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The node's watchdog, in the node's service. On 2026-10-16 the Galaxy's node stopped as a whole: no log line, no
 * RPC (its listener stopped taking connections), while the service's notification went on; the screens said
 * "Starting" for two hours. The service reads the node every 15 s for its notification; when nothing answers for
 * long enough, it writes a node report (NodeReports.kt) and has the app restart the node (NodeRestartReceiver).
 */

/** A node that answered once is stopped after this long without an answer: it answers every few seconds. */
const val FROZEN_AFTER_ANSWER_MS = 5 * 60_000L

/**
 * A node that never answered since its start is stopped after this long: its first RPC answers within seconds of
 * the start, compaction included ([StartPhase]); this leaves room for a slow opening of IPFS.
 */
const val FROZEN_WITHOUT_ANSWER_MS = 15 * 60_000L

/** Reads in a row without an answer before a node counts as stopped: a few slow answers are not a stop. */
const val MIN_FAILED_READS = 8

/** At most this many restarts by the app in [RESTART_WINDOW_MS]: a node that stops again and again needs the user. */
const val MAX_RESTARTS = 3
const val RESTART_WINDOW_MS = 6 * 60 * 60_000L

/**
 * Tells a node that stopped answering from one that starts or syncs, from the reads of the service. Times are
 * the phone's uptime ([android.os.SystemClock.uptimeMillis]): it stops while the phone sleeps, and the node with
 * it, so a night of deep sleep is no silence. [startedAt]: when the service started the node.
 */
class NodeWatch(private val startedAt: Long) {
    private var lastAnswerAt: Long? = null
    private var failedReads = 0

    /** The node answered at [now], fully or only its first RPC. */
    fun answered(now: Long) {
        lastAnswerAt = now
        failedReads = 0
    }

    /** A read found nothing that answers. */
    fun failed() {
        failedReads++
    }

    /** How long the node has not answered at [now]: since its last answer, or since its start. */
    fun silentFor(now: Long): Long = now - (lastAnswerAt ?: startedAt)

    /** Whether the node counts as stopped at [now]. */
    fun frozen(now: Long): Boolean =
        failedReads >= MIN_FAILED_READS && silentFor(now) >= if (lastAnswerAt != null) FROZEN_AFTER_ANSWER_MS else FROZEN_WITHOUT_ANSWER_MS
}

/** Whether the app may restart the node at [now], after its restarts at [earlier] (wall clock, ms). */
fun restartAllowed(now: Long, earlier: List<Long>): Boolean = earlier.count { now - it in 0 until RESTART_WINDOW_MS } < MAX_RESTARTS

/** The line of the Node reports section on the app's last restart of the node, from its [restarts] (wall clock, ms). */
fun lastRestartText(restarts: List<Long>, zone: ZoneId = ZoneId.systemDefault()): String {
    val last = restarts.maxOrNull() ?: return "The app has not had to restart the node."
    return "The app restarted the node on ${reportTime(last, zone)}: it had stopped answering."
}

/** "16 Oct 11:44". */
fun reportTime(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(zone))
