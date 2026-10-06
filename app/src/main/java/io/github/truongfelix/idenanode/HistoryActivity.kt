package io.github.truongfelix.idenanode

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The History screen, opened from the Account card: the ceremonies (with each recorded validation report), the
 * transactions and the mining rewards of the account, all from the app's own node.
 */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    HistoryScreen(onClose = { finish() }, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

private enum class HistoryTab(val title: String) { Ceremonies("Ceremonies"), Transactions("Transactions"), Mining("Mining") }

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

private fun readHistory(context: Context): IdentityHistory =
    parseIdentityHistory(rpc(context).call("dna_identityHistory", readTimeoutMs = 30_000) as JSONObject)

private fun readTransactions(context: Context, address: String, token: String?): Pair<List<HistoryTx>, String?> {
    val args = JSONObject().put("address", address).put("count", 30)
    if (token != null) args.put("token", token)
    return parseTransactions(rpc(context).call("bcn_transactions", JSONArray().put(args), readTimeoutMs = 30_000) as? JSONObject)
}

private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** An amount with at most 4 decimals, without the unit (the label names it). */
private fun amount(value: BigDecimal): String = value.setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

private fun points(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
fun HistoryScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(HistoryTab.Ceremonies) }
    var state by remember { mutableStateOf<Result<IdentityHistory>?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reads) { state = withContext(Dispatchers.IO) { runCatching { readHistory(context) } } }
    BackHandler(onBack = onClose)
    // Read again while the node is still finding its ceremonies.
    val history = state?.getOrNull()
    LaunchedEffect(history) {
        if (history != null && !history.ceremoniesComplete) {
            delay(5_000)
            reads++
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Close") }
            Spacer(Modifier.weight(1f))
            Text("History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryTab.entries.forEach {
                FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(it.title) })
            }
        }
        val current = state
        when {
            current == null -> CircularProgressIndicator()
            current.isFailure -> {
                Text("The node did not answer: ${current.exceptionOrNull()?.message}")
                Button(onClick = { reads++ }) { Text("Try again") }
            }
            else -> when (tab) {
                HistoryTab.Ceremonies -> CeremoniesList(history!!, Modifier.weight(1f))
                HistoryTab.Transactions -> TransactionsList(history!!.address, Modifier.weight(1f))
                HistoryTab.Mining -> MiningList(history!!, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CeremoniesList(history: IdentityHistory, modifier: Modifier) {
    var open by remember { mutableStateOf<Int?>(null) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (!history.ceremoniesComplete) {
            item { Note("Your node is still finding its past ceremonies in its stored blocks; the list fills in by itself.") }
        }
        if (history.ceremonies.isEmpty()) {
            item { Text("No validation of this account in the ceremonies your node knows.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(history.ceremonies, key = { it.epoch }) { row ->
            CeremonyCard(row, expanded = open == row.epoch, onClick = { open = if (open == row.epoch) null else row.epoch })
        }
        item {
            Note(
                "Your node keeps the result of every ceremony since its first block, and the score, flips and rewards " +
                    "of the ceremonies it applied itself (from this version on). Tap a ceremony with a report to see it.",
            )
        }
    }
}

@Composable
private fun outcomeText(row: CeremonyRow): Pair<String, Color> {
    val palette = statusPalette()
    return when (row.outcome) {
        CeremonyOutcome.Validated -> (row.state?.let { identityStatus(it) } ?: "Validated") to palette(row.state ?: "Verified")
        CeremonyOutcome.NotValidated -> (row.state?.let { identityStatus(it) } ?: "Not validated") to MaterialTheme.colorScheme.error
        CeremonyOutcome.NoIdentity -> "No identity" to MaterialTheme.colorScheme.onSurfaceVariant
        CeremonyOutcome.Failed -> "Ceremony failed" to MaterialTheme.colorScheme.error
        CeremonyOutcome.Unknown -> "Result unknown" to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun CeremonyCard(row: CeremonyRow, expanded: Boolean, onClick: () -> Unit) {
    val report = row.report
    Card(Modifier.fillMaxWidth().then(if (report != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Epoch ${row.epoch}", fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(row.time?.let { dayFormat.format(Instant.ofEpochSecond(it)) } ?: "", style = MaterialTheme.typography.bodySmall)
            }
            val (text, color) = outcomeText(row)
            val change = if (row.prevState != null && row.state != null && row.prevState != row.state) {
                "${identityStatus(row.prevState)} → "
            } else {
                ""
            }
            Text(change + text, color = color, style = MaterialTheme.typography.bodyMedium)
            val details = buildList {
                row.score?.let { add("Score ${points(it.first)} of ${it.second}") }
                if (row.flips > 0) add("Flips ${row.flips}")
                when {
                    row.shortAnswersSent && row.longAnswersSent -> add("Short and long answers sent")
                    row.shortAnswersSent -> add("Short answers sent")
                    row.longAnswersSent -> add("Long answers sent")
                }
            }
            if (details.isNotEmpty()) Note(details.joinToString(" · "))
            if (report != null) {
                val missed = report.missedRewards
                Text(
                    "Rewards ${idna(report.earned.toPlainString())}" +
                        if (missed.signum() > 0) ", ${amount(missed)} missed" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expanded) ReportDetails(report) else Note("Tap for the report")
            }
        }
    }
}

@Composable
private fun ReportDetails(report: ValidationReport) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!report.participated) {
            Note("Not a candidate of this ceremony.")
            return@Column
        }
        Field("Short", "${points(report.shortPoints)} of ${report.shortFlips} (${report.shortAnswers} answers)")
        Field("Long", "${points(report.longPoints)} of ${report.longFlips} (${report.longAnswers} answers)")
        Field("Flips made", report.madeFlips.toString())
        if (report.missed) Text("Validation missed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (!report.approved && !report.missed) {
            Text("Short answers not seen in time by most participants", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (report.penalized) {
            Text(
                "Penalized" + if (report.penaltyReason.isNotEmpty()) ": ${report.penaltyReason}" else "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (report.wrongGrades) Note("Grades not counted (reports ignored)")
        Text("Rewards, iDNA", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        report.rewards.filter { it.earned.signum() != 0 || (it.missed?.signum() ?: 0) != 0 }.forEach { line ->
            val missed = line.missed
            val lost = if (missed != null && missed.signum() > 0) {
                "  (${amount(missed)} missed" + if (line.reason.isNotEmpty()) ", ${missedReason(line.reason)})" else ")"
            } else {
                ""
            }
            Field(line.kind, amount(line.earned) + lost)
        }
        report.delegatee?.let { Note("${idna(report.delegated.toPlainString())} paid to the pool ${shortAddress(it)}") }
    }
}

@Composable
private fun TransactionsList(address: String, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var txs by remember { mutableStateOf<List<HistoryTx>>(emptyList()) }
    var token by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(next: String?) {
        loading = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readTransactions(context, address, next) } }
                .onSuccess { (page, nextToken) ->
                    txs = txs + page
                    token = nextToken
                    error = null
                }
                .onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(address) { load(null) }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        error?.let { item { Text("The node did not answer: $it", color = MaterialTheme.colorScheme.error) } }
        if (!loading && txs.isEmpty() && error == null) item { Text("No transactions", style = MaterialTheme.typography.bodyMedium) }
        items(txs, key = { it.hash }) { tx -> TxCard(tx, address) }
        when {
            loading -> item { CircularProgressIndicator() }
            token != null -> item { OutlinedButton(onClick = { load(token) }) { Text("Show more") } }
        }
    }
}

@Composable
private fun TxCard(tx: HistoryTx, address: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(txTypeName(tx.type, tx.payload), fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                val signed = tx.signedAmount(address)
                if (signed.signum() != 0) {
                    Text(
                        (if (signed.signum() > 0) "+" else "") + idna(signed.toPlainString()),
                        color = if (signed.signum() < 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
            Note("${timeFormat.format(Instant.ofEpochSecond(tx.timestamp))} · epoch ${tx.epoch}")
            tx.counterParty(address)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tx.fee.signum() > 0) Note("Fee ${idna(tx.fee.toPlainString())}")
        }
    }
}

@Composable
private fun MiningList(history: IdentityHistory, modifier: Modifier) {
    val rows = history.mining
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (rows.isEmpty()) {
            item {
                Text(
                    "No mining reward recorded yet. Your node records them from this version on, for the blocks it adds while mining is on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }
        item {
            val total = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.total }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Field("Total", idna(total.toPlainString()))
                    Field("Proposed", "${rows.sumOf { it.proposedBlocks }} blocks")
                    Field("Committee", "${rows.sumOf { it.committeeBlocks }} blocks")
                }
            }
        }
        items(rows, key = { it.epoch }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text("Epoch ${row.epoch}", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(idna(row.total.toPlainString()))
                    }
                    Note("Proposed ${row.proposedBlocks} blocks: ${amount(row.proposerReward)} iDNA")
                    Note("Committee ${row.committeeBlocks} blocks: ${amount(row.committeeReward)} iDNA")
                    if (row.penaltyBurnt.signum() > 0) {
                        Text("Burnt by penalty ${amount(row.penaltyBurnt)} iDNA", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (history.epoch > 0) item { Note("The current epoch (${history.epoch}) is still counting.") }
    }
}
