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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Why a post, a like or a tip needs iDNA, asked with an empty balance (B-131), and how to get some: someone sends or
 * tips it ([address] to share), the community chats ([COMMUNITY]), or becoming a validator; and why more validators
 * help everyone. It explains only.
 */
@Composable
fun NoIdnaDialog(address: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
                Text(t(R.string.no_idna_more_validators), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}
