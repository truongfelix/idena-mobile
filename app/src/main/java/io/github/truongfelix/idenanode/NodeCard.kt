package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
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
                    Text(t(R.string.node_sync_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.startError != null -> t(R.string.node_stopped_could_not_start)
                            status != null && status.syncing -> t(R.string.node_running_syncing)
                            status != null && headIsStale(status.headTime, System.currentTimeMillis() / 1000) -> t(R.string.node_running_behind)
                            status != null -> t(R.string.node_running_synced)
                            state.syncEnabled -> state.startPhase?.let { startLabel(it, state.runningForMs) } ?: t(R.string.node_starting)
                            !state.hasKey -> t(R.string.node_stopped_no_key)
                            else -> t(R.string.node_stopped)
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
                    // The step's share: the headers', or the chain's ([syncStep]).
                    val step = syncStep(status.stateBlock, status.currentBlock, status.highestBlock, (state.blocksPerSecond ?: 0.0) > 0)
                    LinearProgressIndicator(
                        progress = { if (step.total > 0) step.done.toFloat() / step.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            mining()
            if (backgroundIssue(state.ignoringBatteryOptimizations) != null) BackgroundWarning(state)
            LanguageRow()
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
            t(R.string.node_may_stop_screen_off),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { open = true }) { Text(t(R.string.node_fix)) }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { BackgroundSettings(state) } },
            confirmButton = { TextButton(onClick = { open = false }) { Text(t(R.string.common_close)) } },
        )
    }
}

/** A mining change the validator confirms: the dialog text and the node call. */
enum class MiningChange(@StringRes private val titleId: Int, @StringRes private val textId: Int) {
    Start(R.string.node_mine_on_phone, R.string.node_mining_start_text),
    Stop(R.string.node_stop_mining, R.string.node_mining_stop_text),
    Delegate(R.string.node_delegate, R.string.node_mining_delegate_text),
    Undelegate(R.string.node_undelegate, R.string.node_mining_undelegate_text),
    ;

    val title: String get() = t(titleId)
    val text: String get() = t(textId)
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
                mining.message = t(R.string.node_refused, "${it.message}")
                return@launch
            }
            mining.pendingTx = hash
            val mined = waitUntilMined(context, hash)
            mining.pendingTx = null
            mining.message = if (mined) t(R.string.node_mining_done, change.title)
            else t(R.string.node_mining_not_sent, change.title, NOT_SENT)
            onChanged()
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The switch sends the change, after its dialog; delegation is in the advanced settings.
        val miningHere = status.online && status.delegatee == null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t(R.string.node_mine_on_phone), style = MaterialTheme.typography.titleMedium)
                Text(status.miningStatus, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = miningHere,
                onCheckedChange = { on -> mining.change = if (on) MiningChange.Start else MiningChange.Stop },
                enabled = mining.pendingTx == null && (actions.canStartMining || actions.canStopMining),
            )
        }
        if (status.period == "None" && status.delegatee == null && !status.isPool && status.identityState !in validatedStates) {
            Text(t(R.string.node_mining_needs_validated), style = MaterialTheme.typography.bodySmall)
        }
        if (status.penaltySeconds > 0) {
            Field(t(R.string.node_field_penalty), t(R.string.node_penalty_left, duration(status.penaltySeconds.toDouble())))
        }
        mining.pendingTx?.let { Text(t(R.string.node_tx_waiting, it.take(10)), style = MaterialTheme.typography.bodySmall) }
        mining.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (status.period != "None") Text(t(R.string.node_mining_wait_validation), style = MaterialTheme.typography.bodySmall)
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
                        OutlinedTextField(value = mining.pool, onValueChange = { mining.pool = it }, label = { Text(t(R.string.node_pool_address)) }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { mining.change = null; send(selected) },
                    enabled = selected != MiningChange.Delegate || poolValid,
                ) { Text(t(R.string.common_send)) }
            },
            dismissButton = { TextButton(onClick = { mining.change = null }) { Text(t(R.string.common_cancel)) } },
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
            status.period != "None" -> Text(t(R.string.node_delegation_wait_validation), style = small)
            mining.pendingTx != null -> Text(t(R.string.node_mining_change_waiting), style = small)
            else -> {
                if (actions.canDelegate) OutlinedButton(onClick = { mining.change = MiningChange.Delegate }) { Text(MiningChange.Delegate.title) }
                if (actions.canUndelegate) OutlinedButton(onClick = { mining.change = MiningChange.Undelegate }) { Text(MiningChange.Undelegate.title) }
                if (status.delegatee != null && !actions.canUndelegate) {
                    Text(t(R.string.node_delegation_next_epoch), style = small)
                }
                if (status.delegatee == null && !actions.canDelegate && status.penaltySeconds > 0) {
                    Text(t(R.string.node_no_delegation_penalty), style = small)
                }
                if (status.isPool) Text(t(R.string.node_identity_is_pool), style = small)
            }
        }
    }
}
