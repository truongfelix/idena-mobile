package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/** "Show my ad…" (the audience and the burn, [burnOnly] false) and "Burn more…" (the burn for the profile's audience). */
@Composable
fun ShowAdDialog(ad: MyAd, me: String, onClose: () -> Unit, onDone: (String?) -> Unit) = ShowDialog(ad, me, burnOnly = false, onClose, onDone)

@Composable
fun BurnMoreDialog(ad: MyAd, me: String, onClose: () -> Unit, onDone: (String?) -> Unit) = ShowDialog(ad, me, burnOnly = true, onClose, onDone)

@Composable
private fun ShowDialog(ad: MyAd, me: String, burnOnly: Boolean, onClose: () -> Unit, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shownTo = remember(ad) { ad.entry?.target?.takeIf { it.isNotEmpty() }?.let { runCatching { decodeTarget(hexToBytes(it)) }.getOrNull() } ?: AdTarget() }
    var target by remember { mutableStateOf(shownTo) }
    var ageText by remember { mutableStateOf(shownTo.age.takeIf { it > 0 }?.toString().orEmpty()) }
    var stakeText by remember { mutableStateOf(shownTo.stake.takeIf { it > 0 }?.toString().orEmpty()) }
    var amountText by remember { mutableStateOf("") }
    var competitionLine by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<ShowPlan?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target) {
        // Read once the audience stays the same for a moment.
        delay(500)
        competitionLine = withContext(Dispatchers.IO) {
            runCatching { competitionText(myAdsNode(context).competition(ad.cid, target)) }.getOrElse { "Other ads: not read (${it.message})." }
        }
    }
    val ready = plan
    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (burnOnly) "Burn more" else "Show my ad") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready != null) {
                    Text(showConfirmText(ad.title, target, ready.amount, ready.profile != null, ready.fees))
                } else {
                    if (burnOnly) {
                        Text("“${ad.title}” is shown to ${audiencePhrase(target)}.")
                    } else {
                        Text("Who sees it", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("Language", target.language, listOf("" to "Any") + AD_LANGUAGES.map { it to languageName(it) }) { target = target.copy(language = it) }
                            Choice("OS", target.os, listOf("" to "Any") + AD_OSES) { target = target.copy(os = it) }
                        }
                        NumberField("Min age, epochs", ageText) { ageText = it; target = target.copy(age = it.toIntOrNull() ?: 0) }
                        NumberField("Min stake, iDNA", stakeText) { stakeText = it; target = target.copy(stake = it.toIntOrNull() ?: 0) }
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.trim(); error = null },
                        label = { Text("Burn, iDNA") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(competitionLine ?: "Reading the other ads…", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Shown 24 h to the identities it targets, ranked by the iDNA burnt (a set language counts ×22, an OS ×5).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                busy?.let { Text(it); CircularProgressIndicator() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                busy != null -> {}
                ready == null -> TextButton(onClick = {
                    val amount = amountText.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                    val numbers = (ageText.isEmpty() || ageText.toIntOrNull() != null) && (stakeText.isEmpty() || stakeText.toIntOrNull() != null)
                    if (amount == null || !numbers) {
                        error = if (amount == null) "Write the iDNA to burn." else "The age and the stake are whole numbers."
                        return@TextButton
                    }
                    busy = "Checking with the node…"
                    scope.launch {
                        val checked = withContext(Dispatchers.IO) { runCatching { planShow(context, me, ad, burnOnly, target, amount) } }
                        busy = null
                        checked.onSuccess { plan = it }.onFailure { error = "It cannot be sent: ${it.message}" }
                    }
                }) { Text(if (burnOnly) "Check" else "Show it…") }
                else -> TextButton(onClick = {
                    busy = if (ready.profile != null) "Updating your profile and burning…" else "Burning…"
                    scope.launch {
                        val sent = withContext(Dispatchers.IO) {
                            runCatching {
                                val rpc = rpc(context)
                                waitForBlock(NodeTxLookup(rpc), myAdsNode(context).sendShow(me, ready))
                            }
                        }
                        busy = null
                        onDone(
                            sent.fold(
                                onSuccess = { block ->
                                    when {
                                        block == null -> "Not burnt: $NOT_SENT"
                                        else -> "“${ad.title}” is shown for 24 h: ${idna(ready.amount.toPlainString())} burnt."
                                    }
                                },
                                onFailure = { "Not sent: ${it.message}" },
                            ),
                        )
                    }
                }) { Text(if (burnOnly) "Burn" else "Show it") }
            }
        },
        dismissButton = { if (busy == null) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** The plan of a burn for the profile's entry ([burnOnly]), else of showing the ad to [target]. */
private fun planShow(context: Context, me: String, ad: MyAd, burnOnly: Boolean, target: AdTarget, amount: BigDecimal): ShowPlan {
    val node = myAdsNode(context)
    val entry = ad.entry
    return if (burnOnly && entry != null) node.planBurn(me, entry, amount) else node.planShow(me, ad.cid, ad.contract, target, amount)
}

/** A choice among [options] (code to name), as an outlined button opening a menu. */
@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { open = true }) { Text((options.firstOrNull { it.first == value }?.second ?: value) + " ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(code) }) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(9)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
