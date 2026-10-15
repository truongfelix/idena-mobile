package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The full-screen editor of a post, reply or comment: the answered message in full above a text box of
 * a few lines, then an image (stored on IPFS, at a size chosen with its cost) and whether the text goes
 * on IPFS; the screen scrolls. The draft stays in [model] when the tab changes.
 */
@Composable
fun Editor(model: SocialModel, draft: Draft, canPost: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The image being prepared or why it was refused, and the fee per gas for the size choice.
    var imageNote by remember { mutableStateOf<String?>(null) }
    var feePerGas by remember { mutableStateOf<BigDecimal?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageNote = "Preparing the image…"
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { encodeImageSizes(context, uri) } }
                .onSuccess { sizes ->
                    model.sending.draftImage = ImageDraft(sizes, if (DEFAULT_IMAGE_SIZE in sizes) DEFAULT_IMAGE_SIZE else sizes.keys.max())
                    imageNote = null
                }
                .onFailure { imageNote = "This image cannot be used: ${it.message}" }
            if (feePerGas == null) feePerGas = withContext(Dispatchers.IO) { runCatching { SocialPosting(rpc(context)).feePerGas() }.getOrNull() }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val image = model.sending.draftImage
    val text = model.sending.draftText
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.sending.draft = null }) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = model.sending::prepareDraft, enabled = canPost && (text.isNotBlank() || image != null) && imageNote == null) { Text("Post") }
        }
        draft.answering?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${displayName(it.author, model.names)}  ·  ${ago(it.time)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PostContent(it, MaterialTheme.typography.bodyLarge, selectable = true)
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { model.sending.draftText = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(if (draft.answering == null) "What's new?" else "Your answer") },
            minLines = 4,
            maxLines = 10,
        )
        if (draft.target == PostTarget.NEW_POST) {
            TopicField(model.sending.draftTopic, model.feed) { model.sending.draftTopic = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (image == null) "🖼 Image" else "🖼 Other image", enabled = imageNote != "Preparing the image…") {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            FilterChip(selected = model.sending.textOnIpfs, onClick = { model.sending.textOnIpfs = !model.sending.textOnIpfs }, label = { Text("Text on IPFS") })
        }
        model.sending.sendStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        imageNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (image != null) {
            DraftImage(image.bytes)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                image.sizes.forEach { (side, bytes) ->
                    FilterChip(
                        selected = side == image.chosen,
                        onClick = { model.sending.draftImage = image.copy(chosen = side) },
                        label = { Text("$side px · ${kilobytes(bytes.size)}") },
                    )
                }
            }
            val cost = feePerGas?.let { "about ${fee(storeToIpfsFeeEstimate(it, image.bytes.size))} iDNA" } ?: "a fee that grows with its size"
            Text(
                "The image is stored on IPFS (a storeToIpfs transaction): $cost, on top of the post's fee. " +
                    "It is sent as WebP, without the photo's details (place, camera).",
                style = MaterialTheme.typography.bodySmall,
            )
            Pill("✕ Remove the image", enabled = true) { model.sending.draftImage = null }
        }
        if (model.sending.textOnIpfs) {
            Text(
                "The text is stored on IPFS (a storeToIpfs transaction, cheaper than the post for long texts) and the " +
                    "post holds its address. It stays readable while IPFS nodes keep it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Posts are public and stay on the blockchain for good. The fees are shown before sending.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The image of the draft as it will be posted (decoded from the encoded bytes, so its quality shows). */
@Composable
private fun DraftImage(bytes: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "The image of the post",
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}
