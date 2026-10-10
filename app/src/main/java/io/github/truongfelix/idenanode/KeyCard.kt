package io.github.truongfelix.idenanode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Node tab's Key card: create a new account or import a key; with a key, back it up or import another one.
 * A new account's key goes to the node only once its backup is checked (KeyBackup.kt).
 */

/**
 * [newKey] and [exportKey] make a backup with a password (Mobile.newKey, Mobile.exportKey; run off the main thread);
 * [onCreate] gives the node a new account's checked backup, [onImport] a key typed or chosen in a file.
 */
@Composable
fun KeyCard(
    state: ScreenState,
    newKey: (String) -> String,
    exportKey: (String) -> String,
    onCreate: (String, String, (Boolean, String) -> Unit) -> Unit,
    onImport: (String, String, (Boolean, String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val keyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readBackupFile(context, uri) } }
                .onSuccess { key = it; message = null }
                .onFailure { message = "Not read: ${it.message}" }
        }
    }
    if (creating) {
        KeyBackupDialog(
            newAccount = true,
            makeBackup = newKey,
            onUse = { backup, typed ->
                creating = false
                busy = true
                onCreate(backup, typed) { _, text -> busy = false; message = text }
            },
            onClose = { creating = false },
        )
    }
    if (backingUp) KeyBackupDialog(newAccount = false, makeBackup = exportKey, onUse = { _, _ -> }, onClose = { backingUp = false })
    if (state.hasKey && !open) {
        Row {
            TextButton(onClick = { backingUp = true; message = null }) { Text("Back up my key") }
            TextButton(onClick = { open = true; message = null }) { Text("Import another key") }
        }
        message?.let { Text(it) }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key", style = MaterialTheme.typography.titleMedium)
            if (!open) {
                Text("No account on this phone yet.")
                Button(enabled = !busy, onClick = { creating = true; message = null }) { Text("Create a new account") }
                OutlinedButton(enabled = !busy, onClick = { open = true; message = null }) { Text("Import a key") }
                Text(
                    "Import takes a private key exported from an Idena app (Settings, Private key, Export) or a backup " +
                        "made here.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                Pill("Choose a file", enabled = !busy) { keyFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
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
