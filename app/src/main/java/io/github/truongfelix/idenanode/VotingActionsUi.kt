package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.math.BigDecimal
import java.time.Instant

/** What the page says when the node refused an action ([actionErrorText]), or could not be reached. */
private fun refusalText(e: Throwable, voting: OracleVoting): String = when (e) {
    is ConnectException, is SocketTimeoutException -> t(R.string.voting_node_no_answer, e.message.toString())
    else -> actionErrorText(e.message, voting, ::dateText)
}

/** An action the node estimated: what it sends and costs, waiting for the confirmation. */
private data class PlannedAction(val action: VotingAction, val amount: BigDecimal, val cost: TxCost)

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

private fun dateText(time: Instant): String = "${dateFormat.format(time)} (${timeUntil(Instant.now(), time)})"

/**
 * A voting's actions as small pills ([votingActions], from the node's estimates for [me]). A tap asks the node for the
 * cost (Add funds first asks the amount), a confirmation names the amount and the fee, then the action is sent and
 * followed until it is in a block; [onDone] then reads the voting again.
 */
@Composable
fun VotingActionPills(voting: OracleVoting, me: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember(voting) { mutableStateOf<ActionChecks?>(null) }
    var askAmount by remember { mutableStateOf(false) }
    var planned by remember { mutableStateOf<PlannedAction?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(voting) {
        checks = withContext(Dispatchers.IO) { runCatching { VotingOwnerNode(rpc(context)).checks(me, voting) }.getOrNull() }
    }

    fun plan(action: VotingAction, amount: BigDecimal) {
        message = null
        busy = t(R.string.voting_checking_with_node)
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    // A node behind the chain estimates against an old state.
                    if (!rpc(context).synced()) null else VotingOwnerNode(rpc(context)).plan(action, me, voting, amount)
                }
            }
            busy = null
            made.onSuccess { cost ->
                if (cost == null) message = t(R.string.voting_syncing_try_later) else planned = PlannedAction(action, amount, cost)
            }.onFailure { message = refusalText(it, voting) }
        }
    }

    fun send(ready: PlannedAction) {
        val label = actionLabel(ready.action, voting, checks)
        busy = t(R.string.voting_action_busy, label)
        scope.launch {
            message = withContext(Dispatchers.IO) {
                val rpc = rpc(context)
                val hash = runCatching { VotingOwnerNode(rpc).send(ready.action, me, voting, ready.amount, ready.cost) }
                    .getOrElse { return@withContext t(R.string.voting_action_not_sent_reason, label, refusalText(it, voting)) }
                val block = waitForBlock(NodeTxLookup(rpc), hash)
                when {
                    block == null -> t(R.string.voting_action_not_sent, label, NOT_SENT)
                    block.error != null -> t(R.string.voting_action_result, label, actionErrorText(block.error, voting, ::dateText))
                    else -> t(R.string.voting_action_done, label)
                }
            }
            busy = null
            onDone()
        }
    }

    val actions = votingActions(voting, checks)
    if (actions.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                AssistChip(
                    onClick = {
                        when (action) {
                            VotingAction.AddFunds -> askAmount = true
                            VotingAction.Launch -> plan(action, launchAmount(voting))
                            else -> plan(action, BigDecimal.ZERO)
                        }
                    },
                    enabled = busy == null,
                    label = { Text(actionLabel(action, voting, checks)) },
                )
            }
        }
    }
    busy?.let {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

    if (askAmount) {
        AddFundsDialog(onDismiss = { askAmount = false }) {
            askAmount = false
            plan(VotingAction.AddFunds, it)
        }
    }
    planned?.let { ready ->
        AlertDialog(
            onDismissRequest = { planned = null },
            title = { Text(actionLabel(ready.action, voting, checks)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(actionConfirmText(ready.action, voting, me, ready.amount, ready.cost)) } },
            confirmButton = {
                TextButton(onClick = {
                    planned = null
                    send(ready)
                }) { Text(actionLabel(ready.action, voting, checks)) }
            },
            dismissButton = { TextButton(onClick = { planned = null }) { Text(t(R.string.common_cancel)) } },
        )
    }
}

/** The amount Add funds sends; Next asks the node for the fee. */
@Composable
private fun AddFundsDialog(onDismiss: () -> Unit, onNext: (BigDecimal) -> Unit) {
    var text by remember { mutableStateOf("") }
    val amount = text.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.voting_action_add_funds)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.voting_add_funds_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(t(R.string.voting_amount_idna)) },
                    singleLine = true,
                    isError = text.isNotEmpty() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { amount?.let(onNext) }, enabled = amount != null) { Text(t(R.string.common_next)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}
