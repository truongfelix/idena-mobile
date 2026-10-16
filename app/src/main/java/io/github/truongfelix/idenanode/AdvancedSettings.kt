package io.github.truongfelix.idenanode

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * Advanced settings in the Node card, folded until opened, in four groups: Mining (the [delegation], once the node has
 * the account), Connections (the [routerPort] on Wi-Fi, the direct peers ([DirectPeersSection]), the peer level
 * ([PeerLevel]) and the IPFS connection limit ([IpfsConnections])), Storage (the write buffer of each database,
 * [WriteBuffer]) and the [NodeReportsSection]. The node takes the connection and storage settings at its start: while it runs with others, a line
 * under the title, shown folded too, offers the restart that applies them. [onChanged] runs after a choice is saved.
 */
@Composable
fun AdvancedSettings(
    state: ScreenState,
    delegation: @Composable (NodeStatus) -> Unit,
    routerPort: @Composable () -> Unit,
    onChanged: () -> Unit,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val options = state.options
    val pending = pendingChanges(state.syncEnabled, options, state.runningOptions)

    // Asks for the restart at once when the choice just saved is one the node does not run with.
    fun saved(title: String) {
        onChanged()
        confirm = pendingChanges(state.syncEnabled, NodeSettings.options(context), state.runningOptions).any { it.title == title }
    }

    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced settings", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Fold the advanced settings" else "Open the advanced settings",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pending.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Restart to apply: " + pending.joinToString(", ") { it.title },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirm = true }) { Text("Restart") }
        }
    }
    if (open) {
        state.status?.takeUnless { it.accountPending }?.let {
            GroupTitle("Mining")
            delegation(it)
        }
        GroupTitle("Connections")
        routerPort()
        DirectPeersSection(state) { saved(DIRECT_PEERS_TITLE) }
        ChoiceRow(
            title = PEER_LEVEL_TITLE,
            info = { PEER_LEVEL_INFO },
            choices = PeerLevel.entries,
            chosen = options.peerLevel,
            label = { it.label },
            note = { "up to ${it.maxPeers} peers" + if (it == PeerLevel.DEFAULT) ", default" else "" },
            onChange = {
                NodeSettings.setPeerLevel(context, it)
                saved(PEER_LEVEL_TITLE)
            },
        )
        ChoiceRow(
            title = IPFS_CONNECTIONS_TITLE,
            info = { IPFS_CONNECTIONS_INFO },
            choices = IpfsConnections.entries,
            chosen = options.ipfsConnections,
            label = { it.label },
            note = {
                when {
                    !it.allows(options.peerLevel) -> "too few for ${options.peerLevel.label}"
                    it == IpfsConnections.DEFAULT -> "default"
                    else -> null
                }
            },
            enabled = { it.allows(options.peerLevel) },
            onChange = {
                NodeSettings.setIpfsConnections(context, it)
                saved(IPFS_CONNECTIONS_TITLE)
            },
        )
        GroupTitle("Storage")
        NodeDatabase.entries.forEach { database ->
            ChoiceRow(
                title = database.title,
                info = { "${database.about}\n\n$WRITE_BUFFER_INFO\n\nThis phone: ${ramGb(totalRam(context))} GB of RAM." },
                choices = WriteBuffer.entries,
                chosen = options.writeBuffers[database] ?: WriteBuffer.DEFAULT,
                label = { it.label },
                note = { if (it == WriteBuffer.DEFAULT) "${it.mib} MiB" else null },
                onChange = {
                    NodeSettings.setWriteBuffer(context, database, it)
                    saved(database.title)
                },
            )
        }
        GroupTitle("Node reports")
        NodeReportsSection(state.syncEnabled)
    }
    if (confirm) {
        val status = state.status
        val risk = status?.let { restartRisk(Instant.now(), parseValidationTime(it.nextValidation), it.period) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restart the node?") },
            text = {
                Text(
                    "The node takes these at its next start:\n" + pending.joinToString("\n") { "• ${it.title}: ${it.value}" } +
                        "\n\nA restart now takes a few minutes: the node opens its databases and looks for peers again." +
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

/** The title of a group of advanced settings. */
@Composable
private fun GroupTitle(title: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/**
 * One setting: its [title] with ⓘ for [info], and a chip with the [chosen] choice that opens a menu of all
 * [choices], each with its [label] and an optional [note] under it; a choice not [enabled] shows but cannot be
 * picked.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    info: () -> String,
    choices: List<T>,
    chosen: T,
    label: (T) -> String,
    note: (T) -> String?,
    enabled: (T) -> Boolean = { true },
    onChange: (T) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.Outlined.Info,
                contentDescription = "About $title",
                Modifier.clickable { showInfo = true }.padding(6.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            AssistChip(onClick = { menu = true }, label = { Text("${label(chosen)}  ▾") })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                choices.forEach { option ->
                    DropdownMenuItem(
                        leadingIcon = { RadioButton(selected = option == chosen, onClick = null, enabled = enabled(option)) },
                        text = {
                            Column {
                                Text(label(option))
                                note(option)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        enabled = enabled(option),
                        onClick = {
                            menu = false
                            if (option != chosen) onChange(option)
                        },
                    )
                }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = { Text(info()) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
        )
    }
}

/** The phone's RAM in bytes, as Android reports it. */
private fun totalRam(context: Context): Long =
    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
