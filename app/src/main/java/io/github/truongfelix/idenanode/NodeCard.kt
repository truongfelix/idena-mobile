package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/* The Node tab's Sync card and its Mining section. */

/**
 * Sync: the switch, and the progress while syncing in a slot of fixed height, so that the cards under it
 * never move; [mining] (the identity's mining, once the node has the account); the [routerPort] on Wi-Fi; then
 * the [advanced] settings. The block, peers, speed and network are in the title row's details (ⓘ).
 */
@Composable
fun NodeCard(
    state: ScreenState,
    mining: @Composable () -> Unit,
    routerPort: @Composable () -> Unit,
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
                            !state.hasKey -> "Stopped: import a key first"
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
            routerPort()
            advanced()
        }
    }
}

/** A mining change the validator confirms: the dialog text and the node call. */
private enum class MiningChange(val title: String, val text: String) {
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
 * Mining, in the Sync card: with this phone's node, delegated to a pool, or none. Offers the changes the node would accept
 * now (see [MiningActions]) and follows the transaction until it is in a block. The node applies online
 * and delegation changes every 50 blocks (about 17 minutes).
 */
@Composable
fun MiningSection(status: NodeStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = MiningActions.of(
        status.period, status.identityState, status.online, status.isPool, status.delegatee != null,
        status.delegationEpoch, status.epoch, status.penaltySeconds,
    )
    var change by remember { mutableStateOf<MiningChange?>(null) }
    var pool by remember { mutableStateOf("") }
    var pendingTx by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun send(change: MiningChange) {
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
                    when (change) {
                        MiningChange.Start -> rpc.startMining()
                        MiningChange.Stop -> rpc.stopMining()
                        MiningChange.Delegate -> rpc.delegate(pool.trim())
                        MiningChange.Undelegate -> rpc.undelegate()
                    }
                }
            }
            val hash = result.getOrElse {
                message = "The node refused: ${it.message}"
                return@launch
            }
            pendingTx = hash
            val mined = waitUntilMined(context, hash)
            pendingTx = null
            message = if (mined) "${change.title}: done. The node applies it within 50 blocks (about 17 minutes)."
            else "${change.title}: not sent, $NOT_SENT"
            onChanged()
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mining", style = MaterialTheme.typography.titleMedium)
        run {
            Field("Status", status.miningStatus)
            // Mining on this phone: the switch sends the change (after its dialog); delegation below.
            val miningHere = status.online && status.delegatee == null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mine on this phone", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = miningHere,
                    onCheckedChange = { on -> change = if (on) MiningChange.Start else MiningChange.Stop },
                    enabled = pendingTx == null && (actions.canStartMining || actions.canStopMining),
                )
            }
            if (status.period == "None" && status.delegatee == null && !status.isPool && status.identityState !in validatedStates) {
                Text("Mining needs a validated identity (Newbie, Verified or Human).", style = MaterialTheme.typography.bodySmall)
            }
            if (status.penaltySeconds > 0) {
                Field("Penalty", "${duration(status.penaltySeconds.toDouble())} left")
            }
            pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                status.period != "None" -> Text("Mining changes wait until the validation is over.", style = MaterialTheme.typography.bodySmall)
                pendingTx == null -> {
                    if (actions.canDelegate) OutlinedButton(onClick = { change = MiningChange.Delegate }) { Text(MiningChange.Delegate.title) }
                    if (actions.canUndelegate) OutlinedButton(onClick = { change = MiningChange.Undelegate }) { Text(MiningChange.Undelegate.title) }
                    if (status.delegatee != null && !actions.canUndelegate) {
                        Text("The delegation can be stopped from the next epoch.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (status.delegatee == null && !actions.canDelegate && status.penaltySeconds > 0) {
                        Text("No delegation while a mining penalty runs.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    change?.let { selected ->
        val poolValid = Regex("^0x[0-9a-fA-F]{40}$").matches(pool.trim())
        AlertDialog(
            onDismissRequest = { change = null },
            title = { Text(selected.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.text)
                    if (selected == MiningChange.Delegate) {
                        OutlinedTextField(value = pool, onValueChange = { pool = it }, label = { Text("Pool address (0x…)") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { change = null; send(selected) },
                    enabled = selected != MiningChange.Delegate || poolValid,
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { change = null }) { Text("Cancel") } },
        )
    }
}
