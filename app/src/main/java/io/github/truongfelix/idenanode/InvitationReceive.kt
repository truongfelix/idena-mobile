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
                message = "The node refused: ${it.message}"
                return@launch
            }
            pendingTx = hash
            val mined = waitUntilMined(context, hash)
            pendingTx = null
            message = if (mined) "Invitation accepted: the identity is a candidate for the next validation."
            else "The invitation is not accepted: $NOT_SENT"
            onChanged()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Invitation", style = MaterialTheme.typography.titleSmall)
        Text("You are invited to Idena. Accept the invitation to take part in the next validation as a candidate.")
        pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when {
            !canAcceptInvitation(status.period, status.identityState) ->
                Text("The invitation can be accepted once the validation is over.", style = MaterialTheme.typography.bodySmall)
            pendingTx == null -> Button(onClick = { confirming = true }) { Text("Accept the invitation") }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Accept the invitation") },
            text = { Text("The node sends the activation transaction of your address. Your identity becomes a candidate: take part in the next validation to become a Newbie.") },
            confirmButton = { TextButton(onClick = { confirming = false; accept() }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
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
        busy = "Activating…"
        scope.launch {
            val hash = withContext(Dispatchers.IO) { runCatching { rpc(context).activateInvite(code) } }.getOrElse {
                busy = null
                message = "The node refused the code: ${it.message}"
                return@launch
            }
            busy = "Activated: waiting for a block…"
            if (!waitUntilMined(context, hash)) {
                busy = null
                message = "The code is not activated: $NOT_SENT"
                return@launch
            }
            busy = null
            text = ""
            message = "Invitation activated: the identity is a candidate for the next validation."
            onChanged()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Invitation code", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Paste the code here") },
            isError = text.isNotBlank() && code == null,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.isNotBlank() && code == null) {
            Text("A code is 64 letters and digits (0-9, a-f).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { code?.let(::activate) }, enabled = code != null && busy == null && canActivateCode(status.identityState, status.period)) {
                Text("Activate")
            }
            Spacer(Modifier.size(12.dp))
            Text("No fee", style = MaterialTheme.typography.bodySmall)
        }
        if (status.period != "None") Text("Codes are activated outside the validation.", style = MaterialTheme.typography.bodySmall)
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Or give your address to someone who invites you.", style = MaterialTheme.typography.bodySmall)
    }
}
