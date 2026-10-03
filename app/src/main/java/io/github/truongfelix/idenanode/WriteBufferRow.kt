package io.github.truongfelix.idenanode

import android.app.ActivityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
 * The chain database's write buffer in the Sync card: the size chosen ([WriteBuffer]) with a menu of the
 * others. The node takes it at its start: while it runs with another size, Restart applies the choice.
 */
@Composable
fun WriteBufferRow(state: ScreenState, onChange: (WriteBuffer) -> Unit, onRestart: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = state.writeBuffer
    val pending = writeBufferPending(state.syncEnabled, state.runningWriteBuffer, chosen)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Write buffer", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (pending) "${chosen.label} · restart to apply" else chosen.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pending) TextButton(onClick = { confirm = true }) { Text("Restart") }
        Box {
            AssistChip(onClick = { menu = true }, label = { Text("${chosen.mib} MiB  ▾") })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                WriteBuffer.entries.forEach { option ->
                    DropdownMenuItem(
                        leadingIcon = { RadioButton(selected = option == chosen, onClick = null) },
                        text = {
                            Column {
                                Text("${option.mib} MiB · ${option.label}")
                                Text(option.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            menu = false
                            if (option != chosen) {
                                onChange(option)
                                confirm = writeBufferPending(state.syncEnabled, state.runningWriteBuffer, option)
                            }
                        },
                    )
                }
                val memory = remember {
                    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                }
                Text(
                    "This phone: ${ramGb(memory.totalMem)} GB of RAM. The node takes the size at its start.",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirm) {
        val status = state.status
        val risk = status?.let { restartRisk(Instant.now(), parseValidationTime(it.nextValidation), it.period) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restart the node?") },
            text = {
                Text(
                    "The node uses the ${chosen.mib} MiB write buffer from its next start. A restart now takes a " +
                        "few minutes: the node opens its database and looks for peers again." +
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
