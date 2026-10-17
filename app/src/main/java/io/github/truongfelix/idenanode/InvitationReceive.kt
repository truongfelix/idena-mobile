package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Receiving an invitation, in the Profile card: accepting one sent to the address, or activating a code. */

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * In the Profile card, an invitation sent to the identity's address, waiting to be accepted: the activation
 * transaction makes the identity a candidate for the next validation. Only outside the ceremony.
 */
@Composable
fun AcceptInvitationSection(status: NodeStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var pendingTx by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun accept() {
        message = null
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { rpc(context).acceptInvitation() }
            }.getOrElse {
                message = t(R.string.wallet_node_refused, it.message.toString())
                return@launch
            }
            pendingTx = hash
            val mined = waitUntilMined(context, hash)
            pendingTx = null
            message = if (mined) t(R.string.wallet_invite_accepted)
            else t(R.string.wallet_invite_not_accepted, NOT_SENT)
            onChanged()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t(R.string.wallet_invite_invitation), style = MaterialTheme.typography.titleSmall)
        Text(t(R.string.wallet_invite_you_are_invited))
        pendingTx?.let { Text(t(R.string.wallet_tx_waiting, it.take(10)), style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when {
            !canAcceptInvitation(status.period, status.identityState) ->
                Text(t(R.string.wallet_invite_accept_later), style = MaterialTheme.typography.bodySmall)
            pendingTx == null -> Button(onClick = { confirming = true }) { Text(t(R.string.wallet_invite_accept)) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(t(R.string.wallet_invite_accept)) },
            text = { Text(t(R.string.wallet_invite_accept_text)) },
            confirmButton = { TextButton(onClick = { confirming = false; accept() }) { Text(t(R.string.common_send)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(t(R.string.common_cancel)) } },
        )
    }
}

/**
 * In the Profile card, for an address without an identity: the invitation code field. Activating makes
 * the identity a Candidate for the next validation.
 */
@Composable
fun ActivateCodeSection(status: NodeStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val code = parseInvitationCode(text)

    fun activate(code: String) {
        message = null
        busy = t(R.string.wallet_invite_activating)
        scope.launch {
            val hash = withContext(Dispatchers.IO) { runCatching { rpc(context).activateInvite(code) } }.getOrElse {
                busy = null
                message = t(R.string.wallet_invite_code_refused, it.message.toString())
                return@launch
            }
            busy = t(R.string.wallet_invite_activated_waiting)
            if (!waitUntilMined(context, hash)) {
                busy = null
                message = t(R.string.wallet_invite_code_not_activated, NOT_SENT)
                return@launch
            }
            busy = null
            text = ""
            message = t(R.string.wallet_invite_activated)
            onChanged()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t(R.string.wallet_invite_code), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(t(R.string.wallet_invite_paste_code)) },
            isError = text.isNotBlank() && code == null,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.isNotBlank() && code == null) {
            Text(t(R.string.wallet_invite_code_format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { code?.let(::activate) }, enabled = code != null && busy == null && canActivateCode(status.identityState, status.period)) {
                Text(t(R.string.wallet_invite_activate))
            }
            Spacer(Modifier.size(12.dp))
            Text(t(R.string.wallet_invite_no_fee), style = MaterialTheme.typography.bodySmall)
        }
        if (status.period != "None") Text(t(R.string.wallet_invite_codes_outside), style = MaterialTheme.typography.bodySmall)
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(t(R.string.wallet_invite_give_address), style = MaterialTheme.typography.bodySmall)
    }
}
