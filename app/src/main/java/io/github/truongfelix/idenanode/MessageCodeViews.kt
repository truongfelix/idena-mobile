package io.github.truongfelix.idenanode

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.truongfelix.idenanode.go.mobile.Mobile

/** The phone's own message code ([messageCode]), from its key (Keystore and node library), or null when unreadable. */
fun readOwnMessageCode(context: Context): String? =
    runCatching { NodeKeys.files(context).read() }.getOrNull()?.let { key ->
        try {
            Mobile.privateKeyPublicKey(key).ifEmpty { null }?.let(::messageCode)
        } finally {
            key.fill(0)
        }
    }

/** The account's message code, to share: its text and QR code, Copy and Share (Android's share menu). */
@Composable
fun OwnMessageCodeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Null while it is read; "" when it cannot be.
    var code by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { code = withContext(Dispatchers.IO) { readOwnMessageCode(context) }.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.msgcode_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.msgcode_note), style = MaterialTheme.typography.bodySmall)
                when (val shown = code) {
                    null -> {}
                    "" -> Text(t(R.string.msgcode_unavailable), color = MaterialTheme.colorScheme.error)
                    else -> {
                        Image(qrCode(shown), contentDescription = null, Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                        Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(shown))
                                copied = true
                            }) {
                                if (copied) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
                                Text(if (copied) " " + t(R.string.node_copied) else t(R.string.node_copy))
                            }
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shown)
                                context.startActivity(Intent.createChooser(send, t(R.string.msgcode_share)))
                            }) { Text(t(R.string.node_share)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}

/** Pasting [address]'s message code: [onAdd] gets its public key once the code is checked to be that account's. */
@Composable
fun AddMessageCodeDialog(address: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val key = messageCodeFor(text, address)
    val wrong = text.isNotBlank() && key == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.msgcode_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.msgcode_add_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t(R.string.msgcode_field)) },
                    isError = wrong,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = if (wrong) ({ Text(t(R.string.msgcode_wrong)) }) else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = { key?.let(onAdd) }, enabled = key != null) { Text(t(R.string.msgcode_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}
