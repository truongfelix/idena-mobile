package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** A transfer (with its comment's [payload], if any) or a stake replenishment waiting for the user's confirmation, with the node's fee. */
private data class PendingTx(
    val type: Int, val to: String, val amount: BigDecimal, val title: String, val summary: String,
    val payload: String? = null, val fee: BigDecimal = BigDecimal.ZERO,
)

private enum class WalletDialog { Send, Receive, Stake }

/**
 * The wallet of the node's identity: Receive (the address and its QR code), Send and Add stake. The node
 * estimates each transaction's fee, the user confirms, the node signs and sends, and the card follows it
 * until it is in a block. Sending and staking wait while the node syncs or a validation runs (the node
 * refuses a stake replenishment from the flip lottery on).
 */
@Composable
fun WalletCard(status: NodeStatus, people: Map<String, Person>, names: Map<String, String>, network: NetworkRewards?, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<WalletDialog?>(null) }
    var confirm by remember { mutableStateOf<PendingTx?>(null) }
    var pendingTx by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canTransact = status.period == "None" && !status.syncing && pendingTx == null && !busy

    fun prepare(tx: PendingTx) {
        busy = true
        message = null
        scope.launch {
            val fee = withContext(Dispatchers.IO) { runCatching { rpc(context).estimateTx(tx.type, status.address, tx.to, tx.amount, tx.payload) } }
            busy = false
            fee.onSuccess { confirm = tx.copy(fee = it) }.onFailure { message = t(R.string.wallet_node_refused, it.message.toString()) }
        }
    }

    fun send(tx: PendingTx) {
        busy = true
        scope.launch {
            val hash = withContext(Dispatchers.IO) { runCatching { rpc(context).sendTx(tx.type, status.address, tx.to, tx.amount, tx.payload) } }
                .getOrElse {
                    busy = false
                    message = t(R.string.wallet_node_refused, it.message.toString())
                    return@launch
                }
            busy = false
            pendingTx = hash
            val mined = waitUntilMined(context, hash)
            pendingTx = null
            message = if (mined) t(R.string.wallet_tx_done, tx.title) else t(R.string.wallet_tx_not_sent, tx.title, NOT_SENT)
            onChanged()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t(R.string.wallet_title), style = MaterialTheme.typography.titleMedium)
            Field(t(R.string.wallet_balance), idna(status.balance))
            Field(t(R.string.wallet_stake), idna(status.stake))
            // The stake's details, from the Account card before 0.1.35.
            val stake = status.stake.toDoubleOrNull() ?: 0.0
            val locked = status.lockedStake.toDoubleOrNull() ?: 0.0
            if (locked > 0) Field(t(R.string.wallet_locked), idna(status.lockedStake))
            val miss = stakeProtection(status.identityState, status.age, stake, locked, Outcome.Miss)
            val fail = stakeProtection(status.identityState, status.age, stake, locked, Outcome.Fail)
            if (miss != null && fail != null) {
                Field(t(R.string.wallet_protection), t(R.string.wallet_protection_value, percent(miss), percent(fail)))
            }
            network?.let { apy(stake, it) }?.let {
                Field(t(R.string.wallet_apy), t(R.string.wallet_apy_value, percent(it.yearly)))
                Text(
                    t(R.string.wallet_apy_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val padding = PaddingValues(horizontal = 14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = WalletDialog.Send }, enabled = canTransact, contentPadding = padding) { Text(t(R.string.common_send)) }
                OutlinedButton(onClick = { dialog = WalletDialog.Receive }, contentPadding = padding) { Text(t(R.string.wallet_receive)) }
                OutlinedButton(
                    onClick = { dialog = WalletDialog.Stake },
                    enabled = canTransact && canReceiveStake(status.identityState),
                    contentPadding = padding,
                ) { Text(t(R.string.wallet_add_stake)) }
            }
            pendingTx?.let { Text(t(R.string.wallet_tx_waiting, it.take(10)), style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                status.period != "None" -> Text(t(R.string.wallet_wait_validation), style = MaterialTheme.typography.bodySmall)
                status.syncing -> Text(t(R.string.wallet_wait_sync), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (dialog) {
        WalletDialog.Receive -> ReceiveDialog(status.address) { dialog = null }
        WalletDialog.Send -> SendDialog(status, people, names, onDismiss = { dialog = null }) { to, name, amount, comment ->
            dialog = null
            val shown = name?.let { "@$it ($to)" } ?: recipientText(to, names)
            var summary = t(R.string.wallet_send_summary, idna(amount.toPlainString()), shown)
            val bytes = commentBytes(comment)
            if (bytes != null) summary += "\n\n" + t(R.string.wallet_send_comment_line, comment.trim())
            prepare(PendingTx(SEND_TX, to, amount, t(R.string.common_send), summary, commentPayload(bytes)))
        }
        WalletDialog.Stake -> StakeDialog(status, onDismiss = { dialog = null }) { amount ->
            dialog = null
            prepare(
                PendingTx(
                    REPLENISH_STAKE_TX, status.address, amount, t(R.string.wallet_add_stake),
                    t(R.string.wallet_stake_summary, idna(amount.toPlainString())),
                ),
            )
        }
        null -> {}
    }

    confirm?.let { tx ->
        val fee = tx.fee.setScale(4, RoundingMode.UP).stripTrailingZeros().toPlainString()
        val maxFee = (tx.fee * BigDecimal(2)).setScale(4, RoundingMode.UP).stripTrailingZeros().toPlainString()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tx.title) },
            text = { Text(t(R.string.wallet_confirm_fee, tx.summary, fee, maxFee)) },
            confirmButton = { TextButton(onClick = { confirm = null; send(tx) }) { Text(t(R.string.common_send)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t(R.string.common_cancel)) } },
        )
    }
}

/** The identity's address as text and as a QR code, with a copy button. */
@Composable
private fun ReceiveDialog(address: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val qr = remember(address) { qrCode(address) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.wallet_receive_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Dark modules on white whatever the theme, so that any scanner reads it.
                Image(bitmap = qr, contentDescription = t(R.string.wallet_address_qr), modifier = Modifier.size(220.dp), filterQuality = FilterQuality.None)
                SelectionContainer {
                    Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                Text(t(R.string.wallet_receive_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(address))
                copied = true
            }) { Text(t(if (copied) R.string.wallet_copied else R.string.wallet_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}

/** [text] as a QR code image, one pixel per module (drawn without smoothing). */
internal fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * The recipient (typed, pasted or picked among the named people of the Social tab's People list; "@name" sends to
 * the holder of a name in the profiles contract, shown with its robot, address and state) and the amount; checked
 * here, then by the node's fee estimate; an optional comment. [onNext] gets the address, for "@name" the name, the
 * amount and the comment as typed.
 */
@Composable
private fun SendDialog(
    status: NodeStatus,
    people: Map<String, Person>,
    names: Map<String, String>,
    onDismiss: () -> Unit,
    onNext: (String, String?, BigDecimal, String) -> Unit,
) {
    val context = LocalContext.current
    var to by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val named = remember(people, names) { pickablePeople(people, names, status.address) }
    var amountText by remember { mutableStateOf("") }
    val amount = parseIdna(amountText)
    var comment by remember { mutableStateOf("") }
    val commentTooLong = (commentBytes(comment)?.size ?: 0) > MAX_COMMENT_BYTES
    val balance = status.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val recipient = parseRecipient(to)
    val name = (recipient as? Recipient.Name)?.name
    // Null while the node is asked; looked up once the typing pauses.
    var resolved by remember(name) { mutableStateOf<Result<ResolvedName>?>(null) }
    LaunchedEffect(name) {
        if (name == null) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        resolved = withContext(Dispatchers.IO) { runCatching { NodeProfilesSource(rpc(context)).resolve(name) } }
    }
    val address = recipientAddress(recipient, resolved?.getOrNull())
    val toValid = address != null
    val tooMuch = amount != null && amount > balance
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.wallet_send_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.wallet_balance_line, idna(status.balance)), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it.trim() },
                    label = { Text(t(R.string.wallet_send_to)) },
                    singleLine = true,
                    isError = recipient == Recipient.Invalid || (name != null && resolved != null && !toValid),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                name?.let { RecipientLine(it, resolved) }
                if (recipient is Recipient.Address) names[recipient.address]?.let {
                    Text(t(R.string.wallet_send_to_name, it), style = MaterialTheme.typography.bodyMedium)
                }
                if (named.isNotEmpty()) {
                    TextButton(onClick = { picking = !picking }) { Text(t(if (picking) R.string.wallet_hide_contacts else R.string.wallet_choose_contacts)) }
                    if (picking) {
                        named.forEach { (address, name) ->
                            TextButton(onClick = { to = address; picking = false }) {
                                Text("$name  ·  ${shortAddress(address)}", maxLines = 1)
                            }
                        }
                    }
                }
                if (address != null && address.equals(status.address, ignoreCase = true)) {
                    Text(t(R.string.wallet_own_address), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(t(R.string.wallet_amount)) },
                    singleLine = true,
                    isError = amountText.isNotEmpty() && (amount == null || tooMuch),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (tooMuch) Text(t(R.string.wallet_too_much), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(t(R.string.wallet_comment)) },
                    placeholder = { Text(t(R.string.wallet_comment_example)) },
                    isError = commentTooLong,
                )
                Text(
                    t(if (commentTooLong) R.string.wallet_comment_too_long else R.string.wallet_comment_note, MAX_COMMENT_BYTES),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (commentTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(t(R.string.wallet_send_final), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (address != null && amount != null) onNext(address, name, amount, comment) },
                enabled = toValid && amount != null && !tooMuch && !commentTooLong,
            ) { Text(t(R.string.common_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}

/**
 * Whom "@[name]" sends to: its holder's robot, address and identity state, or why it cannot be sent to (looking it up,
 * the node did not answer, nobody holds it, or its holder is out of good standing: the name is hidden and may change
 * hands).
 */
@Composable
private fun RecipientLine(name: String, resolved: Result<ResolvedName>?) {
    val small = MaterialTheme.typography.bodySmall
    val error = MaterialTheme.colorScheme.error
    val found = resolved?.getOrNull()
    val problem = when {
        resolved == null -> t(R.string.wallet_name_looking, name)
        resolved.isFailure -> t(R.string.wallet_name_no_answer, name)
        found?.holder == null -> t(R.string.wallet_name_nobody, name)
        found.profile?.inStanding != true -> t(R.string.wallet_name_not_standing, name)
        else -> null
    }
    if (problem != null || found?.holder == null) {
        Text(problem.orEmpty(), style = small, color = if (resolved == null) MaterialTheme.colorScheme.onSurfaceVariant else error)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(found.holder, 32.dp)
        Column {
            Text(found.holder, style = small, fontFamily = FontFamily.Monospace)
            Text(identityStatus(found.profile?.state.orEmpty()), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The amount to add to the identity's stake, with what it changes: the stake after, how much of it the
 * identity keeps if it misses or fails the next validation, the discrimination threshold, and that stake
 * cannot be withdrawn.
 */
@Composable
private fun StakeDialog(status: NodeStatus, onDismiss: () -> Unit, onNext: (BigDecimal) -> Unit) {
    val context = LocalContext.current
    var amountText by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf<BigDecimal?>(null) }
    LaunchedEffect(Unit) {
        threshold = withContext(Dispatchers.IO) { runCatching { rpc(context).discriminationThreshold() }.getOrNull() }
    }
    val amount = parseIdna(amountText)
    val balance = status.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val stake = status.stake.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val locked = status.lockedStake.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val tooMuch = amount != null && amount > balance
    val preview = StakePreview.of(status.identityState, status.age, stake, locked, amount ?: BigDecimal.ZERO, threshold)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.wallet_add_stake)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.wallet_balance_line, idna(status.balance)), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(t(R.string.wallet_stake_amount)) },
                    singleLine = true,
                    isError = amountText.isNotEmpty() && (amount == null || tooMuch),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (tooMuch) Text(t(R.string.wallet_too_much), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(t(R.string.wallet_stake_change, idna(status.stake), idna(preview.newStake.toPlainString())), style = MaterialTheme.typography.bodyMedium)
                StakeProtection(status, preview)
                threshold?.let {
                    Text(
                        t(R.string.wallet_stake_threshold, idna(it.toPlainString())),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (preview.belowThreshold) {
                        Text(t(R.string.wallet_stake_below), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    t(R.string.wallet_stake_no_withdraw),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let(onNext) }, enabled = amount != null && !tooMuch) { Text(t(R.string.common_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}

/** How much of the stake after the addition the identity keeps if it misses or fails the next validation. */
@Composable
private fun StakeProtection(status: NodeStatus, preview: StakePreview) {
    val missed = preview.keptIfMissed
    val failed = preview.keptIfFailed
    fun kept(share: Double) = idna((preview.newStake * BigDecimal(share)).setScale(4, RoundingMode.DOWN).toPlainString())
    when {
        missed == null || failed == null -> {}
        missed == 0.0 && failed == 0.0 -> Text(
            t(R.string.wallet_stake_all_burnt, identityStateText(status.identityState)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> {
            Text(t(R.string.wallet_stake_kept_missed, percent(missed), kept(missed)), style = MaterialTheme.typography.bodyMedium)
            Text(t(R.string.wallet_stake_kept_failed, percent(failed), kept(failed)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
