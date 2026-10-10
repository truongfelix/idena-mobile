package io.github.truongfelix.idenanode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The New ad form (or a draft's, [draftId]): title, description, link and one picture, from which the app makes the
 * thumbnail too ("Other thumbnail" picks another). Saved as a draft on the phone; checked when it goes to review.
 */
@Composable
fun AdEditorScreen(draftId: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { adStore(context) }
    var draft by remember { mutableStateOf<AdDraft?>(null) }
    var thumb by remember { mutableStateOf<ByteArray?>(null) }
    var media by remember { mutableStateOf<ByteArray?>(null) }
    var picturesChanged by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }
    LaunchedEffect(draftId) {
        val loaded = withContext(Dispatchers.IO) {
            draftId?.let { id -> runCatching { Triple(store.load(id), store.thumb(id), store.media(id)) }.getOrNull() }
        }
        draft = loaded?.first ?: AdDraft(AdDraftStore.newId(), created = System.currentTimeMillis())
        thumb = loaded?.second
        media = loaded?.third
    }
    val pictures = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        note = "Preparing the picture…"
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { adPictures(context, uri) } }
                .onSuccess { (big, small) -> media = big; thumb = small; picturesChanged = true; note = null }
                .onFailure { note = "Not read: ${it.message}" }
        }
    }
    val otherThumb = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { adThumb(context, uri) } }
                .onSuccess { thumb = it; picturesChanged = true; note = null }
                .onFailure { note = "Not read: ${it.message}" }
        }
    }
    val current = draft
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle(if (draftId == null) "New ad" else "Edit ad", onClose) {
            TextButton(onClick = { previewing = true }, enabled = current != null) { Text("Preview") }
        }
        if (current == null) {
            CircularProgressIndicator()
            return@Column
        }
        CountedField("Title", current.title, AD_TITLE_MAX) { draft = current.copy(title = it) }
        CountedField("Description", current.desc, AD_DESC_MAX, singleLine = false) { draft = current.copy(desc = it) }
        OutlinedTextField(
            value = current.url,
            onValueChange = { draft = current.copy(url = it.trim()) },
            label = { Text("Link") },
            placeholder = { Text("https://") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Picture", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AdImage(media, maxSide = AD_MEDIA_SIZE, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Fit)
            AdImage(thumb, maxSide = 160, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Text(
                "The app makes the thumbnail (${AD_THUMB_SIZE}×$AD_THUMB_SIZE) and the picture (${AD_MEDIA_SIZE}×$AD_MEDIA_SIZE) from it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (media == null) "Choose a picture" else "Other picture", enabled = note != "Preparing the picture…") {
                pictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (media != null) {
                Pill("Other thumbnail", enabled = true) { otherThumb.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val saved = current.copy(title = current.title.trim(), desc = current.desc.trim())
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { store.save(saved, thumb.takeIf { picturesChanged }, media.takeIf { picturesChanged }) }
                    }
                    result.onSuccess { onClose() }.onFailure { note = "Not saved: ${it.message}" }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save the draft") }
        Text(
            "The draft stays on this phone. Once it goes to review it can no longer be changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
    if (previewing && current != null) {
        AdContentPreview(AdContent(current.title.trim(), current.desc.trim(), current.url.trim(), thumb, media)) { previewing = false }
    }
}

/** A text field with the characters left of [max], as the desktop's form; the field takes no more. */
@Composable
private fun CountedField(label: String, value: String, max: Int, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(max)) },
        label = { Text(label) },
        supportingText = { Text("${max - value.length}") },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}
