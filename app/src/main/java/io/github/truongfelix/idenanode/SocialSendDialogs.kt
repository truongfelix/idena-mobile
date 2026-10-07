package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import java.math.BigDecimal

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * What a paid action does and costs: the files stored on IPFS first, each with its fee (none for a file
 * stored by an earlier try), then the call.
 */
fun confirmText(pending: PendingPost): String = buildString {
    pending.detail?.let { append(it).append(". ") }
    append("It is public and stays on the blockchain for good, signed by your identity ${shortAddress(pending.from)}.")
    for (file in pending.files) {
        if (file.stored) {
            append("\n\nThe ${file.what} is already stored on IPFS: no new fee.")
            continue
        }
        append("\n\nStoring the ${file.what} on IPFS (${kilobytes(file.bytes.size)}): about ${fee(file.fee)} iDNA (at most ${fee(file.fee * BigDecimal(2))}).")
    }
    append("\n\n${if (pending.files.isEmpty()) "Fee" else "The post"}: about ${fee(pending.fee.fee)} iDNA (at most ${fee(pending.fee.maxFee)}).")
    if (pending.files.isNotEmpty()) append("\n\nTotal: about ${fee(pending.totalFee)} iDNA.")
}

fun fee(amount: BigDecimal): String = amount.setScale(2, RoundingMode.UP).toPlainString()

fun kilobytes(bytes: Int): String = "${(bytes + 999) / 1000} KB"

/** The amount of a tip: whole iDNA (the contract refuses fractions), with the balance for reference. */
@Composable
fun TipDialog(post: SocialPost, onDismiss: () -> Unit, onTip: (Long) -> Unit) {
    val context = LocalContext.current
    var amount by remember { mutableStateOf("1") }
    var balance by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        balance = withContext(Dispatchers.IO) {
            runCatching {
                val rpc = rpc(context)
                SocialPosting(rpc).balance(rpc.call("dna_getCoinbaseAddr") as String).setScale(2, RoundingMode.DOWN).toPlainString()
            }.getOrNull()
        }
    }
    val value = amount.toLongOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tip ${displayName(post.call.author, LocalNames.current)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The tip goes to the author of this post, through the idena.social contract. Whole iDNA only.")
                balance?.let { Text("Your balance: $it iDNA", style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { text -> amount = text.filter { it.isDigit() }.take(9) },
                    label = { Text("iDNA") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onTip) }, enabled = value != null) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
