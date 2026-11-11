package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The full-screen editor of a post, reply or comment: the answered message in full above a text box of
 * a few lines, then an image (stored on IPFS, at a size chosen with its cost) and whether the text goes
 * on IPFS; the screen scrolls. The draft stays in [model] when the tab changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Editor(model: SocialModel, draft: Draft, canPost: Boolean) {
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val picker = rememberImagePicker(model)
    val imageNote = picker.note
    val feePerGas = picker.feePerGas
    LaunchedEffect(Unit) { focus.requestFocus() }
    val voice = rememberVoiceNote(model)
    val image = model.sending.draftImage
    // A picture chosen in the reply bar: its sizes' prices.
    LaunchedEffect(image != null) { if (image != null) picker.loadFee(context) }
    val text = model.sending.draftText
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.sending.draft = null }) { Text(t(R.string.common_cancel)) }
            Spacer(Modifier.weight(1f))
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = model.sending::prepareDraft, enabled = canPost && (text.isNotBlank() || image != null) && imageNote == null && !voice.recording) { Text(t(R.string.social_post_button)) }
        }
        draft.answering?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${displayName(it.author, model.names)}  ·  ${ago(it.time)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PostContent(it, MaterialTheme.typography.bodyLarge, selectable = true, inThread = true)
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { model.sending.draftText = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(if (draft.answering == null) t(R.string.social_editor_whats_new) else t(R.string.social_editor_your_answer)) },
            minLines = 4,
            maxLines = 10,
        )
        if (draft.target == PostTarget.NEW_POST) {
            TopicField(model.sending.draftTopic, model.feed) { model.sending.draftTopic = it }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val idle = !picker.preparing && !voice.recording
            Pill(if (image == null) t(R.string.social_editor_image) else t(R.string.social_editor_other_image), enabled = idle, onClick = picker::pick)
            Pill(t(R.string.social_editor_audio), enabled = idle, onClick = picker::pickAudio)
            if (voice.recording) Pill(voice.stopLabel, enabled = true, onClick = voice::stop)
            // One media per post: Record once the draft's picture or file is removed.
            else Pill(t(R.string.social_editor_record), enabled = !picker.preparing && image == null, onClick = voice::record)
            FilterChip(selected = model.sending.textOnIpfs, onClick = { model.sending.textOnIpfs = !model.sending.textOnIpfs }, label = { Text(t(R.string.social_text_on_ipfs)) })
        }
        model.sending.sendStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        imageNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        voice.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (image != null) {
            DraftImageChoice(model, image, feePerGas)
            Pill(t(R.string.social_remove_image_button), enabled = true) { model.sending.draftImage = null }
        }
        if (model.sending.textOnIpfs) TextOnIpfsNote()
    }
}

