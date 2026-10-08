package io.github.truongfelix.idenanode

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant

/*
 * The flips of the epoch ([MyFlips.kt]): the list the flip screen opens on, a flip shown as in the
 * validation, and deleting a flip.
 */

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** What the node and this phone say of the identity's flips. */
private class MyFlipsState(
    val address: String,
    val quota: FlipQuota,
    val published: List<String>,
    val made: List<MadeFlip>,
    val epoch: Int,
    val period: String,
)

private fun readMyFlips(context: Context): MyFlipsState {
    val node = rpc(context)
    val identity = node.identity()
    val ceremony = node.ceremony()
    val quota = FlipQuota(identity.optString("state"), identity.optInt("requiredFlips"), identity.optInt("availableFlips"), identity.optInt("madeFlips"))
    return MyFlipsState(
        identity.optString("address"),
        quota,
        identityFlips(identity),
        MadeFlipStore.list(context, ceremony.epoch),
        ceremony.epoch,
        ceremony.period,
    )
}

/** A delete the validator confirms, with its fee and the flips made after it. */
private class PendingDelete(val row: MyFlipRow, val fee: BigDecimal)

/**
 * The list of the epoch's flips: "Make a flip" while more can be made, then each flip with its four
 * pictures (story order), its words, its state and Delete. A tap shows the flip as in the validation.
 */
@Composable
fun MyFlipsScreen(onMake: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keywords = remember { loadKeywords(context) }
    var state by remember { mutableStateOf<Result<MyFlipsState>?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf(setOf<String>()) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    var shown by remember { mutableStateOf<Flip?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reads) { state = withContext(Dispatchers.IO) { runCatching { readMyFlips(context) } } }
    BackHandler(onBack = onClose)

    val read = state?.getOrNull()
    val rows = read?.let { myFlipRows(it.published, it.made, deleting, System.currentTimeMillis()) }.orEmpty()
    // Read again while a flip waits for its block or its delete.
    LaunchedEffect(rows) {
        if (rows.any { it.state != MyFlipState.Published }) {
            delay(10_000)
            reads++
        }
    }

    fun askDelete(row: MyFlipRow) {
        val current = read ?: return
        message = null
        busy = "Asking the node for the fee…"
        scope.launch {
            val fee = withContext(Dispatchers.IO) { runCatching { rpc(context).estimateDeleteFlip(current.address, row.cid) } }
            busy = null
            fee.onSuccess { pending = PendingDelete(row, it) }.onFailure { message = "The node refuses to delete it: ${it.message}" }
        }
    }

    fun delete(row: MyFlipRow) {
        pending = null
        busy = "Deleting the flip…"
        scope.launch {
            val hash = withContext(Dispatchers.IO) { runCatching { rpc(context).deleteFlip(row.cid) } }.getOrElse {
                busy = null
                message = "The node refused: ${it.message}"
                return@launch
            }
            busy = null
            deleting = deleting + row.cid
            val mined = waitUntilMined(context, hash)
            if (mined) withContext(Dispatchers.IO) { MadeFlipStore.remove(context, row.cid) }
            deleting = deleting - row.cid
            message = if (mined) "Flip deleted: its words can be used for a new flip." else "The flip is not deleted: $NOT_SENT"
            reads++
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Close") }
            Spacer(Modifier.weight(1f))
            Text("My flips", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        val current = state
        when {
            current == null -> { CircularProgressIndicator(); return@Column }
            current.isFailure -> {
                Text("The node did not answer: ${current.exceptionOrNull()?.message}")
                Button(onClick = { reads++ }) { Text("Try again") }
                return@Column
            }
        }
        val node = read!!
        val waiting = rows.count { it.state == MyFlipState.Waiting }
        val q = node.quota
        val extra = if (q.available > q.required) " (up to ${q.available})" else ""
        Text("${q.made + waiting} of ${q.required} made$extra", style = MaterialTheme.typography.bodyLarge)
        if (q.missing - waiting > 0) {
            Text(
                "Without ${q.missing - waiting} more, the identity becomes ${q.missingOutcome} at the validation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            node.period != "None" -> Text("Flips are made and deleted outside the validation.", style = MaterialTheme.typography.bodySmall)
            q.copy(made = q.made + waiting).canMake -> Button(onClick = onMake, enabled = busy == null) { Text("Make a flip") }
        }
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) Text("No flip made this epoch.", style = MaterialTheme.typography.bodyMedium)
            rows.forEach { row ->
                key(row.cid) {
                    HorizontalDivider()
                    FlipRowView(
                        row,
                        keywords,
                        canDelete = busy == null && canDeleteFlip(row, node.period),
                        onOpen = {
                            val made = row.made ?: return@FlipRowView
                            scope.launch {
                                val frames = withContext(Dispatchers.IO) { MadeFlipStore.frames(context, row.cid) }
                                if (frames != null) shown = made.asFlip(frames, keywords)
                            }
                        },
                        onDelete = { askDelete(row) },
                    )
                }
            }
        }
    }

    pending?.let { delete ->
        val q = read?.quota
        val after = q?.let { it.made - 1 }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Delete the flip") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The flip leaves the validation and its words can be used again. Fee: ${idna(delete.fee.toPlainString())}.")
                    if (q != null && after != null && after < q.required) {
                        Text(
                            "Then $after of ${q.required} required flips are made: make another before the flip lottery, " +
                                "or the identity becomes ${q.missingOutcome}.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { delete(delete.row) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep it") } },
        )
    }

    shown?.let { flip ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(flip.keywords.joinToString(" / ") { it.name.uppercase() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("As validators see it: the story and the shuffled story, in a random order.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().height(440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Story(flip, flip.leftOrder, selected = false, modifier = Modifier.weight(1f), onClick = null)
                        Story(flip, flip.rightOrder, selected = false, modifier = Modifier.weight(1f), onClick = null)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Close") } },
        )
    }
}

/** A flip of the list: its four pictures (or none, made in another app), its words, its state and Delete. */
@Composable
private fun FlipRowView(row: MyFlipRow, keywords: List<Keyword>, canDelete: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val made = row.made
    val frames by produceState<List<ByteArray>?>(null, row.cid) {
        value = if (made == null) null else withContext(Dispatchers.IO) { MadeFlipStore.frames(context, row.cid) }
    }
    Column(Modifier.fillMaxWidth().then(if (frames != null) Modifier.clickable(onClick = onOpen) else Modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        frames?.let { list ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEach { FrameImage(it, Modifier.weight(1f)) }
            }
        }
        if (made != null) {
            Text(made.words.mapNotNull { keywords.getOrNull(it)?.name?.uppercase() }.joinToString(" / "), fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = when (row.state) {
                MyFlipState.Waiting -> "Waiting for a block"
                MyFlipState.Deleting -> "Deleting: waiting for a block"
                MyFlipState.Published ->
                    if (made == null) "Made in another app" else "Published · ${dateFormat.format(Instant.ofEpochMilli(made.sentAt))}"
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (canDelete) TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
