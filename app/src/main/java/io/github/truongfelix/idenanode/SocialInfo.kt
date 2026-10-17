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

/**
 * The details behind the Social status (title row): the node, the blocks read, the history, the last action, "Scan
 * blocks". [peers]: the node's, as the title row reads them ([SocialModel.status]).
 */
@Composable
fun SocialInfoDialog(model: SocialModel, peers: Int?, onDismiss: () -> Unit) {
    val status = model.status(peers)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.health, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(socialBarLabel(status, peers ?: model.node?.peers))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val node = model.node
                Text(
                    when {
                        node == null -> t(R.string.social_info_node_no_answer)
                        node.syncing -> t(
                            R.string.social_info_node_syncing, blockNumber.format(node.currentBlock), blockNumber.format(node.highestBlock), peerText(peers ?: node.peers),
                        )
                        else -> t(R.string.social_info_node_block, model.head?.let { blockNumber.format(it) } ?: "…", peerText(peers ?: node.peers))
                    },
                )
                Text(newestText(model.cache, model.head) + ".")
                Text(historyText(model.cache, model.feed.size) + ".")
                Text(t(R.string.social_info_scan, model.scanStatus))
                model.sending.lastAction?.let { Text(t(R.string.social_info_last_action, it)) }
                HorizontalDivider()
                FilterChip(selected = model.scanning, onClick = { model.setScanningOn(!model.scanning) }, label = { Text(t(R.string.social_scan_blocks)) })
                Text(
                    t(R.string.social_info_scan_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DotLegend(
                    Health.Good to t(R.string.social_legend_good),
                    Health.Warn to t(R.string.social_legend_warn),
                    Health.Bad to t(R.string.social_legend_bad),
                    Health.Off to t(R.string.social_legend_off),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}

private fun peerText(peers: Int): String = tp(R.plurals.social_info_peers, peers, peers)

val blockNumber: NumberFormat = NumberFormat.getIntegerInstance()

/** The newest block scanned against the node's block, and how far behind it is (a block every ~20 s). */
private fun newestText(cache: SocialCache?, head: Long?): String {
    val node = t(R.string.social_info_node_head, head?.let { blockNumber.format(it) } ?: "…")
    if (cache == null || cache.high < cache.low) return t(R.string.social_info_newest_none, node)
    val scanned = t(R.string.social_info_newest, blockNumber.format(cache.high), node)
    val behind = (head ?: return scanned) - cache.high
    if (behind <= 0) return scanned
    val minutes = behind * 20 / 60
    val time = if (minutes >= 120) t(R.string.social_info_about_hours, blockNumber.format(minutes / 60)) else t(R.string.social_info_about_minutes, minutes)
    return t(R.string.social_info_behind, scanned, blockNumber.format(behind), time)
}

/** How far back the history scan has come, and the posts found. */
private fun historyText(cache: SocialCache?, posts: Int): String {
    if (cache == null || cache.high < cache.low) return t(R.string.social_info_history_none)
    val found = tp(R.plurals.social_info_posts, posts, posts)
    if (cache.historyDone) return t(R.string.social_info_history_done, blockNumber.format(HISTORY_START), found)
    val percent = (cache.scannedShare * 100).toInt()
    return t(R.string.social_info_history_part, percent, blockNumber.format(cache.low), found)
}
