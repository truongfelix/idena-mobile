package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

/*
 * The invitations on the Node tab ([Invitations.kt] has the rules): the Invitations card (send, the
 * invitations sent with their status and code, cancel, spoil a public code) and the code field of the
 * Account card for an address without an identity.
 */

/** KillInviteeTx: the inviter takes back an invitee. */
const val KILL_INVITEE_TX = 0xA

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The invitations this phone sent (files/invites.json, in the app's private storage: the codes are keys).
 * Only those of the current epoch are kept.
 */
object InviteStore {
    private fun file(context: Context) = File(context.filesDir, "invites.json")

    @Synchronized
    fun load(context: Context, epoch: Int): List<SentInvite> =
        runCatching { parseSentInvites(file(context).readText(), epoch) }.getOrDefault(emptyList())

    /** Adds [invite], dropping the invitations of other epochs; written to a new file, then renamed. */
    @Synchronized
    fun add(context: Context, invite: SentInvite) {
        val invites = load(context, invite.epoch) + invite
        val target = file(context)
        val temp = File(target.parentFile, "invites.json.new")
        temp.writeText(sentInvitesJson(invites))
        check(temp.renameTo(target)) { "cannot save the invitation" }
    }
}

/** What the card asks the validator to confirm or shows after a send. */
private sealed interface InviteDialog {
    data object New : InviteDialog
    data class Code(val invite: SentInvite, val saved: Boolean) : InviteDialog
    data class Cancel(val row: InviteRow, val fee: BigDecimal) : InviteDialog
    data object Spoil : InviteDialog
}

/**
 * The Invitations card: invites left, "Invite someone", the invitations sent this epoch (from this phone,
 * then from another app) with their status, the code to give (copy or QR) and Cancel, and "Spoil a public
 * code".
 */
@Composable
fun InvitationsCard(status: NodeStatus, people: Map<String, Person>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<InviteRow>>(emptyList()) }
    var reads by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<InviteDialog?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Read again at each block: the receivers' states change with the blocks. Only the identity's own
    // invitations: those of another key imported before stay in the file.
    LaunchedEffect(status.address, status.epoch, status.invitees, status.currentBlock, reads) {
        rows = withContext(Dispatchers.IO) {
            val sent = InviteStore.load(context, status.epoch).filter { it.sentBy(status.address) }
            val node = rpc(context)
            val byTx = status.invitees.associateBy { it.txHash }
            val addresses = (sent.map { byTx[it.txHash]?.address ?: it.receiver } + status.invitees.map { it.address }).distinct()
            val states = addresses.mapNotNull { address -> runCatching { address to node.identityState(address) }.getOrNull() }.toMap()
            val mined = sent.filter { it.txHash !in byTx && runCatching { node.isMined(it.txHash) }.getOrDefault(false) }
                .map { it.txHash }.toSet()
            inviteRows(sent, status.invitees, states, mined)
        }
    }

    fun send(name: String, to: String?) {
        dialog = null
        busy = "Sending the invitation…"
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tx = rpc(context).sendInvite(to)
                    val invite = SentInvite(
                        cleanName(name), tx.receiver, tx.code, tx.hash, status.epoch, System.currentTimeMillis(), status.address.lowercase(),
                    )
                    // The code exists nowhere else: kept before it is shown, and shown even if it could not be kept.
                    invite to runCatching { InviteStore.add(context, invite) }.isSuccess
                }
            }
            busy = null
            result.onSuccess { (invite, saved) ->
                if (invite.code != null) dialog = InviteDialog.Code(invite, saved)
                else message = "Invitation sent to ${shortAddress(invite.receiver)}: they accept it in their Idena app."
                reads++
                onChanged()
            }.onFailure { message = "The node refused the invitation: ${it.message}" }
        }
    }

    fun askCancel(row: InviteRow) {
        message = null
        busy = "Asking the node for the fee…"
        scope.launch {
            val fee = withContext(Dispatchers.IO) {
                runCatching { rpc(context).estimateTx(KILL_INVITEE_TX, status.address, row.address, BigDecimal.ZERO) }
            }
            busy = null
            fee.onSuccess { dialog = InviteDialog.Cancel(row, it) }.onFailure { message = "The node refuses to cancel it: ${it.message}" }
        }
    }

    fun cancel(row: InviteRow) {
        dialog = null
        busy = "Cancelling the invitation…"
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { rpc(context).sendTx(KILL_INVITEE_TX, status.address, row.address, BigDecimal.ZERO) }
            }.getOrElse {
                busy = null
                message = "The node refused: ${it.message}"
                return@launch
            }
            busy = "Cancelled: waiting for a block…"
            val mined = waitUntilMined(context, hash)
            busy = null
            message = if (mined) "Invitation cancelled." else "The invitation is not cancelled: $NOT_SENT"
            reads++
            onChanged()
        }
    }

    fun spoil(code: String) {
        dialog = null
        busy = "Spoiling the code…"
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { rpc(context).spoilInvite(code) } }
            busy = null
            message = result.fold(
                { "Code spoiled: nobody can use it any more once the transaction is in a block (${it.take(10)}…)." },
                { "The node refused: ${it.message}" },
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Invitations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (status.invites == 1) "1 left" else "${status.invites} left", style = MaterialTheme.typography.bodyMedium)
            }
            when {
                canSendInvite(status.invites, status.period) ->
                    Button(onClick = { message = null; dialog = InviteDialog.New }, enabled = busy == null) { Text("Invite someone") }
                status.invites == 0 -> Text(
                    "No invitation to send. Verified and Human identities receive invitations at each validation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text("Invitations are sent outside the validation.", style = MaterialTheme.typography.bodySmall)
            }
            busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            rows.forEach { row ->
                key(row.sent?.txHash ?: row.address) {
                    HorizontalDivider()
                    InviteRowView(row, people, canCancel = busy == null && canCancelInvite(row.state, status.period), onCancel = { askCancel(row) })
                }
            }
            TextButton(onClick = { message = null; dialog = InviteDialog.Spoil }, enabled = busy == null) { Text("Spoil a public code") }
        }
    }

    when (val current = dialog) {
        InviteDialog.New -> NewInviteDialog(status, people, onDismiss = { dialog = null }, onSend = ::send)
        is InviteDialog.Code -> CodeDialog(current.invite, current.saved) { dialog = null }
        is InviteDialog.Cancel -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Cancel the invitation") },
            text = {
                Text(
                    "${inviteName(current.row, people)} loses the invitation" +
                        (if (current.row.state == "Candidate") " and will not take part in the validation" else "") +
                        ". Your invitation is not given back. Fee: ${idna(current.fee.toPlainString())}.",
                )
            },
            confirmButton = { TextButton(onClick = { cancel(current.row) }) { Text("Cancel it") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep it") } },
        )
        InviteDialog.Spoil -> SpoilDialog(onDismiss = { dialog = null }, onSpoil = ::spoil)
        null -> {}
    }
}

