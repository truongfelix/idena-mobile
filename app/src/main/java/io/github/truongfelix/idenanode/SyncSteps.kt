package io.github.truongfelix.idenanode

/*
 * The steps of a sync as the status bar names them. bcn_syncing's currentBlock is the fast sync's headers when
 * the node holds some above its chain (bcn_lastBlock), else the chain; a fast sync downloads the headers, then
 * one snapshot (the state), and only then the chain jumps up. While it waits for a snapshot it may full sync
 * slices of blocks, the headers kept: the chain then moves and the headers do not.
 */

/** What the node does while it syncs. */
enum class SyncPhase { Headers, Snapshot, Blocks }

/** A sync step: [done] of [total] blocks, the chain's or the headers'. */
data class SyncStep(val phase: SyncPhase, val done: Long, val total: Long) {
    /** The share done, 0 to 99: a node one block short says 99%. */
    val percent: Int
        get() = if (total <= 0) 0 else (done.toDouble() / total * 100).toInt().coerceIn(0, 99)

    val remaining: Long
        get() = (total - done).coerceAtLeast(0)
}

/**
 * Headers this close to the top are all there: the fast sync stops them at the newest snapshot's height, and
 * snapshots are [SNAPSHOT_RANGE] blocks apart on mainnet.
 */
const val SNAPSHOT_RANGE = 1000L
private const val HEADERS_AT_TOP = SNAPSHOT_RANGE + 100

/**
 * The step of a sync: [chain] the chain's height (bcn_lastBlock), [headers] bcn_syncing's currentBlock, [highest]
 * its highestBlock, [chainMoving] whether the chain grew lately. The top is never below what the node holds.
 */
fun syncStep(chain: Long, headers: Long, highest: Long, chainMoving: Boolean): SyncStep {
    val top = maxOf(highest, headers, chain)
    if (headers <= chain || chainMoving) return SyncStep(SyncPhase.Blocks, chain, top)
    return if (top - headers > HEADERS_AT_TOP) SyncStep(SyncPhase.Headers, headers, top) else SyncStep(SyncPhase.Snapshot, chain, top)
}

/** The status bar's words for [step], with the time left at [speed] blocks per second of that step. */
fun syncLabel(step: SyncStep, speed: Double?): String {
    val left = speed?.takeIf { it > 0 && step.phase != SyncPhase.Snapshot }?.let { duration(step.remaining / it) }
    return when (step.phase) {
        SyncPhase.Headers -> if (left == null) t(R.string.node_sync_headers, step.percent) else t(R.string.node_sync_headers_left, step.percent, left)
        SyncPhase.Snapshot -> t(R.string.node_sync_snapshot)
        SyncPhase.Blocks -> if (left == null) t(R.string.node_sync_blocks, step.percent) else t(R.string.node_sync_blocks_left, step.percent, left)
    }
}

/**
 * A head block older than this, with the node saying it is synced, means it is behind: blocks come about every
 * 20 s, empty ones included.
 */
const val STALE_HEAD_SECONDS = 10 * 60L

/** Whether a node that says it is synced is in fact behind: its head block, made at [headTime], is old at [now] (seconds). */
fun headIsStale(headTime: Long?, now: Long): Boolean = headTime != null && headTime > 0 && now - headTime > STALE_HEAD_SECONDS
