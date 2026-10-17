package io.github.truongfelix.idenanode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor asks the app to send: a name change, the name dropped, or the bio and picture. */
sealed interface ProfileEdit {
    data class Name(val name: String) : ProfileEdit
    data object DropName : ProfileEdit

    /** The bio, and the picture: [newPicture] (a WebP to store first), else [keptPicture] ("" removes it). */
    class Profile(val bio: String, val newPicture: ByteArray?, val keptPicture: String) : ProfileEdit
}

/**
 * The account's public name, bio and picture in the profiles contract, as the desktop's editor: each change is a call
 * of its own, shown with its fees before it is sent ([onSave]): Save the name (claimName), Drop the name (dropName),
 * Save the profile (setProfile, the picture stored on IPFS first). [profile] is what the contract holds now.
 */
@Composable
fun ProfileEditDialog(
    address: String,
    profile: ContractProfile?,
    onSave: (ProfileEdit) -> Unit,
    onDismiss: () -> Unit,
    waitingMethod: (String) -> Boolean = { false },
) {
    // A name change or a profile saved before waits for its block: not sent twice (B-81).
    val nameWaits = waitingMethod("claimName") || waitingMethod("dropName")
    val profileWaits = waitingMethod("setProfile")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var bio by remember { mutableStateOf(profile?.bio.orEmpty()) }
    var newPicture by remember { mutableStateOf<ByteArray?>(null) }
    var removed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        note = t(R.string.profile_preparing_picture)
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { encodePicture(context, uri) } }
                .onSuccess { newPicture = it; removed = false; note = null }
                .onFailure { note = t(R.string.profile_not_read, "${it.message}") }
        }
    }
    val oldPicture = profile?.picture.orEmpty()
    val nameValid = PROFILE_NAME.matches(name)
    val profileChanged = bio != profile?.bio.orEmpty() || newPicture != null || (removed && oldPicture.isNotEmpty())
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.profile_public_profile)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    t(R.string.profile_public_note),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(20) },
                    label = { Text(t(R.string.profile_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    t(R.string.profile_name_rules),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(ProfileEdit.Name(name)) }, enabled = nameValid && name != profile?.name && !nameWaits) { Text(t(R.string.profile_save_name)) }
                    if (profile?.name != null) TextButton(onClick = { onSave(ProfileEdit.DropName) }, enabled = !nameWaits) { Text(t(R.string.profile_drop_name)) }
                }
                if (nameWaits) Text(t(R.string.profile_name_waits), style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(MAX_BIO) },
                    label = { Text(t(R.string.profile_bio)) },
                    supportingText = { Text("${bio.length} / $MAX_BIO") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(t(R.string.profile_picture_heading), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chosen = newPicture
                    when {
                        chosen != null -> remember(chosen) { decodeScaled(chosen, PICTURE_SIDE) }?.let {
                            Image(it, t(R.string.profile_new_picture), Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)))
                        }
                        removed -> Avatar(address, 56.dp)
                        else -> ProfilePicture(address, ipfsCid(oldPicture), 56.dp)
                    }
                    OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(t(R.string.profile_choose)) }
                    if (chosen != null || (oldPicture.isNotEmpty() && !removed)) TextButton(onClick = { newPicture = null; removed = true }) { Text(t(R.string.common_remove)) }
                }
                Text(
                    t(R.string.profile_picture_rules, PICTURE_SIDE, MAX_PICTURE_BYTES / 1000),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ProfileEdit.Profile(bio.trim(), newPicture, if (removed) "" else oldPicture)) }, enabled = profileChanged && !profileWaits) {
                Text(t(R.string.profile_save_profile))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}

/** Sends an [edit] of the editor through [sending], each with its fees shown first. */
fun sendProfileEdit(sending: SocialSending, edit: ProfileEdit) = when (edit) {
    is ProfileEdit.Name -> sending.prepareProfile(t(R.string.profile_claim_what, edit.name), t(R.string.profile_claim_detail, edit.name)) { ProfileCalls.claimName(edit.name) }
    ProfileEdit.DropName -> sending.prepareProfile(t(R.string.profile_drop_name), t(R.string.profile_drop_detail)) { ProfileCalls.dropName() }
    is ProfileEdit.Profile -> sending.prepareProfile(t(R.string.profile_title), t(R.string.profile_save_detail), edit.newPicture) { stored ->
        ProfileCalls.setProfile(edit.bio, stored ?: edit.keptPicture)
    }
}
