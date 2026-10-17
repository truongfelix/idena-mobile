package io.github.truongfelix.idenanode

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
 * The History screen, opened from the Profile card: the ceremonies (with each recorded validation report), the
 * transactions and the mining rewards of the account, all from the app's own node.
 */
class HistoryActivity : AppActivity() {
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

private enum class HistoryTab(@StringRes val title: Int) {
    Ceremonies(R.string.wallet_history_tab_ceremonies),
    Transactions(R.string.wallet_history_tab_transactions),
    Mining(R.string.wallet_history_tab_mining),
}

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
            TextButton(onClick = onClose) { Text(t(R.string.common_close)) }
            Spacer(Modifier.weight(1f))
            Text(t(R.string.wallet_history_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryTab.entries.forEach {
                FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(t(it.title)) })
            }
        }
        val current = state
        when {
            current == null -> CircularProgressIndicator()
            current.isFailure -> {
                Text(t(R.string.wallet_node_no_answer, current.exceptionOrNull()?.message.toString()))
                Button(onClick = { reads++ }) { Text(t(R.string.common_try_again)) }
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
            item { Note(t(R.string.wallet_history_finding)) }
        }
        if (history.ceremonies.isEmpty()) {
            item { Text(t(R.string.wallet_history_no_validation), style = MaterialTheme.typography.bodyMedium) }
        }
        items(history.ceremonies, key = { it.epoch }) { row ->
            CeremonyCard(row, expanded = open == row.epoch, onClick = { open = if (open == row.epoch) null else row.epoch })
        }
        item {
            Note(
                t(R.string.wallet_history_ceremonies_note),
            )
        }
    }
}

