package io.github.truongfelix.idenanode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.truongfelix.idenanode.go.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/*
 * The Key card's backup of a key, in three steps: a password, the backup saved to a file of the user's choice, then
 * checked (the file chosen again and the password: it opens as the same account). For a new account the node uses
 * the key only after the check; "Back up my key" does the same for the key already on the phone. The backup is the
 * Idena export format (the key encrypted with the password), which the desktop imports too.
 */

/** The shortest password a backup takes: the export format hashes it once, with no slow key stretching. */
const val MIN_PASSWORD_LENGTH = 12

/** The largest file the check reads: a backup is 120 characters. */
private const val MAX_BACKUP_FILE = 4096

/** What keeps [password], typed twice ([again]), from protecting a backup, or null. */
fun passwordProblem(password: String, again: String): String? = when {
    password.codePointCount(0, password.length) < MIN_PASSWORD_LENGTH -> t(R.string.wallet_key_password_too_short, MIN_PASSWORD_LENGTH)
    password != again -> t(R.string.wallet_key_passwords_differ)
    else -> null
}

/** The file name offered for the backup of [address]. */
fun backupFileName(address: String): String = "idena-key-${address.take(10).lowercase()}.txt"

/** What the check says when the backup at hand opens as [opened], for the account [expected]; null [opened]: [error]. */
fun backupCheckText(expected: String, opened: String?, error: String?): String = when {
    opened == null -> t(R.string.wallet_key_check_fails, error ?: t(R.string.wallet_key_unknown_error))
    !opened.equals(expected, ignoreCase = true) -> t(R.string.wallet_key_check_other, shortAddress(opened))
    else -> t(R.string.wallet_key_check_opens, shortAddress(opened))
}

private enum class BackupStep { Password, Save, Check }

/**
 * The backup in three steps. [newAccount]: a new key ([makeBackup] = Mobile.newKey), given to [onUse] once checked;
 * else the phone's key ([makeBackup] = Mobile.exportKey). [makeBackup] runs off the main thread.
 */
