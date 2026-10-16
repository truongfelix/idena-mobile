package io.github.truongfelix.idenanode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPORTS_INFO = "When the node stops answering for 5 minutes, the app writes a report and restarts " +
    "the node, up to 3 times in 6 hours.\n\n" +
    "A report shows what each part of the node was doing (its goroutines) and about the last 20 minutes of its log. " +
    "It holds the node's address and its peers' network addresses, no key.\n\n" +
    "If your node has trouble connecting or syncing: Make a report, Save the last report, and share it with the Idena " +
    "community to understand what happens."

/** How long the section waits for the report it asked for. */
private const val REPORT_WAIT_MS = 30_000L

/**
 * The node reports in Advanced settings ([NodeReports]): the app's last restart of the node, and buttons to make a
 * report from the running node ([syncEnabled]) and to save the last one as a file the user picks.
 */
@Composable
fun NodeReportsSection(syncEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reads by remember { mutableIntStateOf(0) }
    val restarts = remember(reads) { NodeReports.restarts(context) }
    val latest = remember(reads) { NodeReports.latest(context) }
    var making by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report = latest
        if (uri == null || report == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { NodeReports.save(context, report, uri) } }
            note = result.fold({ "Saved." }, { "Not saved: ${it.message}" })
        }
    }
    LaunchedEffect(making) {
        if (!making) return@LaunchedEffect
        val before = latest?.name
        var waited = 0L
        while (waited < REPORT_WAIT_MS && NodeReports.latest(context)?.name == before) {
            delay(1_000)
            waited += 1_000
        }
        note = if (NodeReports.latest(context)?.name == before) "The node made no report: is it running?" else null
        making = false
        reads++
    }

    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(lastRestartText(restarts), Modifier.weight(1f), style = small, color = dim)
        Icon(
            Icons.Outlined.Info,
            contentDescription = "About the node reports",
            Modifier.clickable { showInfo = true }.padding(6.dp).size(18.dp),
            tint = dim,
        )
    }
    latest?.let { Text("Last report: ${reportTime(it.lastModified())}", style = small, color = dim) }
    note?.let { Text(it, style = small, color = dim) }
    Row {
        TextButton(enabled = syncEnabled && !making, onClick = {
            note = null
            making = true
            NodeService.makeReport(context)
        }) { Text(if (making) "Making the report…" else "Make a report") }
        TextButton(enabled = latest != null, onClick = { latest?.let { saver.launch(it.name) } }) { Text("Save the last report") }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Node reports") },
            text = { Text(REPORTS_INFO) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
        )
    }
}
