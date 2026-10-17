package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
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
    append(t(R.string.social_confirm_public, shortAddress(pending.from)))
    for (file in pending.files) {
        if (file.stored) {
            append("\n\n").append(t(R.string.social_confirm_file_stored, file.shownName))
            continue
        }
        append("\n\n").append(t(R.string.social_confirm_file_fee, file.shownName, kilobytes(file.bytes.size), fee(file.fee), fee(file.fee * BigDecimal(2))))
    }
    val callName = if (pending.call.contract == PROFILE_CONTRACT) t(R.string.social_confirm_the_call) else t(R.string.social_confirm_the_post)
    append("\n\n").append(t(R.string.social_confirm_fee, if (pending.files.isEmpty()) t(R.string.social_confirm_fee_label) else callName, fee(pending.fee.fee), fee(pending.fee.maxFee)))
    pending.memberExtra?.let {
        append("\n\n").append(
            if (it.lapsed) t(R.string.social_confirm_member_lapsed, "${it.amount}")
            else t(R.string.social_confirm_member, "${it.amount}"),
        )
    }
    if (pending.files.isNotEmpty()) append("\n\n").append(t(R.string.social_confirm_total, fee(pending.totalFee)))
}

/** Where a paid action goes, for its confirmation's title. */
fun placeOf(pending: PendingPost) = if (pending.call.contract == PROFILE_CONTRACT) t(R.string.social_place_profile) else t(R.string.social_place_social)

fun fee(amount: BigDecimal): String = amount.setScale(2, RoundingMode.UP).toPlainString()

fun kilobytes(bytes: Int): String = t(R.string.social_kilobytes, (bytes + 999) / 1000)

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
        title = { Text(t(R.string.social_tip_title, displayName(post.call.author, LocalNames.current))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.social_tip_text))
                balance?.let { Text(t(R.string.social_tip_balance, it), style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { text -> amount = text.filter { it.isDigit() }.take(9) },
                    label = { Text("iDNA") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onTip) }, enabled = value != null) { Text(t(R.string.common_next)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}

/** "2 actions waiting for a block" while any paid action of Social waits ([SocialSending.inFlight]); a tap lists them. */
@Composable
fun InFlightLine(inFlight: List<InFlight>) {
    if (inFlight.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text(
        tp(R.plurals.social_in_flight, inFlight.size, inFlight.size),
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(t(R.string.social_in_flight_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    inFlight.forEach { Text(t(R.string.social_in_flight_item, it.what, it.hash.take(10)), style = MaterialTheme.typography.bodyMedium) }
                    Text(t(R.string.social_in_flight_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(t(R.string.common_close)) } },
        )
    }
}