@Composable
private fun outcomeText(row: CeremonyRow): Pair<String, Color> {
    val palette = statusPalette()
    return when (row.outcome) {
        CeremonyOutcome.Validated -> (row.state?.let { identityStatus(it) } ?: t(R.string.wallet_history_validated)) to palette(row.state ?: "Verified")
        CeremonyOutcome.NotValidated -> (row.state?.let { identityStatus(it) } ?: t(R.string.wallet_history_not_validated)) to MaterialTheme.colorScheme.error
        CeremonyOutcome.NoIdentity -> t(R.string.wallet_history_no_identity) to MaterialTheme.colorScheme.onSurfaceVariant
        CeremonyOutcome.Failed -> t(R.string.wallet_history_failed) to MaterialTheme.colorScheme.error
        CeremonyOutcome.Unknown -> t(R.string.wallet_history_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun CeremonyCard(row: CeremonyRow, expanded: Boolean, onClick: () -> Unit) {
    val report = row.report
    Card(Modifier.fillMaxWidth().then(if (report != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(R.string.wallet_history_epoch, row.epoch), fontWeight = FontWeight.Medium)
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
                row.score?.let { add(t(R.string.wallet_history_score, points(it.first), it.second)) }
                if (row.flips > 0) add(t(R.string.wallet_history_flips, row.flips))
                when {
                    row.shortAnswersSent && row.longAnswersSent -> add(t(R.string.wallet_history_both_answers))
                    row.shortAnswersSent -> add(t(R.string.wallet_history_short_answers))
                    row.longAnswersSent -> add(t(R.string.wallet_history_long_answers))
                }
            }
            if (details.isNotEmpty()) Note(details.joinToString(" · "))
            if (report != null) {
                val missed = report.missedRewards
                Text(
                    if (missed.signum() > 0) {
                        t(R.string.wallet_history_rewards_missed, idna(report.earned.toPlainString()), amount(missed))
                    } else {
                        t(R.string.wallet_history_rewards, idna(report.earned.toPlainString()))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expanded) ReportDetails(report) else Note(t(R.string.wallet_history_tap_report))
            }
        }
    }
}

@Composable
private fun ReportDetails(report: ValidationReport) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!report.participated) {
            Note(t(R.string.wallet_history_not_candidate))
            return@Column
        }
        Field(t(R.string.wallet_history_short), t(R.string.wallet_history_session_points, points(report.shortPoints), report.shortFlips, report.shortAnswers))
        Field(t(R.string.wallet_history_long), t(R.string.wallet_history_session_points, points(report.longPoints), report.longFlips, report.longAnswers))
        Field(t(R.string.wallet_history_flips_made), report.madeFlips.toString())
        if (report.missed) Text(t(R.string.wallet_history_validation_missed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (!report.approved && !report.missed) {
            Text(t(R.string.wallet_history_not_approved), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (report.penalized) {
            Text(
                if (report.penaltyReason.isNotEmpty()) t(R.string.wallet_history_penalized_reason, report.penaltyReason) else t(R.string.wallet_history_penalized),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (report.wrongGrades) Note(t(R.string.wallet_history_wrong_grades))
        Text(t(R.string.wallet_history_rewards_title), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        report.rewards.filter { it.earned.signum() != 0 || (it.missed?.signum() ?: 0) != 0 }.forEach { line ->
            val missed = line.missed
            val lost = if (missed != null && missed.signum() > 0) {
                if (line.reason.isNotEmpty()) t(R.string.wallet_history_line_missed_reason, amount(missed), missedReason(line.reason)) else t(R.string.wallet_history_line_missed, amount(missed))
            } else {
                ""
            }
            Field(line.kind, amount(line.earned) + lost)
        }
        report.delegatee?.let { Note(t(R.string.wallet_history_pool_paid, idna(report.delegated.toPlainString()), shortAddress(it))) }
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
    // The other sides' names, as the desktop's History (given in Social, else claimed): read again as pages come.
    val names = rememberShownNames(txs.mapNotNull { it.counterParty(address) })

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        error?.let { item { Text(t(R.string.wallet_node_no_answer, it), color = MaterialTheme.colorScheme.error) } }
        if (!loading && txs.isEmpty() && error == null) item { Text(t(R.string.wallet_history_no_transactions), style = MaterialTheme.typography.bodyMedium) }
        items(txs, key = { it.hash }) { tx -> TxCard(tx, address, names) }
        when {
            loading -> item { CircularProgressIndicator() }
            token != null -> item { OutlinedButton(onClick = { load(token) }) { Text(t(R.string.wallet_history_show_more)) } }
        }
    }
}

@Composable
private fun TxCard(tx: HistoryTx, address: String, names: Map<String, String>) {
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
            Note(t(R.string.wallet_history_tx_time, timeFormat.format(Instant.ofEpochSecond(tx.timestamp)), tx.epoch))
            tx.counterParty(address)?.let {
                names[it.lowercase()]?.let { name -> Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tx.fee.signum() > 0) Note(t(R.string.wallet_history_fee, idna(tx.fee.toPlainString())))
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
                    t(R.string.wallet_history_no_mining),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }
        item {
            val total = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.total }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Field(t(R.string.wallet_history_total), idna(total.toPlainString()))
                    Field(t(R.string.wallet_history_proposed), t(R.string.wallet_history_blocks, rows.sumOf { it.proposedBlocks }))
                    Field(t(R.string.wallet_history_committee), t(R.string.wallet_history_blocks, rows.sumOf { it.committeeBlocks }))
                }
            }
        }
        items(rows, key = { it.epoch }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(t(R.string.wallet_history_epoch, row.epoch), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(idna(row.total.toPlainString()))
                    }
                    Note(t(R.string.wallet_history_proposed_line, row.proposedBlocks, amount(row.proposerReward)))
                    Note(t(R.string.wallet_history_committee_line, row.committeeBlocks, amount(row.committeeReward)))
                    if (row.penaltyBurnt.signum() > 0) {
                        Text(t(R.string.wallet_history_burnt, amount(row.penaltyBurnt)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (history.epoch > 0) item { Note(t(R.string.wallet_history_current_epoch, history.epoch)) }
    }
}
