package io.github.truongfelix.idenanode

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/* The dialogs of the Profile card's Invitations: the code to give, a new invitation, spoiling a public code. */

/** The code as a QR code and as text, with the warning that goes with it. */
@Composable
fun CodeView(code: String) {
    val qr = remember(code) { qrCode(code) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(bitmap = qr, contentDescription = t(R.string.wallet_invite_code_qr), modifier = Modifier.size(220.dp), filterQuality = FilterQuality.None)
        SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text(
            t(R.string.wallet_invite_give_privately),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The invitation just sent to a code: the code to give, to copy or show as a QR code. */
@Composable
fun CodeDialog(invite: SentInvite, saved: Boolean, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.wallet_invite_sent)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeView(invite.code.orEmpty())
                if (!saved) {
                    Text(
                        t(R.string.wallet_invite_not_kept),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(invite.code.orEmpty())); copied = true }) {
                Text(t(if (copied) R.string.wallet_copied else R.string.wallet_invite_copy_code))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}

/** A new invitation: a private name, and a code to give or an address (typed, pasted or from Contacts). */
@Composable
fun NewInviteDialog(status: NodeStatus, people: Map<String, Person>, onDismiss: () -> Unit, onSend: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var toAddress by remember { mutableStateOf(false) }
    var to by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val named = remember(people) { sortedPeople(people).filter { it.name.isNotEmpty() && it.address != status.address.lowercase() } }
    val toValid = isAddress(to) && !to.equals(status.address, ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.wallet_invite_someone)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Person.MAX_NAME) },
                    label = { Text(t(R.string.wallet_invite_name)) },
                    singleLine = true,
                )
                Choice(t(R.string.wallet_invite_to_code), selected = !toAddress) { toAddress = false }
                Choice(t(R.string.wallet_invite_to_address), selected = toAddress) { toAddress = true }
                if (toAddress) {
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it.trim() },
                        label = { Text(t(R.string.wallet_invite_address)) },
                        singleLine = true,
                        isError = to.isNotEmpty() && !toValid,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (named.isNotEmpty()) {
                        TextButton(onClick = { picking = !picking }) { Text(t(if (picking) R.string.wallet_hide_contacts else R.string.wallet_choose_contacts)) }
                        if (picking) {
                            named.forEach { person ->
                                TextButton(onClick = {
                                    to = person.address
                                    if (name.isEmpty()) name = person.name
                                    picking = false
                                }) { Text("${person.name}  ·  ${shortAddress(person.address)}", maxLines = 1) }
                            }
                        }
                    }
                    Text(t(R.string.wallet_invite_address_note), style = MaterialTheme.typography.bodySmall)
                }
                Text(t(R.string.wallet_invite_no_fee_uses), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(name, if (toAddress) to.lowercase() else null) }, enabled = !toAddress || toValid) { Text(t(R.string.common_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.size(4.dp))
        Text(text)
    }
}

/** Spoiling someone's code shared publicly: activated to a random address, so that bots cannot use it. */
@Composable
fun SpoilDialog(onDismiss: () -> Unit, onSpoil: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val code = parseInvitationCode(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.wallet_invite_spoil_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    t(R.string.wallet_invite_spoil_text),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(t(R.string.wallet_invite_code)) },
                    isError = text.isNotBlank() && code == null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = { TextButton(onClick = { code?.let(onSpoil) }, enabled = code != null) { Text(t(R.string.wallet_invite_spoil)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}
