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
    var busy by remember { mutableStateOf<String?>(t(R.string.ads_reading_profile)) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ad) {
        val entry = ad.entry
        val planned = withContext(Dispatchers.IO) {
            runCatching { myAdsNode(context).planRemoval(me, entry ?: error(t(R.string.ads_not_in_profile))) }
        }
        busy = null
        planned.onSuccess { plan = it }.onFailure { error = t(R.string.ads_cannot_be_sent, it.message.toString()) }
    }
    val ready = plan
    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(t(R.string.ads_remove_title)) },
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
                    busy = t(R.string.ads_updating_profile)
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
                                    if (block == null) t(R.string.ads_not_removed, NOT_SENT) else t(R.string.ads_removed, ad.title)
                                },
                                onFailure = { t(R.string.ads_not_sent, it.message.toString()) },
                            ),
                        )
                    }
                }) { Text(t(R.string.common_remove)) }
            }
        },
        dismissButton = { if (busy == null || ready == null) TextButton(onClick = onClose) { Text(t(R.string.common_cancel)) } },
    )
}
