package io.github.truongfelix.idenanode

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/* The Node tab's Key card: import a key. */

@Composable
fun KeyCard(state: ScreenState, onImport: (String, String, (Boolean, String) -> Unit) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (state.hasKey && !open) {
        TextButton(onClick = { open = true; message = null }) { Text("Import another key") }
        message?.let { Text(it) }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key", style = MaterialTheme.typography.titleMedium)
            if (!open) {
                Text("Import the private key exported from the Idena app (Settings, Export my private key).")
                OutlinedButton(onClick = { open = true; message = null }) { Text("Import a key") }
                Text(
                    "No Idena account yet? Create one on app.idena.io, export its private key there, then " +
                        "import it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { openWebApp(context) }) { Text("Open app.idena.io") }
            } else {
                if (state.hasKey) {
                    Text("The current key is kept in a backup file.", style = MaterialTheme.typography.bodySmall)
                    Text(IMPORT_KEEPS_NODE_NOTE, style = MaterialTheme.typography.bodySmall)
                }
                if (state.status?.let { it.online && it.delegatee == null } == true) {
                    Text(MINING_IMPORT_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Do not run the same key on two nodes at the same time, for example here and on a desktop " +
                        "node: both would act for the identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Exported key") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    Button(
                        enabled = !busy && key.isNotBlank() && password.isNotEmpty(),
                        onClick = {
                            busy = true
                            onImport(key, password) { imported, text ->
                                busy = false
                                if (imported) {
                                    key = ""
                                    password = ""
                                    open = false
                                }
                                message = text
                            }
                        },
                    ) { Text("Import") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { open = false; key = ""; password = "" }) { Text("Cancel") }
                }
            }
            message?.let { Text(it) }
        }
    }
}

/** Opens the Idena web app in the browser; nothing happens if the phone has no browser. */
private fun openWebApp(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.idena.io"))) }
}
