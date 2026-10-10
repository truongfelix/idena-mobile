package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* The Node tab's Node card: sync, mining on this phone, the screen-off warning; the delegation for its advanced settings. */

/**
 * The Node card, last on the Node tab: the sync switch, and the progress while syncing in a slot of fixed height, so
 * that the rows under it never move; [mining] (mining on this phone, once the node has the account); a warning while
 * Android may stop the node with the screen off ([BackgroundWarning]); then the [advanced] settings. The block, peers,
 * speed and network are in the title row's details (ⓘ).
 */
@Composable
fun NodeCard(
    state: ScreenState,
    mining: @Composable () -> Unit,
    advanced: @Composable () -> Unit,
    onSyncChange: (Boolean) -> Unit,
) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.startError != null -> "Stopped: the node could not start"
                            status != null && status.syncing -> "Running, syncing"
                            status != null -> "Running, synced"
                            state.syncEnabled -> "Starting…"
                            !state.hasKey -> "Stopped: create or import a key first"
                            else -> "Stopped"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
                // Without a key the node would create a random one that the app cannot back up.
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = onSyncChange,
                    enabled = state.hasKey || state.syncEnabled,
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (status != null && status.syncing && status.highestBlock > 0) {
                    LinearProgressIndicator(
                        progress = { status.currentBlock.toFloat() / maxOf(status.highestBlock, status.currentBlock).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            mining()
            if (backgroundIssue(state.ignoringBatteryOptimizations) != null) BackgroundWarning(state)
            advanced()
        }
    }
}

/**
 * While Android may stop the node with the screen off: one line, and Fix with the settings that let it run
 * ([BackgroundSettings], also in the title row's details).
 */
@Composable
private fun BackgroundWarning(state: ScreenState) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "The node may stop with the screen off",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { open = true }) { Text("Fix") }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { BackgroundSettings(state) } },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

/** A mining change the validator confirms: the dialog text and the node call. */
enum class MiningChange(val title: String, val text: String) {
    Start(
        "Mine on this phone",
        "Your identity mines with this phone's node, which must then keep running. If it stays offline for " +
            "about an hour, other nodes can switch your identity offline with a mining penalty.",
    ),
    Stop("Stop mining", "Your identity stops mining with this phone's node."),
    Delegate(
        "Delegate to a pool",
        "Your identity mines through the pool's node, and the pool receives your mining and validation " +
            "rewards. You can lose your stake and all these rewards to the pool. The delegation can be stopped " +
            "from the next epoch.",
    ),
    Undelegate("Stop delegation", "Your identity stops mining through the pool; the rewards come to your address again."),
}

/**
 * The mining change under way, shared by the Mining row ([MiningSection]) and the delegation in the advanced settings
 * ([DelegationSection]): the change being confirmed, the pool typed, the transaction waiting for its block, the outcome.
 */
class MiningActionState {
    var change by mutableStateOf<MiningChange?>(null)
    var pool by mutableStateOf("")
    var pendingTx by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
}

private fun miningActions(status: NodeStatus) = MiningActions.of(
    status.period, status.identityState, status.online, status.isPool, status.delegatee != null,
    status.delegationEpoch, status.epoch, status.penaltySeconds,
)

/**
 * Mining, in the Node card: the switch that mines with this phone's node, under the identity's mining status (with this
 * phone, delegated to a pool, or none). Confirms a change, a delegation from [DelegationSection] too, and follows the
 * transaction until it is in a block. The node applies online and delegation changes every 50 blocks (about 17 minutes).
 */
@Composable
fun MiningSection(status: NodeStatus, mining: MiningActionState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = miningActions(status)

    fun send(change: MiningChange) {
        mining.message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
                    when (change) {
                        MiningChange.Start -> rpc.startMining()
                        MiningChange.Stop -> rpc.stopMining()
                        MiningChange.Delegate -> rpc.delegate(mining.pool.trim())
                        MiningChange.Undelegate -> rpc.undelegate()
                    }
                }
            }
            val hash = result.getOrElse {
                mining.message = "The node refused: ${it.message}"
                return@launch
            }
            mining.pendingTx = hash
            val mined = waitUntilMined(context, hash)
            mining.pendingTx = null
            mining.message = if (mined) "${change.title}: done. The node applies it within 50 blocks (about 17 minutes)."
            else "${change.title}: not sent, $NOT_SENT"
            onChanged()
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The switch sends the change, after its dialog; delegation is in the advanced settings.
        val miningHere = status.online && status.delegatee == null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mine on this phone", style = MaterialTheme.typography.titleMedium)
                Text(status.miningStatus, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = miningHere,
                onCheckedChange = { on -> mining.change = if (on) MiningChange.Start else MiningChange.Stop },
                enabled = mining.pendingTx == null && (actions.canStartMining || actions.canStopMining),
            )
        }
        if (status.period == "None" && status.delegatee == null && !status.isPool && status.identityState !in validatedStates) {
            Text("Mining needs a validated identity (Newbie, Verified or Human).", style = MaterialTheme.typography.bodySmall)
        }
        if (status.penaltySeconds > 0) {
            Field("Penalty", "${duration(status.penaltySeconds.toDouble())} left")
        }
        mining.pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
        mining.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (status.period != "None") Text("Mining changes wait until the validation is over.", style = MaterialTheme.typography.bodySmall)
    }

    mining.change?.let { selected ->
        val poolValid = Regex("^0x[0-9a-fA-F]{40}$").matches(mining.pool.trim())
        AlertDialog(
            onDismissRequest = { mining.change = null },
            title = { Text(selected.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.text)
                    if (selected == MiningChange.Delegate) {
                        OutlinedTextField(value = mining.pool, onValueChange = { mining.pool = it }, label = { Text("Pool address (0x…)") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { mining.change = null; send(selected) },
                    enabled = selected != MiningChange.Delegate || poolValid,
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { mining.change = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Delegation, in the advanced settings: Delegate to a pool or Stop delegation when the node would accept it now (see
 * [MiningActions]). The Mining row confirms the change and follows its transaction ([MiningSection]).
 */
@Composable
fun DelegationSection(status: NodeStatus, mining: MiningActionState) {
    val actions = miningActions(status)
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            status.period != "None" -> Text("Delegation changes wait until the validation is over.", style = small)
            mining.pendingTx != null -> Text("A mining change is waiting for its block.", style = small)
            else -> {
                if (actions.canDelegate) OutlinedButton(onClick = { mining.change = MiningChange.Delegate }) { Text(MiningChange.Delegate.title) }
                if (actions.canUndelegate) OutlinedButton(onClick = { mining.change = MiningChange.Undelegate }) { Text(MiningChange.Undelegate.title) }
                if (status.delegatee != null && !actions.canUndelegate) {
                    Text("The delegation can be stopped from the next epoch.", style = small)
                }
                if (status.delegatee == null && !actions.canDelegate && status.penaltySeconds > 0) {
                    Text("No delegation while a mining penalty runs.", style = small)
                }
                if (status.isPool) Text("This identity is a pool: it does not delegate.", style = small)
            }
        }
    }
}