@Composable
fun KeyBackupDialog(
    newAccount: Boolean,
    makeBackup: (String) -> String,
    onUse: (backup: String, password: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(BackupStep.Password) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backup by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var checkText by remember { mutableStateOf("") }
    var checkPassword by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { writeBackup(context, uri, backup) } }
            saved = result.isSuccess
            error = result.exceptionOrNull()?.let { t(R.string.wallet_key_not_saved, it.message.toString()) }
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readBackupFile(context, uri) } }
            result.onSuccess { checkText = it; checked = false; checkResult = null }
            error = result.exceptionOrNull()?.let { t(R.string.wallet_key_not_read, it.message.toString()) }
        }
    }

    val small = MaterialTheme.typography.bodySmall
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(t(if (newAccount) R.string.wallet_key_new_account else R.string.wallet_key_back_up)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.wallet_key_step, step.ordinal + 1, stepName(step)), style = MaterialTheme.typography.titleSmall)
                when (step) {
                    BackupStep.Password -> {
                        Text(
                            t(if (newAccount) R.string.wallet_key_protects_new else R.string.wallet_key_protects_phone),
                        )
                        PasswordField(password, t(R.string.wallet_key_password), shown) { password = it; error = null }
                        PasswordField(again, t(R.string.wallet_key_password_again), shown) { again = it; error = null }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                runCatching { Mobile.suggestPassword() }
                                    .onSuccess { password = it; again = it; shown = true; error = null }
                                    .onFailure { error = it.message }
                            }) { Text(t(R.string.wallet_key_suggest)) }
                            TextButton(onClick = { shown = !shown }) { Text(t(if (shown) R.string.wallet_key_hide else R.string.wallet_key_show)) }
                        }
                        Text(
                            t(R.string.wallet_key_write_down),
                            style = small,
                        )
                        if (password.isNotEmpty() && again.isNotEmpty()) passwordProblem(password, again)?.let { Text(it, style = small) }
                    }
                    BackupStep.Save -> {
                        Text(t(if (newAccount) R.string.wallet_key_your_account else R.string.wallet_key_phone_account))
                        AddressLine(address)
                        Text(
                            t(R.string.wallet_key_only_backup),
                            style = small,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill(t(R.string.wallet_key_save_file), enabled = !busy) { saver.launch(backupFileName(address)) }
                            if (saved) Text(t(R.string.wallet_key_saved), style = small)
                        }
                        TextButton(onClick = { copyBackup(context, backup); copied = true }) { Text(t(R.string.wallet_key_copy_text)) }
                        if (copied) Text(t(R.string.wallet_key_copied_hint), style = small)
                    }
                    BackupStep.Check -> {
                        Text(t(R.string.wallet_key_check_hint))
                        Pill(t(R.string.wallet_key_choose_file), enabled = !busy) { opener.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
                        OutlinedTextField(
                            value = checkText,
                            onValueChange = { checkText = it; checked = false; checkResult = null },
                            label = { Text(t(R.string.wallet_key_backup)) },
                            textStyle = small.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                        )
                        PasswordField(checkPassword, t(R.string.wallet_key_password), shown = false) { checkPassword = it; checked = false; checkResult = null }
                        checkResult?.let { Text(it) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = small) }
            }
        },
        confirmButton = {
            when (step) {
                BackupStep.Password -> TextButton(
                    enabled = !busy && passwordProblem(password, again) == null,
                    onClick = {
                        busy = true
                        val typed = password
                        scope.launch {
                            val made = withContext(Dispatchers.IO) {
                                runCatching { makeBackup(typed).let { it to Mobile.keyAddress(it, typed) } }
                            }
                            busy = false
                            made.onSuccess { (text, opened) ->
                                backup = text
                                address = opened
                                step = BackupStep.Save
                            }
                            error = made.exceptionOrNull()?.message
                        }
                    },
                ) { Text(t(R.string.common_next)) }
                BackupStep.Save -> TextButton(enabled = saved || copied, onClick = { step = BackupStep.Check; error = null }) { Text(t(R.string.common_next)) }
                BackupStep.Check -> if (!checked) {
                    TextButton(
                        enabled = !busy && checkText.isNotBlank() && checkPassword.isNotEmpty(),
                        onClick = {
                            val opened = runCatching { Mobile.keyAddress(checkText, checkPassword) }
                            checkResult = backupCheckText(address, opened.getOrNull(), opened.exceptionOrNull()?.message)
                            checked = opened.getOrNull()?.equals(address, ignoreCase = true) == true
                        },
                    ) { Text(t(R.string.wallet_key_check)) }
                } else if (newAccount) {
                    TextButton(onClick = { onUse(backup, password) }) { Text(t(R.string.wallet_key_use_account)) }
                } else {
                    TextButton(onClick = onClose) { Text(t(R.string.wallet_key_done)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(t(R.string.common_cancel)) } },
    )
}

private fun stepName(step: BackupStep): String = when (step) {
    BackupStep.Password -> t(R.string.wallet_key_password)
    BackupStep.Save -> t(R.string.wallet_key_step_save)
    BackupStep.Check -> t(R.string.wallet_key_step_check)
}

@Composable
private fun PasswordField(value: String, label: String, shown: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Writes [backup] to the file the user chose, as it is (no line break). */
private fun writeBackup(context: Context, uri: Uri, backup: String) {
    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(t(R.string.wallet_key_file_not_written))
    out.use { it.write(backup.toByteArray(Charsets.US_ASCII)) }
}

/** The text of the file the user chose (a backup, an exported key), at most [MAX_BACKUP_FILE] bytes. */
fun readBackupFile(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException(t(R.string.wallet_key_file_not_read))
    val bytes = input.use { stream ->
        // At most one byte more than allowed (InputStream.readNBytes needs Android 13).
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (out.size() <= MAX_BACKUP_FILE) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    if (bytes.size > MAX_BACKUP_FILE) throw IOException(t(R.string.wallet_key_file_too_large))
    return String(bytes, Charsets.UTF_8).trim()
}

/** Copies [backup], marked sensitive: Android 13+ then hides it in its clipboard preview. */
private fun copyBackup(context: Context, backup: String) {
    val clip = ClipData.newPlainText(t(R.string.wallet_key_clip_label), backup)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
