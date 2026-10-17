package io.github.truongfelix.idenanode

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The New versions state of the screens ([ReleaseCheck]): the setting, the release ready and whether its page shows. */
object ReleaseUi {
    var on by mutableStateOf<Boolean?>(null)
    var ready by mutableStateOf<AppRelease?>(null)
    var shown by mutableStateOf(false)

    /** Reads the setting, then asks GitHub when due; [gapMs] from the last time. */
    suspend fun refresh(context: Context, gapMs: Long) {
        on = ReleaseCheck.setting(context)
        ready = withContext(Dispatchers.IO) {
            runCatching { ReleaseCheck.runIfDue(context, gapMs) }
                .onFailure { Log.w("ReleaseCheck", "Release check: $it") }
                .getOrNull() ?: ReleaseCheck.ready(context)
        }
    }
}

/** The New versions line of the Node card: the switch, and the release ready, which opens its page. */
@Composable
fun ReleaseRow() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t(R.string.release_title), style = MaterialTheme.typography.bodyMedium)
            Text(t(R.string.release_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = ReleaseUi.on == true, onCheckedChange = { on ->
            ReleaseCheck.set(context, on)
            scope.launch { ReleaseUi.refresh(context, 0) }
        })
    }
    ReleaseUi.ready?.let {
        TextButton(onClick = { ReleaseUi.shown = true }) { Text(t(R.string.release_ready_link, it.version)) }
    }
}

/**
 * The app's part of New versions, at the root of the main screen: the check when the app opens, the question asked
 * once (after an install, once the phone has a key), and the release's page.
 */
@Composable
fun ReleaseDialogs(hasKey: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ReleaseUi.refresh(context.applicationContext, ReleaseCheck.OPEN_GAP_MS) }
    val scope = rememberCoroutineScope()
    if (ReleaseUi.on == null && hasKey) {
        fun answer(on: Boolean) {
            ReleaseCheck.set(context.applicationContext, on)
            scope.launch { ReleaseUi.refresh(context.applicationContext, 0) }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(t(R.string.release_ask_title)) },
            text = { Text(t(R.string.release_ask_text)) },
            confirmButton = { TextButton(onClick = { answer(true) }) { Text(t(R.string.release_ask_yes)) } },
            dismissButton = { TextButton(onClick = { answer(false) }) { Text(t(R.string.release_ask_no)) } },
        )
    }
    val release = ReleaseUi.ready
    if (ReleaseUi.shown && release != null) {
        AlertDialog(
            onDismissRequest = { ReleaseUi.shown = false },
            title = { Text(t(R.string.release_ready, release.version)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(plainNote(release.note).ifEmpty { t(R.string.release_no_note) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ReleaseUi.shown = false
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page)))
                    } catch (e: ActivityNotFoundException) {
                        Log.w("ReleaseCheck", "No browser: $e")
                    }
                }) { Text(t(R.string.release_download)) }
            },
            dismissButton = { TextButton(onClick = { ReleaseUi.shown = false }) { Text(t(R.string.common_close)) } },
        )
    }
}
