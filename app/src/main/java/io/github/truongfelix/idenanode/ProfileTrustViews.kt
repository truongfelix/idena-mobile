package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** A call to the profiles contract, sent through the app's sending path with its fees shown first: what, detail, call. */
typealias ProfileAction = (what: String, detail: String?, call: SocialCall) -> Unit

/**
 * Trust or take it back, for a person (an address) or a topic ("r/<name>"), and "Trusted by 12 (+3 vouched)": a tap
 * lists the givers counted now ([onProfile] opens one). [trust] null while it is read; [failed] when the node did
 * not answer. Selected while [me]'s Trust is given, counted or not (it can always be taken back).
 */
@Composable
fun TrustRow(target: String, trust: TrustCount?, failed: Boolean, me: String?, canAct: Boolean, onCall: ProfileAction?, onProfile: (String) -> Unit) {
    var listing by remember(target) { mutableStateOf(false) }
    val body = MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onCall != null && me != null && me.lowercase() != target.lowercase()) {
            val mine = trust?.givenBy(me) == true
            FilterChip(
                selected = mine,
                enabled = canAct && trust != null,
                onClick = {
                    if (mine) onCall("Take your Trust back", "Your Trust in ${trustName(target)} no longer counts", ProfileCalls.untrust(target))
                    else onCall("Trust", "Gives your public Trust to ${trustName(target)}: everyone sees it; you can take it back", ProfileCalls.trust(target))
                },
                label = { Text(if (mine) "Trusted" else "Trust") },
            )
        }
        when {
            failed -> Text("Trust: the node did not answer.", style = body)
            trust == null -> Text("Trust: reading…", style = body)
            else -> Text(
                "🤝 ${trustText(trust) ?: "Trusted by nobody yet"}",
                Modifier.clickable(enabled = trust.counted.isNotEmpty()) { listing = true },
                style = body,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    if (listing && trust != null) {
        TrustGiversDialog(trust.counted, onDismiss = { listing = false }) { listing = false; onProfile(it) }
    }
}

private fun trustName(target: String) = if (target.startsWith(TOPIC_PREFIX)) target else "this person"

/** The givers counted now, each opening its profile; those who give as vouched members marked so. */
@Composable
private fun TrustGiversDialog(givers: List<TrustGiver>, onDismiss: () -> Unit, onProfile: (String) -> Unit) {
    val names = LocalNames.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trusted by") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                givers.forEach { giver ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onProfile(giver.address) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PersonAvatar(giver.address, 32.dp)
                        Text(displayName(giver.address, names) + if (giver.vouched) " (vouched)" else "", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A topic's Trust ([TrustRow]), read when the page opens and again after each profile call in a block ([reload]). */
@Composable
fun TopicTrust(name: String, me: String?, canAct: Boolean, reload: Int, onCall: ProfileAction?, onProfile: (String) -> Unit) {
    val context = LocalContext.current
    val target = topicChannel(name)
    var trust by remember(target) { mutableStateOf<Result<TrustCount>?>(null) }
    LaunchedEffect(target, reload) {
        trust = withContext(Dispatchers.IO) { runCatching { NodeProfilesSource(rpc(context)).trust(target) } }
    }
    TrustRow(target, trust?.getOrNull(), trust?.isFailure == true, me, canAct, onCall, onProfile)
}
