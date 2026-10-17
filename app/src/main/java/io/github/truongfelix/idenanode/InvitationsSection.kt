package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
 * The invitations to send, in the Profile card ([Invitations.kt] has the rules): send, the invitations sent with their
 * status and code, cancel, spoil a public code. Receiving one: [InvitationReceive.kt]; the dialogs:
 * [InvitationDialogs.kt].
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

/** What the section asks the validator to confirm or shows after a send. */
private sealed interface InviteDialog {
    data object New : InviteDialog
    data class Code(val invite: SentInvite, val saved: Boolean) : InviteDialog
    data class Cancel(val row: InviteRow, val fee: BigDecimal) : InviteDialog
    data object Spoil : InviteDialog
}

/**
 * The Profile card's Invitations: invites left, "Invite someone", the invitations sent this epoch (from this phone,
 * then from another app) with their status, the code to give (copy or QR) and Cancel, and "Spoil a public
 * code".
 */
@Composable
fun InvitationsSection(status: NodeStatus, people: Map<String, Person>, onChanged: () -> Unit) {
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
        busy = t(R.string.wallet_invite_sending)
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
                else message = t(R.string.wallet_invite_sent_to, shortAddress(invite.receiver))
                reads++
                onChanged()
            }.onFailure { message = t(R.string.wallet_invite_refused, it.message.toString()) }
        }
    }

    fun askCancel(row: InviteRow) {
        message = null
        busy = t(R.string.wallet_invite_asking_fee)
        scope.launch {
            val fee = withContext(Dispatchers.IO) {
                runCatching { rpc(context).estimateTx(KILL_INVITEE_TX, status.address, row.address, BigDecimal.ZERO) }
            }
            busy = null
            fee.onSuccess { dialog = InviteDialog.Cancel(row, it) }.onFailure { message = t(R.string.wallet_invite_cancel_refused, it.message.toString()) }
        }
    }

    fun cancel(row: InviteRow) {
        dialog = null
        busy = t(R.string.wallet_invite_cancelling)
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { rpc(context).sendTx(KILL_INVITEE_TX, status.address, row.address, BigDecimal.ZERO) }
            }.getOrElse {
                busy = null
                message = t(R.string.wallet_node_refused, it.message.toString())
                return@launch
            }
            busy = t(R.string.wallet_invite_cancelled_waiting)
            val mined = waitUntilMined(context, hash)
            busy = null
            message = if (mined) t(R.string.wallet_invite_cancelled) else t(R.string.wallet_invite_not_cancelled, NOT_SENT)
            reads++
            onChanged()
        }
    }

    fun spoil(code: String) {
        dialog = null
        busy = t(R.string.wallet_invite_spoiling)
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { rpc(context).spoilInvite(code) } }
            busy = null
            message = result.fold(
                { t(R.string.wallet_invite_spoiled, it.take(10)) },
                { t(R.string.wallet_node_refused, it.message.toString()) },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(R.string.wallet_invite_invitations), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(tp(R.plurals.wallet_invite_left, status.invites, status.invites), style = MaterialTheme.typography.bodyMedium)
        }
        when {
            canSendInvite(status.invites, status.period) ->
                Button(onClick = { message = null; dialog = InviteDialog.New }, enabled = busy == null) { Text(t(R.string.wallet_invite_someone)) }
            status.invites == 0 -> Text(
                t(R.string.wallet_invite_none_to_send),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text(t(R.string.wallet_invite_outside), style = MaterialTheme.typography.bodySmall)
        }
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        rows.forEach { row ->
            key(row.sent?.txHash ?: row.address) {
                HorizontalDivider()
                InviteRowView(row, people, canCancel = busy == null && canCancelInvite(row.state, status.period), onCancel = { askCancel(row) })
            }
        }
        TextButton(onClick = { message = null; dialog = InviteDialog.Spoil }, enabled = busy == null) { Text(t(R.string.wallet_invite_spoil_title)) }
    }

    when (val current = dialog) {
        InviteDialog.New -> NewInviteDialog(status, people, onDismiss = { dialog = null }, onSend = ::send)
        is InviteDialog.Code -> CodeDialog(current.invite, current.saved) { dialog = null }
        is InviteDialog.Cancel -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(t(R.string.wallet_invite_cancel_title)) },
            text = {
                Text(
                    t(
                        if (current.row.state == "Candidate") R.string.wallet_invite_cancel_text_candidate else R.string.wallet_invite_cancel_text,
                        inviteName(current.row, people),
                        idna(current.fee.toPlainString()),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { cancel(current.row) }) { Text(t(R.string.wallet_invite_cancel_it)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(t(R.string.wallet_invite_keep_it)) } },
        )
        InviteDialog.Spoil -> SpoilDialog(onDismiss = { dialog = null }, onSpoil = ::spoil)
        null -> {}
    }
}

/** The name of an invitation: its private label, a contact's name, or "Invitation". */
private fun inviteName(row: InviteRow, people: Map<String, Person>): String =
    row.sent?.name?.takeIf { it.isNotEmpty() } ?: people[row.address]?.name?.takeIf { it.isNotEmpty() } ?: t(R.string.wallet_invite_invitation)

@Composable
private fun InviteRowView(row: InviteRow, people: Map<String, Person>, canCancel: Boolean, onCancel: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val code = row.sent?.code?.takeIf { row.state == "Invite" || row.pending }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(inviteName(row, people), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (canCancel) TextButton(onClick = onCancel) { Text(t(R.string.common_cancel)) }
        }
        if (code != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(R.string.wallet_invite_code_short, code.take(4), code.takeLast(4)), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) { Text(t(if (copied) R.string.wallet_copied else R.string.wallet_copy)) }
                TextButton(onClick = { showQr = true }) { Text("QR") }
            }
        } else {
            Text(shortAddress(row.address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (row.sent == null) t(R.string.wallet_invite_from_other_app, inviteStatus(row)) else inviteStatus(row),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showQr && code != null) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(t(R.string.wallet_invite_code)) },
            text = { CodeView(code) },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(t(R.string.common_close)) } },
        )
    }
}
