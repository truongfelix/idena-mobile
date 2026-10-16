package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Remove from my profile…": takes the campaign [ad] out of the profile of [me], once confirmed with its fees. The
 * phone keeps its copy: the approved ad can be shown again.
 */
@Composable
fun RemoveFromProfileDialog(ad: MyAd, me: String, onClose: () -> Unit, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<RemovalPlan?>(null) }
    var busy by remember { mutableStateOf<String?>("Reading your profile…") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ad) {
        val entry = ad.entry
        val planned = withContext(Dispatchers.IO) {
            runCatching { myAdsNode(context).planRemoval(me, entry ?: error("this ad is not in your profile")) }
        }
        busy = null
        planned.onSuccess { plan = it }.onFailure { error = "It cannot be sent: ${it.message}" }
    }
    val ready = plan
    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Remove from my profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready != null) Text(removeConfirmText(ad.title, ready.fees))
                busy?.let { Text(it); CircularProgressIndicator() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (ready != null && busy == null) {
                TextButton(onClick = {
                    busy = "Updating your profile…"
                    scope.launch {
                        val sent = withContext(Dispatchers.IO) {
                            runCatching {
                                val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
                                waitForBlock(NodeTxLookup(rpc), myAdsNode(context).sendRemoval(me, ready))
                            }
                        }
                        busy = null
                        onDone(
                            sent.fold(
                                onSuccess = { block ->
                                    if (block == null) "Not removed: $NOT_SENT" else "“${ad.title}” is no longer in your profile."
                                },
                                onFailure = { "Not sent: ${it.message}" },
                            ),
                        )
                    }
                }) { Text("Remove") }
            }
        },
        dismissButton = { if (busy == null || ready == null) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
