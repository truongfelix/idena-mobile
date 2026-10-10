package io.github.truongfelix.idenanode

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The New voting screen: the kind as pills (the desktop's presets), the question, its options, the start and the
 * secret voting's length, the rest under Advanced settings ([NewVotingAdvanced]). Publish checks the form, has the
 * node estimate the deploy, confirms with the stake and the fee, publishes, and launches the voting at once when it
 * starts now ([launchesAtOnce]); then [onPublished] opens it.
 */
@Composable
fun NewVotingScreen(me: String, onClose: () -> Unit, onPublished: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(NewVotingForm().withKind(VotingKind.Fact, Instant.now())) }
    var badNumbers by remember { mutableStateOf(setOf<String>()) }
    var problems by remember { mutableStateOf(emptyList<String>()) }
    var plan by remember { mutableStateOf<PublishPlan?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var published by remember { mutableStateOf<String?>(null) }
    var network by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        network = withContext(Dispatchers.IO) {
            runCatching { (rpc(context).call("dna_globalState") as JSONObject).optLong("networkSize") }.getOrNull()
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle("New voting", onClose)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VotingKind.entries.forEach { kind ->
                FilterChip(selected = form.kind == kind, onClick = { if (kind != form.kind) form = form.withKind(kind, Instant.now()) }, label = { Text(kind.title) })
            }
        }
        Text(form.kind.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(form.title, { form = form.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
        OutlinedTextField(form.desc, { form = form.copy(desc = it) }, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 3)
        Text("Options", style = MaterialTheme.typography.titleSmall)
        form.options.forEachIndexed { i, option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    option,
                    { text -> form = form.copy(options = form.options.toMutableList().also { it[i] = text }) },
                    Modifier.weight(1f),
                    label = { Text("Option ${i + 1}") },
                    singleLine = true,
                )
                if (form.options.size > 2) {
                    TextButton(onClick = { form = form.copy(options = form.options.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                }
            }
        }
        TextButton(onClick = { form = form.copy(options = form.options + "") }) { Text("+ Add option") }
        StartChoice(form) { form = it }
        Text("Secret voting", style = MaterialTheme.typography.titleSmall)
        DurationPills(form.votingDuration) { form = form.copy(votingDuration = it) }
        NewVotingAdvanced(form, network, onChange = { form = it }, onBadNumbers = { badNumbers = it })

        problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        busy?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val done = published
        if (done != null) {
            Button(onClick = { onPublished(done) }, Modifier.fillMaxWidth()) { Text("Open the voting") }
        } else {
            Button(
                onClick = {
                    problems = form.problems() + if (badNumbers.isEmpty()) emptyList() else listOf("Fix the numbers in red.")
                    message = null
                    if (problems.isNotEmpty()) return@Button
                    busy = "Checking the voting with the node…"
                    scope.launch {
                        val made = withContext(Dispatchers.IO) { runCatching { planPublish(context, me, form) } }
                        busy = null
                        made.onSuccess { plan = it }.onFailure { message = "The voting cannot be published: ${it.message}" }
                    }
                },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Publish…") }
        }
        Spacer(Modifier.height(16.dp))
    }

    plan?.let { ready ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Publish the voting") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(publishConfirmText(form, ready) { dateText(it) }) } },
            confirmButton = {
                TextButton(onClick = {
                    plan = null
                    scope.launch {
                        val outcome = publishAndLaunch(context, me, form, ready) { busy = it }
                        busy = null
                        published = outcome.contract
                        message = outcome.message
                        if (outcome.contract != null && outcome.message == null) onPublished(outcome.contract)
                    }
                }) { Text("Publish") }
            },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("Cancel") } },
        )
    }
}

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** "8 Oct 2026, 18:30 (in 2 days)", as the voting's page. */
private fun dateText(time: Instant): String = "${dateFormat.format(time)} (${timeUntil(Instant.now(), time)})"

/** Plans the publish once the node is synced: a node behind the chain estimates against an old state. */
private fun planPublish(context: Context, me: String, form: NewVotingForm): PublishPlan {
    val rpc = rpc(context)
    if (!rpc.synced()) throw IOException("the node is still syncing; publish once it is synced")
    return VotingOwnerNode(rpc).planPublish(me, form, Instant.now())
}

/** How a publish ended: the voting, if the deploy made one, and what went wrong (null when all went through). */
private data class PublishOutcome(val contract: String?, val message: String?)

/**
 * Publishes [plan] and waits for its block; then, when the voting starts now, launches it and waits again. [status]
 * says each step. A launch that fails leaves the voting published, not started: its page offers Launch.
 */
private suspend fun publishAndLaunch(context: Context, me: String, form: NewVotingForm, plan: PublishPlan, status: (String) -> Unit): PublishOutcome =
    withContext(Dispatchers.IO) {
        val rpc = rpc(context)
        val node = VotingOwnerNode(rpc)
        status("Publishing…")
        val hash = runCatching { node.publish(me, plan) }.getOrElse { return@withContext PublishOutcome(null, "Not published: ${it.message}") }
        val block = waitForBlock(NodeTxLookup(rpc), hash) ?: return@withContext PublishOutcome(null, "Not published: $NOT_SENT")
        block.error?.let { return@withContext PublishOutcome(null, "The contract refused it: $it.") }
        val contract = runCatching { node.deployed(hash) }.getOrNull() ?: plan.contract
        if (!launchesAtOnce(form)) return@withContext PublishOutcome(contract, null)
        status("Launching…")
        val launched = runCatching {
            val voting = votingNode(context).voting(contract, me)
            // The form's rewards fund too: the contract keeps it only with an owner fee.
            val amount = maxOf(plan.launchAmount - voting.balance, launchAmount(voting))
            val cost = node.plan(VotingAction.Launch, me, voting, amount)
            waitForBlock(NodeTxLookup(rpc), node.send(VotingAction.Launch, me, voting, amount, cost))
        }
        val error = launched.fold(
            onSuccess = { block -> if (block == null) NOT_SENT else block.error?.let { "the contract refused it: $it" } },
            onFailure = { it.message ?: "the node refused it" },
        )
        PublishOutcome(contract, error?.let { "Published, not launched (${it.trimEnd('.')}). Launch it from its page." })
    }

/** Start now, or at a picked date and time. */
@Composable
private fun StartChoice(form: NewVotingForm, onChange: (NewVotingForm) -> Unit) {
    val context = LocalContext.current
    Text("Start", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = form.startNow, onClick = { onChange(form.copy(startNow = true)) })
        Text("Now")
        RadioButton(selected = !form.startNow, onClick = { onChange(form.copy(startNow = false)) })
        TextButton(onClick = { pickDateTime(context, form.startTime ?: Instant.now()) { onChange(form.copy(startNow = false, startTime = it)) } }) {
            Text(form.startTime?.takeIf { !form.startNow }?.let { dateFormat.format(it) } ?: "Pick a date")
        }
    }
}

/** The durations as pills, the chosen one selected. */
@Composable
internal fun DurationPills(blocks: Long, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VOTING_DURATIONS.forEach { FilterChip(selected = blocks == it, onClick = { onPick(it) }, label = { Text(durationText(it)) }) }
    }
}

/** Android's date then time dialogs, from [initial]; [onPicked] gets the local date and time chosen. */
private fun pickDateTime(context: Context, initial: Instant, onPicked: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = initial.atZone(zone)
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            onPicked(ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, zone).toInstant())
        }, start.hour, start.minute, true).show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}
