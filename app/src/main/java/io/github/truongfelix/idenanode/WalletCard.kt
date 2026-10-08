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

/** A transfer or a stake replenishment waiting for the user's confirmation, with the node's fee. */
private data class PendingTx(val type: Int, val to: String, val amount: BigDecimal, val title: String, val summary: String, val fee: BigDecimal = BigDecimal.ZERO)

private enum class WalletDialog { Send, Receive, Stake }

/**
 * The wallet of the node's identity: Receive (the address and its QR code), Send and Add stake. The node
 * estimates each transaction's fee, the user confirms, the node signs and sends, and the card follows it
 * until it is in a block. Sending and staking wait while the node syncs or a validation runs (the node
 * refuses a stake replenishment from the flip lottery on).
 */
@Composable
fun WalletCard(status: NodeStatus, people: Map<String, Person>, network: NetworkRewards?, onChanged: () -> Unit) {
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
            val fee = withContext(Dispatchers.IO) { runCatching { rpc(context).estimateTx(tx.type, status.address, tx.to, tx.amount) } }
            busy = false
            fee.onSuccess { confirm = tx.copy(fee = it) }.onFailure { message = "The node refused: ${it.message}" }
        }
    }

    fun send(tx: PendingTx) {
        busy = true
        scope.launch {
            val hash = withContext(Dispatchers.IO) { runCatching { rpc(context).sendTx(tx.type, status.address, tx.to, tx.amount) } }
                .getOrElse {
                    busy = false
                    message = "The node refused: ${it.message}"
                    return@launch
                }
            busy = false
            pendingTx = hash
            val mined = waitUntilMined(context, hash)
            pendingTx = null
            message = if (mined) "${tx.title}: done." else "${tx.title}: not sent, $NOT_SENT"
            onChanged()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wallet", style = MaterialTheme.typography.titleMedium)
            Field("Balance", idna(status.balance))
            Field("Stake", idna(status.stake))
            // The stake's details, from the Account card before 0.1.35.
            val stake = status.stake.toDoubleOrNull() ?: 0.0
            val locked = status.lockedStake.toDoubleOrNull() ?: 0.0
            if (locked > 0) Field("Locked", idna(status.lockedStake))
            val miss = stakeProtection(status.identityState, status.age, stake, locked, Outcome.Miss)
            val fail = stakeProtection(status.identityState, status.age, stake, locked, Outcome.Fail)
            if (miss != null && fail != null) {
                Field("Protection", "${percent(miss)} if missed, ${percent(fail)} if failed")
            }
            network?.let { apy(stake, it) }?.let {
                Field("APY", "${percent(it.yearly)} (staking + mining)")
                Text(
                    "If the identity validates and mines itself; flip and invitation rewards are not counted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val padding = PaddingValues(horizontal = 14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = WalletDialog.Send }, enabled = canTransact, contentPadding = padding) { Text("Send") }
                OutlinedButton(onClick = { dialog = WalletDialog.Receive }, contentPadding = padding) { Text("Receive") }
                OutlinedButton(
                    onClick = { dialog = WalletDialog.Stake },
                    enabled = canTransact && canReceiveStake(status.identityState),
                    contentPadding = padding,
                ) { Text("Add stake") }
            }
            pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                status.period != "None" -> Text("Sending and staking wait until the validation is over.", style = MaterialTheme.typography.bodySmall)
                status.syncing -> Text("Sending and staking wait until the node is synced.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (dialog) {
        WalletDialog.Receive -> ReceiveDialog(status.address) { dialog = null }
        WalletDialog.Send -> SendDialog(status, people, onDismiss = { dialog = null }) { to, amount ->
            dialog = null
            prepare(PendingTx(SEND_TX, to, amount, "Send", "Send ${idna(amount.toPlainString())} to ${recipientText(to, people)}."))
        }
        WalletDialog.Stake -> StakeDialog(status, onDismiss = { dialog = null }) { amount ->
            dialog = null
            prepare(
                PendingTx(
                    REPLENISH_STAKE_TX, status.address, amount, "Add stake",
                    "Move ${idna(amount.toPlainString())} from your balance to your identity's stake. It cannot be withdrawn.",
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
            text = { Text("${tx.summary}\n\nFee: about $fee iDNA (at most $maxFee iDNA).") },
            confirmButton = { TextButton(onClick = { confirm = null; send(tx) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
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
        title = { Text("Receive iDNA") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Dark modules on white whatever the theme, so that any scanner reads it.
                Image(bitmap = qr, contentDescription = "QR code of the address", modifier = Modifier.size(220.dp), filterQuality = FilterQuality.None)
                SelectionContainer {
                    Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                Text("Send only iDNA on the Idena network to this address.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(address))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
 * The recipient (typed, pasted or picked among the named people of the Social tab's People list) and the
 * amount; checked here, then by the node's fee estimate.
 */
@Composable
private fun SendDialog(status: NodeStatus, people: Map<String, Person>, onDismiss: () -> Unit, onNext: (String, BigDecimal) -> Unit) {
    var to by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val named = remember(people) { sortedPeople(people).filter { it.name.isNotEmpty() && it.address != status.address.lowercase() } }
    var amountText by remember { mutableStateOf("") }
    val amount = parseIdna(amountText)
    val balance = status.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val toValid = isAddress(to)
    val tooMuch = amount != null && amount > balance
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send iDNA") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Balance: ${idna(status.balance)}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it.trim() },
                    label = { Text("To (0x…)") },
                    singleLine = true,
                    isError = to.isNotEmpty() && !toValid,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                if (toValid) people[to.lowercase()]?.name?.takeIf { it.isNotEmpty() }?.let {
                    Text("To: $it", style = MaterialTheme.typography.bodyMedium)
                }
                if (named.isNotEmpty()) {
                    TextButton(onClick = { picking = !picking }) { Text(if (picking) "Hide the contacts" else "Choose from Contacts") }
                    if (picking) {
                        named.forEach { person ->
                            TextButton(onClick = { to = person.address; picking = false }) {
                                Text("${person.name}  ·  ${shortAddress(person.address)}", maxLines = 1)
                            }
                        }
                    }
                }
                if (toValid && to.equals(status.address, ignoreCase = true)) {
                    Text("This is your own address.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (iDNA)") },
                    singleLine = true,
                    isError = amountText.isNotEmpty() && (amount == null || tooMuch),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (tooMuch) Text("More than your balance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("A transfer cannot be undone: check the address.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let { onNext(to, it) } }, enabled = toValid && amount != null && !tooMuch) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
        title = { Text("Add stake") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Balance: ${idna(status.balance)}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount to stake (iDNA)") },
                    singleLine = true,
                    isError = amountText.isNotEmpty() && (amount == null || tooMuch),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (tooMuch) Text("More than your balance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("Stake: ${idna(status.stake)} → ${idna(preview.newStake.toPlainString())}", style = MaterialTheme.typography.bodyMedium)
                StakeProtection(status, preview)
                threshold?.let {
                    Text(
                        "A validated identity needs at least ${idna(it.toPlainString())} of stake to count as an approved " +
                            "validator (block committees) and to send its evidence map; below it the node calls it discriminated.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (preview.belowThreshold) {
                        Text("Your stake would still be below it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "Stake cannot be withdrawn: it only returns to the balance (without locked stake) if the identity is terminated.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let(onNext) }, enabled = amount != null && !tooMuch) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            "As a ${status.identityState}, if the identity misses or fails the next validation it is killed and all " +
                "its stake is burnt, including what you add now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> {
            Text("If the identity misses the next validation, it keeps ${percent(missed)} (${kept(missed)}).", style = MaterialTheme.typography.bodyMedium)
            Text("If it fails it, it keeps ${percent(failed)} (${kept(failed)}).", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
