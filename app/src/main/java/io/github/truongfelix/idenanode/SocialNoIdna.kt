package io.github.truongfelix.idenanode

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Why a post, a like or a tip needs iDNA, asked with an empty balance (B-131), and how to get some: someone sends or
 * tips it ([address] to share), the community chats ([COMMUNITY]), or becoming a validator ([ValidatorHowDialog]);
 * and why more validators help everyone. It explains only.
 */
@Composable
fun NoIdnaDialog(address: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var how by remember { mutableStateOf(false) }
    if (how) {
        ValidatorHowDialog { how = false }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.no_idna_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t(R.string.no_idna_why), style = MaterialTheme.typography.bodyMedium)
                Text(t(R.string.no_idna_how), style = MaterialTheme.typography.titleSmall)
                Text(t(R.string.no_idna_send), style = MaterialTheme.typography.bodyMedium)
                if (address != null) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
                        context.startActivity(Intent.createChooser(send, null))
                    }) { Text(t(R.string.profile_share_address)) }
                }
                Text(t(R.string.no_idna_community), style = MaterialTheme.typography.bodyMedium)
                Row {
                    COMMUNITY.forEach { (name, url) ->
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(name) }
                    }
                }
                Text(t(R.string.no_idna_validator), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { how = true }) { Text(t(R.string.validator_how_open)) }
                Text(t(R.string.no_idna_more_validators), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}

/**
 * How to become a validator (owner 2026-10-19): what an Idena identity is (one per person, anonymous, no name nor
 * document), then the way there: an invitation, its activation in the node panel, the next validation, and what a
 * validated identity earns.
 */
@Composable
fun ValidatorHowDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.validator_how_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t(R.string.validator_identity), style = MaterialTheme.typography.bodyMedium)
                for (step in listOf(R.string.validator_step_invite, R.string.validator_step_activate, R.string.validator_step_validation, R.string.validator_step_earn)) {
                    Text(t(step), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}
