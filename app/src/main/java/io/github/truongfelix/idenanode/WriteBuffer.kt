package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/**
 * The write buffer sizes the app offers for each of the node's two databases ([NodeDatabase]). A database
 * writes its buffer to disk when it is full, and each of these writes makes it rewrite older data: a bigger
 * buffer writes much less to the phone's storage, for more memory. Default is idena-go's 4 MiB. Measured on a
 * phone (chain database, 2026-10-03/04): 3.1 MiB written a block at 4 MiB, 0.7 at 32 MiB; RAM 206, 235, 272,
 * 391 MB on average at 4, 16, 32, 64 MiB.
 */
enum class WriteBuffer(val mib: Int) {
    MiB4(4),
    MiB16(16),
    MiB32(32),
    MiB64(64),
    ;

    /** "Default" for idena-go's size, else the size. */
    val label: String get() = if (this == DEFAULT) t(R.string.node_buffer_default) else "$mib MiB"

    companion object {
        val DEFAULT = MiB4

        /** The size a settings file holds; the default when it is missing or holds no offered size. */
        fun parse(text: String?): WriteBuffer = entries.firstOrNull { it.mib.toString() == text?.trim() } ?: DEFAULT
    }
}

/** The node's databases with a write buffer setting, each set on its own. */
enum class NodeDatabase(@StringRes private val titleId: Int, @StringRes private val nounId: Int, @StringRes private val aboutId: Int) {
    Chain(R.string.node_chain_buffer_title, R.string.node_chain_database, R.string.node_chain_buffer_about),
    Ipfs(R.string.node_ipfs_buffer_title, R.string.node_ipfs_database, R.string.node_ipfs_buffer_about),
    ;

    val title: String get() = t(titleId)
    val noun: String get() = t(nounId)
    val about: String get() = t(aboutId)
}

/** What the info of each buffer says after [NodeDatabase.about]. */
val WRITE_BUFFER_INFO: String
    get() = t(R.string.node_write_buffer_info)

/**
 * Whether the node runs with another setting than [chosen] (a write buffer, the peer level...): the node
 * takes its settings at its start, so the choice applies from the next one.
 */
fun <T> settingPending(syncEnabled: Boolean, running: T?, chosen: T): Boolean =
    syncEnabled && running != null && running != chosen

/** An Advanced setting the node does not run with yet: its [title] and the [value] chosen. */
data class PendingChange(val title: String, val value: String)

/** The settings of [chosen] the node runs without ([settingPending]), in the order of Advanced settings. */
fun pendingChanges(syncEnabled: Boolean, chosen: NodeOptions, running: RunningOptions): List<PendingChange> = buildList {
    if (settingPending(syncEnabled, running.peerLevel, chosen.peerLevel)) {
        add(PendingChange(PEER_LEVEL_TITLE, chosen.peerLevel.label))
    }
    if (settingPending(syncEnabled, running.ipfsConnections, chosen.ipfsConnections)) {
        add(PendingChange(IPFS_CONNECTIONS_TITLE, chosen.ipfsConnections.label))
    }
    NodeDatabase.entries.forEach { database ->
        val size = chosen.writeBuffers[database] ?: WriteBuffer.DEFAULT
        if (settingPending(syncEnabled, running.writeBuffers[database], size)) add(PendingChange(database.title, "${size.mib} MiB"))
    }
    // The node takes ids only: a renamed peer needs no restart.
    val chosenIds = chosen.directPeers.map { it.id }.toSet()
    if (settingPending(syncEnabled, running.directPeers?.toSet(), chosenIds)) {
        add(PendingChange(DIRECT_PEERS_TITLE, if (chosenIds.isEmpty()) t(R.string.node_none) else chosen.directPeers.joinToString { it.name.ifEmpty { shortPeerId(it.id) } }))
    }
}

/**
 * Why a node restart now is a risk, or null: after a restart the node looks for peers again, which can
 * take minutes. During the validation [canRestart] is false.
 */
data class RestartRisk(val message: String, val canRestart: Boolean)

/** The [RestartRisk] of a restart at [now], for the validation at [start] and the ceremony [period]. */
fun restartRisk(now: Instant, start: Instant?, period: String): RestartRisk? = when {
    period.isNotEmpty() && period != "None" -> RestartRisk(t(R.string.node_restart_risk_running), canRestart = false)
    start != null && now.isBefore(start) && Duration.between(now, start) < RESTART_MARGIN ->
        RestartRisk(
            t(R.string.node_restart_risk_soon, countdown(now, start)),
            canRestart = true,
        )
    else -> null
}

/** How long before a validation a restart gets a warning. */
private val RESTART_MARGIN: Duration = Duration.ofHours(3)

/** The phone's RAM as sold (3, 4, 6 GB...) from the memory Android reports, which is a bit less. */
fun ramGb(totalBytes: Long): Long = (totalBytes / 1e9).roundToLong()
