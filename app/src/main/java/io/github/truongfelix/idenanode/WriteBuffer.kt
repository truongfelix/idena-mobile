package io.github.truongfelix.idenanode

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
enum class WriteBuffer(val mib: Int, val label: String) {
    MiB4(4, "Default"),
    MiB16(16, "16 MiB"),
    MiB32(32, "32 MiB"),
    MiB64(64, "64 MiB"),
    ;

    companion object {
        val DEFAULT = MiB4

        /** The size a settings file holds; the default when it is missing or holds no offered size. */
        fun parse(text: String?): WriteBuffer = entries.firstOrNull { it.mib.toString() == text?.trim() } ?: DEFAULT
    }
}

/** The node's databases with a write buffer setting, each set on its own. */
enum class NodeDatabase(val title: String, val noun: String, val about: String) {
    Chain(
        "Chain database buffer",
        "chain database",
        "The chain database holds the blockchain: the node writes to it at every block.",
    ),
    Ipfs(
        "IPFS database buffer",
        "IPFS database",
        "The IPFS database holds what the network uses to find content (which node has which flip, post or " +
            "block). When nodes outside can reach the phone (a port open on the router), they store these " +
            "records on it, and this database writes the most.",
    ),
}

/** What the info of each buffer says after [NodeDatabase.about]. */
const val WRITE_BUFFER_INFO =
    "Default is 4 MiB, as on a computer. A higher level needs more RAM but writes less to the phone's storage " +
        "(less wear, a little less work). If Android closes the node, choose a lower level. The node takes the " +
        "size at its start."

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
}

/**
 * Why a node restart now is a risk, or null: after a restart the node looks for peers again, which can
 * take minutes. During the validation [canRestart] is false.
 */
data class RestartRisk(val message: String, val canRestart: Boolean)

/** The [RestartRisk] of a restart at [now], for the validation at [start] and the ceremony [period]. */
fun restartRisk(now: Instant, start: Instant?, period: String): RestartRisk? = when {
    period.isNotEmpty() && period != "None" -> RestartRisk("The validation is running: restart the node after it.", canRestart = false)
    start != null && now.isBefore(start) && Duration.between(now, start) < RESTART_MARGIN ->
        RestartRisk(
            "The validation starts ${countdown(now, start)}: the node may still be looking for peers then.",
            canRestart = true,
        )
    else -> null
}

/** How long before a validation a restart gets a warning. */
private val RESTART_MARGIN: Duration = Duration.ofHours(3)

/** The phone's RAM as sold (3, 4, 6 GB...) from the memory Android reports, which is a bit less. */
fun ramGb(totalBytes: Long): Long = (totalBytes / 1e9).roundToLong()
