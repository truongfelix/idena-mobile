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
    /** The chain's blocks per second over the last minute while syncing; null until measured. */
    val blocksPerSecond: Double? = null,
    /** The fast sync's headers per second over the last minute while syncing; null until measured. */
    val headersPerSecond: Double? = null,
    /** The step of a start, while only the node's first RPC answers; null when the node runs, or nothing answers. */
    val startPhase: StartPhase? = null,
    /** What the node says it does while it syncs (bcn_syncing's message); empty for nothing. */
    val nodeMessage: String = "",
    /** The Advanced settings chosen by the user, and those of the node's last start. */
    val options: NodeOptions = NodeOptions(),
    val runningOptions: RunningOptions = RunningOptions(),
)

/**
 * The node as the app shows it, for the Node tab and the title row's status bar (both tabs): on the Node
 * tab everything it shows every 3 s; on the Social tab only the title row's sync, peers and head block (three
 * calls) every 5 s. Nothing while the app is hidden.
 */
class NodeModel(private val context: Context) {
    var state by mutableStateOf(ScreenState())
        private set

    /** The node at the last read; null when it did not answer. */
    var view by mutableStateOf<NodeView?>(null)
        private set

    /** Whether the Node tab is shown: then everything is read. */
    var nodeTabVisible by mutableStateOf(true)

    private val chainRate = SyncRate()
    private val headerRate = SyncRate()

    /** Whether the chain grew over the last minute. */
    val chainMoving: Boolean
        get() = (state.blocksPerSecond ?: 0.0) > 0

    /** The speed of the sync's step: headers while they download, else blocks. */
    fun stepSpeed(step: SyncStep): Double? = if (step.phase == SyncPhase.Headers) state.headersPerSecond else state.blocksPerSecond

    /** The title row's status bar. */
    val bar: BarState
        get() = nodeBarState(
            NodeBarInput(
                syncEnabled = state.syncEnabled,
                hasKey = state.hasKey,
                startError = state.startError,
                node = view,
                runningForMs = state.runningForMs,
                speed = view?.let { stepSpeed(it.syncStep(chainMoving)) },
                backgroundIssue = backgroundIssue(state.ignoringBatteryOptimizations),
                chainMoving = chainMoving,
                nowSeconds = System.currentTimeMillis() / 1000,
                startPhase = state.startPhase,
                nodeMessage = state.nodeMessage,
            ),
        )

    /** Reads everything the Node tab shows. */
    suspend fun refresh() {
        val next = withContext(Dispatchers.IO) { readState(context) }
        state = withRates(next, next.status?.takeIf { it.syncing }?.let { it.stateBlock to it.currentBlock })
        view = next.status?.let {
            NodeView(
                it.peers, it.syncing, it.currentBlock, it.highestBlock, validation = it.period != "None", wrongTime = it.wrongTime,
                chainBlock = it.stateBlock, headTime = it.headTime,
            )
        }
    }

    /** Reads what the title row shows: the app's files, then the node's sync and peers. */
    private suspend fun refreshBar() {
        val (local, node) = withContext(Dispatchers.IO) {
            val local = readLocal(context, state)
            val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
            var message = ""
            val node = runCatching {
                val sync = rpc.call("bcn_syncing") as JSONObject
                message = sync.optString("message")
                val peers = (rpc.call("net_peers") as? JSONArray)?.length() ?: 0
                val head = rpc.call("bcn_lastBlock") as JSONObject
                NodeView(
                    peers, sync.optBoolean("syncing"), sync.optLong("currentBlock"), sync.optLong("highestBlock"),
                    validation = false, wrongTime = sync.optBoolean("wrongTime"),
                    chainBlock = head.optLong("height"), headTime = head.optLong("timestamp").takeIf { it > 0 },
                )
            }.getOrNull()
            val startPhase = if (node == null && local.syncEnabled) readStartPhase(context, rpc) else null
            local.copy(startPhase = startPhase, nodeMessage = message) to node
        }
        state = withRates(local, node?.takeIf { it.syncing }?.let { (it.chainBlock ?: it.currentBlock) to it.currentBlock })
        view = node
    }

    /** [next] with the chain's and the headers' speeds, from [blocks] (chain, headers) while syncing. */
    private fun withRates(next: ScreenState, blocks: Pair<Long, Long>?): ScreenState =
        next.copy(blocksPerSecond = blocks?.let { chainRate.update(it.first) }, headersPerSecond = blocks?.let { headerRate.update(it.second) })

    /** The Sync switch was turned: the screen follows before the next read. */
    fun syncChanged(enabled: Boolean) {
        state = state.copy(syncEnabled = enabled, startError = null)
    }

    /** Another Advanced setting was chosen: the screen follows before the next read. */
    fun optionsChanged() {
        state = state.copy(options = NodeSettings.options(context))
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
    val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
    val status = try {
        rpc.status()
    } catch (e: Exception) {
        null
    }
    val local = readLocal(context, ScreenState(status = status, nodeMessage = status?.message.orEmpty()))
    return local.copy(
        hasKey = hasKey,
        startPhase = if (status == null && local.syncEnabled) readStartPhase(context, rpc) else null,
        // Reading the key needs the Keystore and the node library; only when the node does not answer.
        keyAddress = if (status == null && hasKey) {
            runCatching { NodeKeys.files(context).read() }.getOrNull()?.let { key ->
                try {
                    Mobile.privateKeyAddress(key)
                } finally {
                    key.fill(0)
                }
            }?.ifEmpty { null }
        } else {
            null
        },
    )
}

/**
 * [state] with what the app reads without the node: the switch, the start error, the network, the background,
 * the Advanced settings.
 */
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
        options = NodeSettings.options(context),
        runningOptions = NodeSettings.runningOptions(context),
    )
}

private fun networkName(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return t(R.string.node_network_offline)
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> t(R.string.node_network_mobile_data)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> t(R.string.node_network_other)
    }
}
