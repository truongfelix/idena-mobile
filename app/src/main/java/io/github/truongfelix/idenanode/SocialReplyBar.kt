package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The draft of a reply to [post] itself, the reply bar's target until a reply or comment is picked. */
fun replyDraft(post: SocialPost) = Draft(t(R.string.social_what_reply), t(R.string.social_what_reply), PostTarget.onPost(post.id), post.call)

/**
 * The reply bar under a thread, as the comments of the usual social apps: the thread stays on screen and scrolls
 * above it. The field answers [post] until 💬 Reply on a reply or comment points it there ("Replying to …", ✕ goes
 * back to the post); a tap in the field starts the reply. 🖼 opens the picture picker, and the picture joins the reply
 * here; the line naming it (or a long text) opens a panel over the thread ([ReplyOptionsSheet]: the picture's size,
 * text on IPFS); ➤ asks for the fees as the editor's Post does. Not shown under a post of an older contract (read only).
 */
@Composable
fun ReplyBar(model: SocialModel, post: SocialPost, canPost: Boolean) {
    val sending = model.sending
    val draft = sending.draft?.takeIf { it.target != PostTarget.NEW_POST }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // A reply or comment picked in the thread takes the keyboard; a draft closed (Back, a sent reply) gives it back.
    LaunchedEffect(draft?.target) {
        if (draft != null) runCatching { focus.requestFocus() } else focusManager.clearFocus()
    }
    val onPost = draft == null || draft.target == PostTarget.onPost(post.id)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val picker = rememberImagePicker(model)
    var options by remember { mutableStateOf(false) }
    if (options && draft != null) ReplyOptionsSheet(model, picker) { options = false }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (!onPost) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t(R.string.social_replying_to, displayName(draft!!.answering?.author.orEmpty(), model.names)),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SmallIconButton(Icons.Filled.Close, t(R.string.social_reply_to_post_instead), muted) { sending.draft = replyDraft(post) }
                    }
                }
                val extras = listOfNotNull(
                    sending.draftImage?.let { t(R.string.social_reply_image, it.chosen) },
                    t(R.string.social_text_on_ipfs).takeIf { sending.textOnIpfs },
                    t(R.string.social_long_text_question).takeIf { !sending.textOnIpfs && sending.draftText.length >= LONG_REPLY },
                )
                if (draft != null && extras.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            extras.joinToString(" · ") + " ›",
                            Modifier.weight(1f).clickable { options = true },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (sending.draftImage != null) SmallIconButton(Icons.Filled.Close, t(R.string.social_remove_image), muted) { sending.draftImage = null }
                    }
                }
                picker.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = sending.draftText,
                        onValueChange = { sending.draftText = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .onFocusChanged { if (it.isFocused && sending.draft == null) sending.draft = replyDraft(post) },
                        placeholder = { Text(t(R.string.social_reply_to, displayName(post.call.author, model.names)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    IconButton(
                        onClick = {
                            if (sending.draft == null) sending.draft = replyDraft(post)
                            picker.pick()
                        },
                        enabled = !picker.preparing,
                    ) { Text("🖼") }
                    FilledIconButton(
                        onClick = sending::prepareDraft,
                        enabled = canPost && draft != null && !picker.preparing && (sending.draftText.isNotBlank() || sending.draftImage != null),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = t(R.string.social_post_reply)) }
                }
            }
        }
    }
}

/** A reply this long offers its text on IPFS (cheaper than in the transaction for long texts). */
private const val LONG_REPLY = 280

/** The panel over the thread for the reply's picture (its sizes and cost, Remove) and its text on IPFS; Done closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplyOptionsSheet(model: SocialModel, picker: ImagePicker, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sending = model.sending
    val image = sending.draftImage
    LaunchedEffect(image != null) { if (image != null) picker.loadFee(context) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (image != null) DraftImageChoice(model, image, picker.feePerGas)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(R.string.social_long_text_on_ipfs), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = sending.textOnIpfs, onCheckedChange = { sending.textOnIpfs = it })
            }
            if (sending.textOnIpfs) TextOnIpfsNote()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (image != null) TextButton(onClick = { sending.draftImage = null }) { Text(t(R.string.social_remove_image_button)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text(t(R.string.social_done)) }
            }
        }
    }
}
