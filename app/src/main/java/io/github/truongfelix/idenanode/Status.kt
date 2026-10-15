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
    /** The node; null when it does not answer. */
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
)

/**
 * Peers the node may lack after a start before the dot turns red: the phone often waits several minutes
 * for a first full node that accepts it (up to 14 min seen), after a start that may compact its database.
 */
const val PEER_GRACE_MS = 20 * 60_000L

/**
 * The node's status bar: red when it could not start, does not answer (after [PEER_GRACE_MS]), has a wrong
 * clock or no peers (after the grace); grey with sync off; orange while starting, looking for peers,
 * syncing (its step, [syncStep]), behind with an old head block though the node says it is synced, or with a
 * background issue; green when synced with peers.
 */
fun nodeBarState(input: NodeBarInput): BarState {
    if (input.startError != null) return BarState(Health.Bad, "The node could not start")
    if (!input.syncEnabled) return BarState(Health.Off, if (input.hasKey) "Sync off" else "Sync off: create or import a key first")
    val starting = input.runningForMs == null || input.runningForMs < PEER_GRACE_MS
    val node = input.node
        ?: return if (starting) BarState(Health.Warn, "Starting the node…") else BarState(Health.Bad, "The node does not answer")
    if (node.wrongTime) return BarState(Health.Bad, "The phone's clock is wrong")
    if (node.peers == 0) {
        if (!starting) return BarState(Health.Bad, "No peers")
        val minutes = input.runningForMs?.let { it / 60_000 }
        return BarState(Health.Warn, if (minutes == null) "Looking for peers…" else "Looking for peers · $minutes min")
    }
    if (node.syncing && node.highestBlock > 0) return BarState(Health.Warn, syncLabel(node.syncStep(input.chainMoving), input.speed))
    if (node.syncing) return BarState(Health.Warn, "Syncing")
    if (input.nowSeconds != null && headIsStale(node.headTime, input.nowSeconds)) return BarState(Health.Warn, "Behind: looking for peers")
    input.backgroundIssue?.let { return BarState(Health.Warn, "Synced · $it") }
    return BarState(Health.Good, "Synced · ${if (node.peers == 1) "1 peer" else "${node.peers} peers"}")
}

/** What may stop the node with the screen off: Android's battery optimization on. */
fun backgroundIssue(ignoringBatteryOptimizations: Boolean): String? =
    if (ignoringBatteryOptimizations) null else "battery saver on"

/** "about 1 h 30 min", "about 12 min", "under a minute". */
fun duration(seconds: Double): String {
    val minutes = (seconds / 60).roundToLong()
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "about $minutes min"
        else -> "about ${minutes / 60} h ${"%02d".format(minutes % 60)} min"
    }
}

/** "in 2 d 23 h", "in 5 h 12 min", "in 12 min", or "now" once [then] is reached. */
fun countdown(now: Instant, then: Instant): String {
    val left = Duration.between(now, then)
    if (left.isNegative || left.isZero) return "now"
    val days = left.toDays()
    val hours = left.toHours() % 24
    val minutes = maxOf(1, left.toMinutes() % 60)
    return when {
        days > 0 -> "in $days d $hours h"
        hours > 0 -> "in $hours h $minutes min"
        else -> "in $minutes min"
    }
}

/** Whether the validation at [start] is close: under a day, or running; the Profile card then counts the time left to the minute. */
fun validationIsClose(now: Instant, start: Instant?, period: String): Boolean =
    period.isNotEmpty() && period != "None" || start != null && Duration.between(now, start) < Duration.ofDays(1)