/** The draft's picture as it will be posted, its sizes (each with its weight) and what storing it costs. */
@Composable
fun DraftImageChoice(model: SocialModel, image: ImageDraft, feePerGas: BigDecimal?) {
    val cost = feePerGas?.let { t(R.string.social_image_cost, fee(storeToIpfsFeeEstimate(it, image.bytes.size))) } ?: t(R.string.social_image_cost_unknown)
    if (image.isAsIs) {
        DraftFile(image)
        Text(t(R.string.social_media_as_is_note, cost, kilobytes(image.bytes.size)), style = MaterialTheme.typography.bodySmall)
        return
    }
    DraftImage(image.bytes)
    image.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        image.sizes.forEach { (side, bytes) ->
            FilterChip(
                selected = side == image.chosen,
                onClick = { model.sending.draftImage = image.copy(chosen = side) },
                label = { Text("$side px · ${kilobytes(bytes.size)}") },
            )
        }
    }
    Text(
        t(R.string.social_image_note, cost),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** What "Text on IPFS" does. */
@Composable
fun TextOnIpfsNote() {
    Text(
        t(R.string.social_text_on_ipfs_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The picture picker of a draft (the editor's and the reply bar's): the picture chosen is encoded at its sizes into
 * the draft ([SocialSending.draftImage]); [note] says it is being prepared or why it was refused; [feePerGas] prices
 * the sizes.
 */
class ImagePicker(private val launch: () -> Unit, private val launchAudio: () -> Unit) {
    var note by mutableStateOf<String?>(null)
    var feePerGas by mutableStateOf<BigDecimal?>(null)

    val preparing: Boolean
        get() = note == PREPARING_IMAGE

    fun pick() = launch()

    /** A sound file, from the phone's files. */
    fun pickAudio() = launchAudio()

    /** Asks the node for [feePerGas] once (none when it does not answer). */
    suspend fun loadFee(context: Context) {
        if (feePerGas == null) feePerGas = withContext(Dispatchers.IO) { runCatching { SocialPosting(rpc(context)).feePerGas() }.getOrNull() }
    }
}

private val PREPARING_IMAGE: String
    get() = t(R.string.social_preparing_image)

@Composable
fun rememberImagePicker(model: SocialModel): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    lateinit var picker: ImagePicker
    fun picked(uri: Uri?, audio: Boolean) {
        if (uri == null) return
        picker.note = PREPARING_IMAGE
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { pickMedia(context, uri, audio) } }
                .onSuccess {
                    model.sending.draftImage = it
                    picker.note = null
                }
                .onFailure { picker.note = t(R.string.social_image_unusable, "${it.message}") }
            picker.loadFee(context)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it, audio = false) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked(it, audio = true) }
    picker = remember {
        ImagePicker(
            { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            { audioLauncher.launch("audio/*") },
        )
    }
    return picker
}

/**
 * A voice note for the draft (B-223), in the post editor and the reply bar: [record] asks for the microphone the first
 * time, then records; [stop] makes it the draft's sound. [note] says why it failed or that it stopped at its limit.
 */
class VoiceNote(private val context: Context, private val recorder: VoiceRecorder, private val askMicrophone: () -> Unit) {
    var note by mutableStateOf<String?>(null)
    var elapsed by mutableIntStateOf(0)

    val recording: Boolean
        get() = recorder.recording

    val startedAt: Long
        get() = recorder.startedAt

    /** The Stop button's text, with the time recorded. */
    val stopLabel: String
        get() = t(R.string.social_editor_stop, playTime(elapsed))

    fun record() {
        note = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recorder.start()
        else askMicrophone()
    }

    fun stop() = recorder.stop()

    fun cancel() = recorder.cancel()

    fun start() = recorder.start()
}

/** The draft's [VoiceNote], stopped without a sound when the screen holding it leaves. */
@Composable
fun rememberVoiceNote(model: SocialModel): VoiceNote {
    val context = LocalContext.current
    lateinit var voice: VoiceNote
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else voice.note = t(R.string.social_record_refused)
    }
    voice = remember {
        lateinit var made: VoiceNote
        val recorder = VoiceRecorder(context) { result, limited ->
            result.onSuccess {
                model.sending.draftImage = ImageDraft.asIs(it, VOICE_TYPE)
                made.note = if (limited) t(R.string.social_record_limit, it.size / 1000) else null
            }.onFailure { made.note = t(R.string.social_record_failed, "${it.message}") }
        }
        made = VoiceNote(context, recorder) { microphone.launch(Manifest.permission.RECORD_AUDIO) }
        made
    }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    LaunchedEffect(voice.recording) {
        while (voice.recording) {
            voice.elapsed = (SystemClock.elapsedRealtime() - voice.startedAt).toInt()
            delay(500)
        }
    }
    return voice
}

/** The draft's file sent as it is: a moving picture moving, a video or a sound in its player. */
@Composable
private fun DraftFile(image: ImageDraft) {
    when (val kind = kindOfType(image.type)) {
        MediaKind.VIDEO, MediaKind.AUDIO -> {
            val key = remember(image.bytes) { "draft" + image.bytes.contentHashCode().toUInt() }
            rememberMediaFile(key, image.bytes)?.let { MediaPlayerView(it, kind == MediaKind.VIDEO, 300.dp) }
        }
        else -> {
            val key = remember(image.bytes) { "draft" + image.bytes.contentHashCode() }
            AnimatedImage(key, image.bytes, 300.dp) { DraftImage(image.bytes) }
        }
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
            contentDescription = t(R.string.social_draft_image_description),
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}
