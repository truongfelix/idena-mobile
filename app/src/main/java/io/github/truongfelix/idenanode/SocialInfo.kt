package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/** The details behind the Social status (title row): the node, the blocks read, the history, the last action, and "Scan blocks". */
@Composable
fun SocialInfoDialog(model: SocialModel, onDismiss: () -> Unit) {
    val status = model.status
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.health, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(socialBarLabel(status, model.node?.peers))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val node = model.node
                Text(
                    when {
                        node == null -> "Node: no answer."
                        node.syncing -> "Node: syncing, block ${blockNumber.format(node.currentBlock)} of ${blockNumber.format(node.highestBlock)} · ${peerText(node.peers)}."
                        else -> "Node: block ${model.head?.let { blockNumber.format(it) } ?: "…"} · ${peerText(node.peers)}."
                    },
                )
                Text(newestText(model.cache, model.head) + ".")
                Text(historyText(model.cache, model.feed.size) + ".")
                Text("Scan: ${model.scanStatus}")
                model.sending.lastAction?.let { Text("Last action: $it") }
                HorizontalDivider()
                FilterChip(selected = model.scanning, onClick = { model.setScanningOn(!model.scanning) }, label = { Text("Scan blocks") })
                Text(
                    "On: the new blocks, then the older posts once, then each new block. Off: nothing is read; the posts " +
                        "already read stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DotLegend(
                    Health.Good to "Up to date",
                    Health.Warn to "Behind or reading older posts",
                    Health.Bad to "No node or no peers",
                    Health.Off to "Off, or paused during a validation",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun peerText(peers: Int): String = if (peers == 1) "1 peer" else "$peers peers"

val blockNumber: NumberFormat = NumberFormat.getIntegerInstance()

/** The newest block scanned against the node's block, and how far behind it is (a block every ~20 s). */
private fun newestText(cache: SocialCache?, head: Long?): String {
    val node = head?.let { "node: ${blockNumber.format(it)}" } ?: "node: …"
    if (cache == null || cache.high < cache.low) return "Newest block scanned: none yet · $node"
    val scanned = "Newest block scanned: ${blockNumber.format(cache.high)} · $node"
    val behind = (head ?: return scanned) - cache.high
    if (behind <= 0) return scanned
    val minutes = behind * 20 / 60
    val time = if (minutes >= 120) "about ${minutes / 60} h" else "about $minutes min"
    return "$scanned (${blockNumber.format(behind)} behind, $time)"
}

/** How far back the history scan has come, and the posts found. */
private fun historyText(cache: SocialCache?, posts: Int): String {
    if (cache == null || cache.high < cache.low) return "History: not scanned yet"
    val found = if (posts == 1) "1 post" else "$posts posts"
    if (cache.historyDone) return "History: complete since block ${blockNumber.format(HISTORY_START)} · $found"
    val percent = (cache.scannedShare * 100).toInt()
    return "History: $percent% scanned, back to block ${blockNumber.format(cache.low)} · $found"
}
