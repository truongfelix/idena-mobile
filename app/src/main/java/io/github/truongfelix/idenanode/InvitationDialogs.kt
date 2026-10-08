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
        Image(bitmap = qr, contentDescription = "QR code of the invitation code", modifier = Modifier.size(220.dp), filterQuality = FilterQuality.None)
        SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Give it privately: anyone with the code can use the invitation. It works until the next validation.",
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
        title = { Text("Invitation sent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeView(invite.code.orEmpty())
                if (!saved) {
                    Text(
                        "The app could not keep this code: copy it now, it is shown only once.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(invite.code.orEmpty())); copied = true }) {
                Text(if (copied) "Copied" else "Copy the code")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
        title = { Text("Invite someone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Person.MAX_NAME) },
                    label = { Text("Name (only on this phone)") },
                    singleLine = true,
                )
                Choice("A code to give privately", selected = !toAddress) { toAddress = false }
                Choice("An address", selected = toAddress) { toAddress = true }
                if (toAddress) {
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it.trim() },
                        label = { Text("Address (0x…)") },
                        singleLine = true,
                        isError = to.isNotEmpty() && !toValid,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (named.isNotEmpty()) {
                        TextButton(onClick = { picking = !picking }) { Text(if (picking) "Hide the contacts" else "Choose from Contacts") }
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
                    Text("The address must have no identity yet; they accept the invitation in their Idena app.", style = MaterialTheme.typography.bodySmall)
                }
                Text("No fee. It uses one of your invitations.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(name, if (toAddress) to.lowercase() else null) }, enabled = !toAddress || toValid) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Spoil a public code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "An invitation code shared publicly is taken by bots. Spoiling it activates it to a random " +
                        "address: it is wasted, and people learn to share codes privately. No fee.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Invitation code") },
                    isError = text.isNotBlank() && code == null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = { TextButton(onClick = { code?.let(onSpoil) }, enabled = code != null) { Text("Spoil") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