/** The name of an invitation: its private label, a contact's name, or "Invitation". */
private fun inviteName(row: InviteRow, people: Map<String, Person>): String =
    row.sent?.name?.takeIf { it.isNotEmpty() } ?: people[row.address]?.name?.takeIf { it.isNotEmpty() } ?: "Invitation"

@Composable
private fun InviteRowView(row: InviteRow, people: Map<String, Person>, canCancel: Boolean, onCancel: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val code = row.sent?.code?.takeIf { row.state == "Invite" || row.pending }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(inviteName(row, people), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (code != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Code ${code.take(4)}…${code.takeLast(4)}", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) { Text(if (copied) "Copied" else "Copy") }
                TextButton(onClick = { showQr = true }) { Text("QR") }
            }
        } else {
            Text(shortAddress(row.address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            (if (row.sent == null) "Sent from another app · " else "") + inviteStatus(row),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showQr && code != null) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Invitation code") },
            text = { CodeView(code) },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Close") } },
        )
    }
}

/** The code as a QR code and as text, with the warning that goes with it. */
@Composable
private fun CodeView(code: String) {
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
private fun CodeDialog(invite: SentInvite, saved: Boolean, onDismiss: () -> Unit) {
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
private fun NewInviteDialog(status: NodeStatus, people: Map<String, Person>, onDismiss: () -> Unit, onSend: (String, String?) -> Unit) {
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
private fun SpoilDialog(onDismiss: () -> Unit, onSpoil: (String) -> Unit) {
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

/**
 * In the Account card, for an address without an identity: the invitation code field. Activating makes
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
