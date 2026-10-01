package io.github.truongfelix.idenanode

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.truongfelix.idenanode.go.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the Node tab shows, refreshed every few seconds while the app is shown. */
data class ScreenState(
    val syncEnabled: Boolean = false,
    val hasKey: Boolean = false,
    val keyAddress: String? = null,
    val status: NodeStatus? = null,
    val startError: String? = null,
    val network: String = "",
    val ignoringBatteryOptimizations: Boolean = true,
    /** How long ago the service started the node; null when unknown. */
    val runningForMs: Long? = null,
    /** Blocks per second over the last minute while syncing; null until measured. */
    val blocksPerSecond: Double? = null,
)

/**
 * The node as the app shows it, for the Node tab and the title row's status bar (both tabs): on the Node
 * tab everything it shows every 3 s; on the Social tab only the title row's sync and peers (two calls)
 * every 5 s. Nothing while the app is hidden.
 */
class NodeModel(private val context: Context) {
    var state by mutableStateOf(ScreenState())
        private set

    /** The node at the last read; null when it did not answer. */
    var view by mutableStateOf<NodeView?>(null)
        private set

    /** Whether the Node tab is shown: then everything is read. */
    var nodeTabVisible by mutableStateOf(true)

    private val syncRate = SyncRate()

    /** The title row's status bar. */
    val bar: BarState
        get() = nodeBarState(
            NodeBarInput(
                syncEnabled = state.syncEnabled,
                hasKey = state.hasKey,
                startError = state.startError,
                node = view,
                runningForMs = state.runningForMs,
                speed = state.blocksPerSecond,
                backgroundIssue = backgroundIssue(state.ignoringBatteryOptimizations),
            ),
        )

    /** Reads everything the Node tab shows. */
    suspend fun refresh() {
        val next = withContext(Dispatchers.IO) { readState(context) }
        state = next.copy(blocksPerSecond = next.status?.takeIf { it.syncing }?.let { syncRate.update(it.currentBlock) })
        view = next.status?.let {
            NodeView(it.peers, it.syncing, it.currentBlock, it.highestBlock, validation = it.period != "None", wrongTime = it.wrongTime)
        }
    }

    /** Reads what the title row shows: the app's files, then the node's sync and peers. */
    private suspend fun refreshBar() {
        val (local, node) = withContext(Dispatchers.IO) {
            val local = readLocal(context, state)
            val node = runCatching {
                val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
                val sync = rpc.call("bcn_syncing") as JSONObject
                val peers = (rpc.call("net_peers") as? JSONArray)?.length() ?: 0
                NodeView(
                    peers, sync.optBoolean("syncing"), sync.optLong("currentBlock"), sync.optLong("highestBlock"),
                    validation = false, wrongTime = sync.optBoolean("wrongTime"),
                )
            }.getOrNull()
            local to node
        }
        state = local.copy(blocksPerSecond = node?.takeIf { it.syncing }?.let { syncRate.update(it.currentBlock) })
        view = node
    }

    /** The Sync switch was turned: the screen follows before the next read. */
    fun syncChanged(enabled: Boolean) {
        state = state.copy(syncEnabled = enabled, startError = null)
    }

    suspend fun run(lifecycle: Lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (nodeTabVisible) {
                    refresh()
                    delay(3_000)
                } else {
                    refreshBar()
                    delay(5_000)
                }
            }
        }
    }
}

/** The sync speed over the last minute, from the blocks the screen reads every few seconds. */
private class SyncRate {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Adds the current block; returns blocks per second, or null until 10 s are measured. */
    fun update(block: Long): Double? {
        val now = SystemClock.elapsedRealtime()
        // A lower block means the node started over: the old samples no longer apply.
        if (samples.isNotEmpty() && block < samples.last().second) samples.clear()
        samples.addLast(now to block)
        while (samples.size > 2 && now - samples[1].first >= 60_000) samples.removeFirst()
        val (firstTime, firstBlock) = samples.first()
        val seconds = (now - firstTime) / 1000.0
        return if (seconds >= 10) (block - firstBlock) / seconds else null
    }
}

private fun readState(context: Context): ScreenState {
    val hasKey = NodeSettings.hasKey(context)
    val status = try {
        NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).status()
    } catch (e: Exception) {
        null
    }
    return readLocal(context, ScreenState(status = status)).copy(
        hasKey = hasKey,
        // Reading the key needs the node library; only when the node does not answer.
        keyAddress = if (status == null && hasKey) {
            Mobile.address(NodeSettings.nodeDir(context).absolutePath).ifEmpty { null }
        } else {
            null
        },
    )
}

/** [state] with what the app reads without the node: the switch, the start error, the network, the background. */
private fun readLocal(context: Context, state: ScreenState): ScreenState {
    val errorFile = NodeSettings.startErrorFile(context)
    return state.copy(
        syncEnabled = NodeSettings.isSyncEnabled(context),
        hasKey = NodeSettings.hasKey(context),
        startError = if (errorFile.exists()) errorFile.readText() else null,
        network = networkName(context),
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        runningForMs = NodeSettings.runningForMs(context),
    )
}

private fun networkName(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }
}
