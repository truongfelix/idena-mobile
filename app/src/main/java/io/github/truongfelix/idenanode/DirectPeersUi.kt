package io.github.truongfelix.idenanode

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The direct peers in Advanced settings ([DirectPeer]): the node's own peer code to copy or share, the nodes kept
 * (each with whether it is connected), and a dialog to add one from its code. [onChanged] runs after the list
 * is saved; the node takes it at its start.
 */
@Composable
fun DirectPeersSection(state: ScreenState, onChanged: () -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DirectPeer?>(null) }
    var ownName by remember { mutableStateOf(NodeSettings.ownPeerName(context)) }
    val peers = state.options.directPeers
    val ownId = state.status?.peerId

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(DIRECT_PEERS_TITLE, style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Outlined.Info,
            contentDescription = "About $DIRECT_PEERS_TITLE",
            Modifier.clickable { showInfo = true }.padding(6.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Your node's code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ownId == null) {
            Text("Shown while the node runs.", style = MaterialTheme.typography.bodySmall)
        } else {
            OwnCode(peerCode(ownId, ownName), ownName.isEmpty(), onName = { naming = true })
        }
        peers.forEach { peer ->
            PeerRow(peer, directPeerState(peer.id, state), onRemove = { removing = peer })
        }
        if (peers.size < MAX_DIRECT_PEERS) {
            TextButton(onClick = { adding = true }) { Text("Add a peer") }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(DIRECT_PEERS_TITLE) },
            text = { Text(DIRECT_PEERS_INFO) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
        )
    }
    if (naming) {
        NameDialog(ownName, onDismiss = { naming = false }) {
            NodeSettings.setOwnPeerName(context, it)
            ownName = NodeSettings.ownPeerName(context)
            naming = false
        }
    }
    if (adding) {
        AddPeerDialog(peers, ownId, onDismiss = { adding = false }) {
            NodeSettings.setDirectPeers(context, peers + it)
            adding = false
            onChanged()
        }
    }
    removing?.let { peer ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${peer.name.ifEmpty { shortPeerId(peer.id) }}?") },
            text = { Text("The node stops keeping it as a peer from its next start. To add it again, you need its code.") },
            confirmButton = {
                TextButton(onClick = {
                    NodeSettings.setDirectPeers(context, peers.filter { it.id != peer.id })
                    removing = null
                    onChanged()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** The node's own [code], with Copy, Share (Android's share menu) and the name in it. */
@Composable
private fun OwnCode(code: String, unnamed: Boolean, onName: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Text(code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(code))
            copied = true
        }) {
            if (copied) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
            Text(if (copied) " Copied" else "Copy")
        }
        TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code)
            context.startActivity(Intent.createChooser(send, "Share your node's code"))
        }) { Text("Share") }
        TextButton(onClick = onName) { Text(if (unnamed) "Add a name" else "Rename") }
    }
}

/** How a direct peer stands, for its row. */
enum class DirectPeerState(val label: String) {
    Connected("connected"),
    NotConnected("not connected"),
    AfterRestart("after a restart"),
    NodeStopped("node stopped"),
}

/** [id]'s state: connected when among the node's peers; "after a restart" when the running node was not given it. */
fun directPeerState(id: String, state: ScreenState): DirectPeerState {
    val status = state.status ?: return DirectPeerState.NodeStopped
    return when {
        id in status.peerIds -> DirectPeerState.Connected
        state.runningOptions.directPeers?.contains(id) == false -> DirectPeerState.AfterRestart
        else -> DirectPeerState.NotConnected
    }
}

@Composable
private fun PeerRow(peer: DirectPeer, peerState: DirectPeerState, onRemove: () -> Unit) {
    val connected = peerState == DirectPeerState.Connected
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (connected) "●" else "○",
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(peer.name.ifEmpty { "Unnamed node" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${shortPeerId(peer.id)} · ${peerState.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SmallIconButton(Icons.Filled.Close, "Remove ${peer.name.ifEmpty { shortPeerId(peer.id) }}", MaterialTheme.colorScheme.onSurfaceVariant) {
            onRemove()
        }
    }
}

/** Asks the name the node's own code carries. */
@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name in your code") },
        text = {
            Column {
                Text("The other person sees it in their list; it may stay empty.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Takes a peer code (or a bare peer id), pasted or typed, and the name to keep it under. */
@Composable
private fun AddPeerDialog(peers: List<DirectPeer>, ownId: String?, onDismiss: () -> Unit, onAdd: (DirectPeer) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    val parsed = parsePeerCode(code)
    val problem = when {
        code.isBlank() -> null
        parsed == null -> "Not a peer code: it reads idena-peer:Qm… or idena-peer:12D3Koo…"
        else -> directPeerProblem(parsed, peers, ownId)
    }
    fun setCode(text: String) {
        code = text
        // The code's name fills the field until the user types their own.
        if (!nameEdited) name = parsePeerCode(text)?.name.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a peer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(code, { setCode(it) }, label = { Text("Peer code") }, singleLine = true, isError = problem != null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboard.getText()?.text?.let { setCode(it) } }) { Text("Paste") }
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(name, { name = it; nameEdited = true }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && problem == null, onClick = {
                parsed?.let { onAdd(it.copy(name = cleanPeerName(name))) }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
