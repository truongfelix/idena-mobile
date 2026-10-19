package io.github.truongfelix.idenanode

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * What the Sync switch does, in the node panel and the title row: on asks for notifications (Android 13+) and starts
 * the node's service; off stops it; [model] follows before its next read.
 */
@Composable
fun rememberSetSync(model: NodeModel): (Boolean) -> Unit {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { enabled ->
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            NodeService.start(context)
        } else {
            NodeService.stop(context)
        }
        model.syncChanged(enabled)
    }
}

/**
 * The Sync switch of the title row (B-132): on at once; off only after a confirmation, so that a stray tap does not
 * stop the node (a validation running says so). Without a key it stays off, as the panel's.
 */
@Composable
fun TitleSyncSwitch(model: NodeModel, setSync: (Boolean) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val state = model.state
    Switch(
        checked = state.syncEnabled,
        onCheckedChange = { on -> if (on) setSync(true) else asking = true },
        enabled = state.hasKey || state.syncEnabled,
        modifier = Modifier.semantics { contentDescription = t(R.string.sync_switch_description) },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(t(R.string.sync_off_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(R.string.sync_off_text), style = MaterialTheme.typography.bodyMedium)
                    if (model.validationOpen) Text(t(R.string.sync_off_validation), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    setSync(false)
                }) { Text(t(R.string.sync_off_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(t(R.string.common_cancel)) } },
        )
    }
}
