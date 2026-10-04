package io.github.truongfelix.idenanode

import android.app.ActivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * The write buffer of [database] in the Sync card: the size chosen ([WriteBuffer]) with a menu of the others,
 * and ⓘ for what it does. The node takes it at its start: while it runs with another size, Restart applies
 * the choice.
 */
@Composable
fun WriteBufferRow(database: NodeDatabase, state: ScreenState, onChange: (WriteBuffer) -> Unit, onRestart: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = state.writeBuffers[database] ?: WriteBuffer.DEFAULT
    val running = state.runningWriteBuffers[database]
    val pending = writeBufferPending(state.syncEnabled, running, chosen)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(database.title, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "About the ${database.noun} buffer",
                    Modifier.clickable { info = true }.padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending) {
                Text(
                    "Restart to apply",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (pending) TextButton(onClick = { confirm = true }) { Text("Restart") }
        Box {
            AssistChip(onClick = { menu = true }, label = { Text("${chosen.label}  ▾") })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                WriteBuffer.entries.forEach { option ->
                    DropdownMenuItem(
                        leadingIcon = { RadioButton(selected = option == chosen, onClick = null) },
                        text = { Text(option.label) },
                        onClick = {
                            menu = false
                            if (option != chosen) {
                                onChange(option)
                                confirm = writeBufferPending(state.syncEnabled, running, option)
                            }
                        },
                    )
                }
            }
        }
    }
    if (info) {
        val memory = remember {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        }
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(database.title) },
            text = {
                Text("${database.about}\n\n$WRITE_BUFFER_INFO\n\nThis phone: ${ramGb(memory.totalMem)} GB of RAM.")
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } },
        )
    }
    if (confirm) {
        val status = state.status
        val risk = status?.let { restartRisk(Instant.now(), parseValidationTime(it.nextValidation), it.period) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restart the node?") },
            text = {
                Text(
                    "The ${database.noun} uses the ${chosen.mib} MiB buffer from the node's next start. A restart now " +
                        "takes a few minutes: the node opens its databases and looks for peers again." +
                        (risk?.let { "\n\n${it.message}" } ?: ""),
                )
            },
            confirmButton = {
                TextButton(enabled = risk?.canRestart != false, onClick = { confirm = false; onRestart() }) { Text("Restart now") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Later") } },
        )
    }
}
