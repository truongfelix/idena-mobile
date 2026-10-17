package io.github.truongfelix.idenanode

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/*
 * The one-line status bars: the node's in the title row (both tabs), the Social scan's at the top of the
 * Social tab. Each is a colored dot and a short line; the details are behind its info button.
 */

/** The dot's color: green all good, orange working or something to fix, red not working, grey off. */
enum class Health { Good, Warn, Bad, Off }

/** A status bar's dot and line. */
data class BarState(val health: Health, val label: String)

/** What the node's status bar is made of. */
data class NodeBarInput(
    val syncEnabled: Boolean,
    val hasKey: Boolean,
    /** Why the node could not start, from its last start. */
    val startError: String?,
    /** The node; null when it does not answer, or only its first RPC does ([startPhase]). */
    val node: NodeView?,
    /** How long ago the service started the node; null when unknown. */
    val runningForMs: Long?,
    /** Blocks (or headers, while they download) per second while syncing; null until measured. */
    val speed: Double?,
    /** What keeps the node from running with the screen off ([backgroundIssue]), or null. */
    val backgroundIssue: String?,
    /** Whether the chain grew lately: a fast sync waiting for its snapshot may sync blocks meanwhile. */
    val chainMoving: Boolean = false,
    /** The phone's clock (seconds), to tell an old head block; null leaves it out. */
    val nowSeconds: Long? = null,
    /** The step of a start, while only the node's first RPC answers ([readStartPhase]); null when nothing answers. */
    val startPhase: StartPhase? = null,
    /** What the node says it does while it syncs (bcn_syncing's message: its IPFS store compacting); empty for nothing. */
    val nodeMessage: String = "",
)

/**
 * Peers the node may lack after a start before the dot turns red: the phone often waits several minutes
 * for a first full node that accepts it (up to 14 min seen), after a start that may compact its database.
 */
const val PEER_GRACE_MS = 20 * 60_000L

/**
 * The node's status bar: red when it could not start, does not answer (after [PEER_GRACE_MS]), has a wrong
 * clock or no peers (after the grace); grey with sync off; orange while starting (its step, [StartPhase], for as long
 * as it takes: a compaction may outlast the grace), looking for peers, syncing (its step, [syncStep], or what the node
 * says it does), behind with an old head block though the node says it is synced, or with a background issue; green
 * when synced with peers.
 */
fun nodeBarState(input: NodeBarInput): BarState {
    if (input.startError != null) return BarState(Health.Bad, t(R.string.status_could_not_start))
    if (!input.syncEnabled) return BarState(Health.Off, t(if (input.hasKey) R.string.status_sync_off else R.string.status_sync_off_no_key))
    val starting = input.runningForMs == null || input.runningForMs < PEER_GRACE_MS
    val node = input.node ?: return when {
        input.startPhase != null -> BarState(Health.Warn, startLabel(input.startPhase, input.runningForMs))
        starting -> BarState(Health.Warn, StartPhase.Starting.label)
        else -> BarState(Health.Bad, t(R.string.status_no_answer))
    }
    if (node.wrongTime) return BarState(Health.Bad, t(R.string.status_wrong_clock))
    if (node.peers == 0) {
        if (!starting) return BarState(Health.Bad, t(R.string.status_no_peers))
        val minutes = input.runningForMs?.let { it / 60_000 }
        return BarState(Health.Warn, if (minutes == null) t(R.string.status_looking_for_peers) else t(R.string.status_looking_for_peers_minutes, minutes))
    }
    if (node.syncing && input.nodeMessage.isNotEmpty()) return BarState(Health.Warn, input.nodeMessage)
    if (node.syncing && node.highestBlock > 0) return BarState(Health.Warn, syncLabel(node.syncStep(input.chainMoving), input.speed))
    if (node.syncing) return BarState(Health.Warn, t(R.string.status_syncing))
    if (input.nowSeconds != null && headIsStale(node.headTime, input.nowSeconds)) return BarState(Health.Warn, t(R.string.status_behind))
    input.backgroundIssue?.let { return BarState(Health.Warn, t(R.string.status_synced_issue, it)) }
    return BarState(Health.Good, tp(R.plurals.status_synced_peers, node.peers, node.peers))
}

/** What may stop the node with the screen off: Android's battery optimization on. */
fun backgroundIssue(ignoringBatteryOptimizations: Boolean): String? =
    if (ignoringBatteryOptimizations) null else t(R.string.status_battery_saver_on)

/** "about 1 h 30 min", "about 12 min", "under a minute". */
fun duration(seconds: Double): String {
    val minutes = (seconds / 60).roundToLong()
    return when {
        minutes < 1 -> t(R.string.time_under_a_minute)
        minutes < 60 -> t(R.string.time_about_minutes, minutes)
        else -> t(R.string.time_about_hours, minutes / 60, "%02d".format(minutes % 60))
    }
}

/** "in 2 d 23 h", "in 5 h 12 min", "in 12 min", or "now" once [then] is reached. */
fun countdown(now: Instant, then: Instant): String {
    val left = Duration.between(now, then)
    if (left.isNegative || left.isZero) return t(R.string.time_now)
    val days = left.toDays()
    val hours = left.toHours() % 24
    val minutes = maxOf(1, left.toMinutes() % 60)
    return when {
        days > 0 -> t(R.string.time_in_days, days, hours)
        hours > 0 -> t(R.string.time_in_hours, hours, minutes)
        else -> t(R.string.time_in_minutes, minutes)
    }
}

/** Whether the validation at [start] is close: under a day, or running; the Profile card then counts the time left to the minute. */
fun validationIsClose(now: Instant, start: Instant?, period: String): Boolean =
    period.isNotEmpty() && period != "None" || start != null && Duration.between(now, start) < Duration.ofDays(1)
