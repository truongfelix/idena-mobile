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
 * Trust or take it back, for a person (an address) or a topic ("r/<name>"): selected while [me]'s Trust is given,
 * counted or not (it can always be taken back); none for [me] itself. [trust] null while it is read.
 */
@Composable
fun TrustChip(target: String, trust: TrustCount?, me: String?, canAct: Boolean, onCall: ProfileAction?) {
    if (onCall == null || me == null || me.lowercase() == target.lowercase()) return
    val mine = trust?.givenBy(me) == true
    // A Trust or its taking back waits for its block: no second one meanwhile (B-81).
    val access = LocalProfileAccess.current
    val waiting = access != null && (access.waiting(ProfileCalls.trust(target)) || access.waiting(ProfileCalls.untrust(target)))
    FilterChip(
        selected = mine,
        enabled = canAct && trust != null && !waiting,
        onClick = {
            if (mine) onCall("Take your Trust back", "Your Trust in ${trustName(target)} no longer counts", ProfileCalls.untrust(target))
            else onCall("Trust", "Gives your public Trust to ${trustName(target)}: everyone sees it; you can take it back", ProfileCalls.trust(target))
        },
        label = { Text(if (waiting) "Trust · waiting for a block" else if (mine) "Trusted" else "Trust") },
    )
}

/**
 * A topic's Trust: the button ([TrustChip]) and "Trusted by 15", the sum; a tap lists the givers counted now, split in
 * identities and vouched members ([onProfile] opens one). [failed] when the node did not answer.
 */
@Composable
fun TrustRow(target: String, trust: TrustCount?, failed: Boolean, me: String?, canAct: Boolean, onCall: ProfileAction?, onProfile: (String) -> Unit) {
    var listing by remember(target) { mutableStateOf(false) }
    val body = MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrustChip(target, trust, me, canAct, onCall)
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
        TrustGiversDialog(trust, onDismiss = { listing = false }) { listing = false; onProfile(it) }
    }
}

private fun trustName(target: String) = if (target.startsWith(TOPIC_PREFIX)) target else "this person"

/** The givers counted now, each opening its profile: the split, then the identities, then the vouched members. */
@Composable
private fun TrustGiversDialog(trust: TrustCount, onDismiss: () -> Unit, onProfile: (String) -> Unit) {
    val names = LocalNames.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trusted by ${trust.total}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(trustSplit(trust), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                trust.counted.sortedBy { it.vouched }.forEach { giver ->
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
