package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Making a flip with the node of the app ([FlipMaking.kt]): the words, the four frames (camera, gallery,
 * or the opt-in image search) with the anti-AI frame, the shuffled order, then the submission.
 */
class FlipActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    // The list of the epoch's flips; "Make a flip" opens the making steps, which come back to it.
                    var making by rememberSaveable { mutableStateOf(false) }
                    if (making) {
                        FlipScreen(onClose = { making = false }, onSent = { making = false }, modifier = Modifier.padding(padding))
                    } else {
                        MyFlipsScreen(onMake = { making = true }, onClose = { finish() }, modifier = Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

private enum class FlipStep(@StringRes val title: Int) {
    Words(R.string.flip_step_words), Frames(R.string.flip_step_pictures), Shuffle(R.string.flip_step_shuffle), Submit(R.string.flip_step_submit),
}

/**
 * What the node says of the identity's flips: the counts, the keyword pairs, when flips stop, the epoch
 * and whether the node is synced (flips are sent only then).
 */
private class FlipNodeState(val quota: FlipQuota, val pairs: List<KeywordPair>, val deadline: Instant?, val epoch: Int, val synced: Boolean)

private fun readFlipState(context: Context): FlipNodeState {
    val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
    val identity = rpc.identity()
    val quota = FlipQuota(
        identity.optString("state"),
        identity.optInt("requiredFlips"),
        identity.optInt("availableFlips"),
        identity.optInt("madeFlips"),
    )
    return FlipNodeState(
        quota,
        keywordPairs(identity),
        runCatching { rpc.flipDeadline() }.getOrNull(),
        rpc.ceremony().epoch,
        runCatching { rpc.synced() }.getOrDefault(false),
    )
}

@Composable
private fun FlipScreen(onClose: () -> Unit, onSent: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keywords = remember { loadKeywords(context) }
    var node by remember { mutableStateOf<Result<FlipNodeState>?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reads) { node = withContext(Dispatchers.IO) { runCatching { readFlipState(context) } } }
    // The draft of the keyword pair shown, opened once the node gave the pairs ([FlipDrafts]).
    var draft by remember { mutableStateOf<FlipDraft?>(null) }
    var step by remember { mutableStateOf(FlipStep.Words) }
    // The protected frames (story order) for the shuffle and the submission, made when leaving the pictures,
    // and the fingerprint of the pictures they were made from: they are sent only for those pictures.
    var protected by remember { mutableStateOf<List<ByteArray>?>(null) }
    var protectedFor by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // The flip sent last while the node does not count it yet ([SentFlip]); one send at a time.
    var sent by remember { mutableStateOf(FlipDraftStore.sent(context)) }
    var sending by remember { mutableStateOf(false) }

    fun update(next: FlipDraft) {
        draft = next
        val ticket = FlipDraftStore.order(next.words).next()
        scope.launch(Dispatchers.IO) { runCatching { FlipDraftStore.save(context, next, ticket) } }
    }

    // Until the node counts the flip sent, it is asked every 10 s ([checkSent]): a flip from another epoch,
    // or not counted after a long time, is forgotten (and may be made again).
    LaunchedEffect(sent, node) {
        val waiting = sent ?: return@LaunchedEffect
        val read = node?.getOrNull() ?: return@LaunchedEffect
        val check = checkSent(waiting, read.epoch, read.pairs, System.currentTimeMillis())
        if (check != SentFlipCheck.Wait) {
            withContext(Dispatchers.IO) { FlipDraftStore.setSent(context, null) }
            sent = null
            if (check == SentFlipCheck.TimedOut) {
                message = t(R.string.flip_not_counted, dateFormat.format(Instant.ofEpochMilli(waiting.sentAt)))
            }
            return@LaunchedEffect
        }
        val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
        while (true) {
            kotlinx.coroutines.delay(10_000)
            // Once in a block the node counts it: the identity is read again, which runs this check again.
            val mined = withContext(Dispatchers.IO) { runCatching { rpc.isMined(waiting.txHash) }.getOrDefault(false) }
            if (mined || System.currentTimeMillis() - waiting.sentAt > SENT_FLIP_TIMEOUT_MS) {
                reads++
                return@LaunchedEffect
            }
        }
    }

    BackHandler {
        if (step == FlipStep.Words) onClose() else step = FlipStep.entries[step.ordinal - 1]
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(t(R.string.common_close)) }
            Spacer(Modifier.weight(1f))
            Text(t(R.string.flip_make_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        val state = node
        when {
            state == null -> { CircularProgressIndicator(); return@Column }
            state.isFailure -> {
                Text(t(R.string.flip_node_no_answer, state.exceptionOrNull()?.message.toString()))
                Button(onClick = { reads++ }) { Text(t(R.string.common_try_again)) }
                return@Column
            }
        }
        val read = state!!.getOrThrow()
        // The flip sent counts at once, before the node counts it (see [SentFlip]).
        val (quota, pairs) = countSent(read.quota, read.pairs, sent, read.epoch)
        val nodeState = FlipNodeState(quota, pairs, read.deadline, read.epoch, read.synced)
        QuotaLine(nodeState)
        sent?.takeIf { checkSent(it, read.epoch, read.pairs, System.currentTimeMillis()) == SentFlipCheck.Wait }?.let {
            Text(t(R.string.flip_last_waits, it.hash.take(12)), style = MaterialTheme.typography.bodySmall)
        }
        if (!nodeState.quota.canMake) {
            Text(
                if (nodeState.quota.available == 0) t(R.string.flip_none_to_make)
                else t(R.string.flip_all_made),
                style = MaterialTheme.typography.bodyLarge,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            return@Column
        }
        val free = nodeState.pairs.filter { !it.used }
        // Opened when there is none yet, or when its pair was used meanwhile (by a flip sent elsewhere): the draft of
        // the pair worked on last, else of the first free pair. A draft's pictures stay with its pair.
        val current = draft?.takeIf { d -> free.any { it.words == d.words } }
        if (current == null) {
            LaunchedEffect(free) { draft = withContext(Dispatchers.IO) { FlipDraftStore.open(context, free) } }
            if (free.isEmpty()) Text(t(R.string.flip_no_free_pair)) else CircularProgressIndicator()
            return@Column
        }
        val pair = free.first { it.words == current.words }
        val words = pairWords(pair, keywords)
        StepRow(step)
        HorizontalDivider()
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (!nodeState.synced && step == FlipStep.Submit) {
            Text(t(R.string.flip_not_synced), color = MaterialTheme.colorScheme.error)
            // Reads the node again until it is synced.
            LaunchedEffect(read) {
                kotlinx.coroutines.delay(10_000)
                reads++
            }
        }
        Box(Modifier.weight(1f)) {
            when (step) {
                FlipStep.Words -> WordsStep(words, otherPairs = free.size - 1, onOther = {
                    val next = free[(free.indexOf(pair) + 1) % free.size]
                    scope.launch { draft = withContext(Dispatchers.IO) { FlipDraftStore.switchTo(context, next) } }
                }, onNext = { step = FlipStep.Frames })
                FlipStep.Frames -> FramesStep(current, words, ::update, onNext = {
                    working = t(R.string.flip_protecting)
                    val fingerprint = draftFingerprint(current.frames, current.adversarial)
                    scope.launch {
                        protectedFor = null
                        protected = withContext(Dispatchers.Default) {
                            runCatching {
                                current.frames.map { bytes ->
                                    val pixels = framePixels(bytes!!) ?: error(t(R.string.flip_picture_unreadable))
                                    flipFrameJpeg(protectFrame(pixels, WORK_WIDTH, WORK_HEIGHT, kotlin.random.Random(java.security.SecureRandom().nextLong())))
                                }
                            }.onFailure { message = t(R.string.flip_prepare_failed, it.message.toString()) }.getOrNull()
                        }
                        working = null
                        if (protected != null) {
                            protectedFor = fingerprint
                            if (current.shuffled == null || !isValidShuffle(current.shuffled, current.adversarial)) {
                                update(current.copy(shuffled = randomShuffle(current.adversarial)))
                            }
                            step = FlipStep.Shuffle
                        }
                    }
                })
                FlipStep.Shuffle, FlipStep.Submit -> {
                    // Pictures changed since they were protected (or never protected): back to the pictures.
                    if (protected == null || protectedFor != draftFingerprint(current.frames, current.adversarial)) {
                        LaunchedEffect(Unit) { step = FlipStep.Frames }
                        return@Box
                    }
                    if (step == FlipStep.Shuffle) {
                        ShuffleStep(current, protected.orEmpty(), ::update, onNext = { step = FlipStep.Submit })
                        return@Box
                    }
                    SubmitStep(current, words, protected.orEmpty(), nodeState.deadline, enabled = !sending && sent == null && nodeState.synced) {
                    val frames = protected ?: return@SubmitStep
                    val shuffled = current.shuffled?.takeIf { isValidShuffle(it, current.adversarial) } ?: return@SubmitStep
                    // One send at a time, and none while the last one is not counted.
                    if (sending || sent != null || !nodeState.synced || !nodeState.quota.canMake || pairs.none { it.id == current.pairId && !it.used }) return@SubmitStep
                    sending = true
                    working = t(R.string.flip_sending)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val hex = encodeFlip(frames, shuffled, kotlin.random.Random(java.security.SecureRandom().nextLong()))
                                check(hex.size <= MAX_FLIP_HEX) { t(R.string.flip_too_large) }
                                val sentFlip = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).submitFlip(hex, current.pairId, nodeState.epoch)
                                // The copy the list of flips shows (the node cannot show it); the flip is sent anyway.
                                runCatching {
                                    val made = MadeFlip(sentFlip.hash, sentFlip.txHash, current.pairId, pair.words, shuffled, nodeState.epoch, sentFlip.sentAt)
                                    MadeFlipStore.add(context, made, frames)
                                }
                                sentFlip
                            }
                        }
                        working = null
                        result.onSuccess { flip ->
                            // Kept before anything else: the flip counts from now on, even if the app stops.
                            FlipDraftStore.setSent(context, flip)
                            sent = flip
                            FlipDraftStore.clear(context, current.words)
                            draft = null
                            protected = null
                            protectedFor = null
                            step = FlipStep.Words
                            message = t(R.string.flip_submitted, flip.hash.take(12))
                            onSent()
                            reads++
                        }.onFailure { message = t(R.string.flip_refused, flipRefusal(it.message.orEmpty())) }
                        sending = false
                    }
                    }
                }
            }
        }
    }

    working?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(it) },
            text = { Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() } },
            confirmButton = {},
        )
    }
}

internal val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** "1 of 3 made · send before 9 Oct, 15:00", and what missing flips cost. */
@Composable
private fun QuotaLine(state: FlipNodeState) {
    val q = state.quota
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val made = if (q.available > q.required) t(R.string.flip_quota_up_to, q.made, q.required, q.available) else t(R.string.flip_quota, q.made, q.required)
        Text(made + (state.deadline?.let { t(R.string.flip_quota_deadline, dateFormat.format(it)) } ?: ""))
        if (q.missing > 0) {
            Text(
                tp(R.plurals.flip_missing_warning, q.missing, q.missing, q.missingOutcomeLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepRow(step: FlipStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FlipStep.entries.forEach {
            Text(
                "${it.ordinal + 1}. ${t(it.title)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (it == step) FontWeight.Bold else FontWeight.Normal,
                color = if (it == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordsStep(words: List<Keyword>, otherPairs: Int, onOther: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t(R.string.flip_words_intro), style = MaterialTheme.typography.bodyMedium)
        words.forEach {
            Column {
                Text(it.name.uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (it.description.isNotEmpty()) Text(it.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            t(R.string.flip_words_rules),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (otherPairs > 0) OutlinedButton(onClick = onOther) { Text(t(R.string.flip_other_words)) }
            Button(onClick = onNext) { Text(t(R.string.common_next)) }
        }
    }
}

/** A picture's bytes decoded for the screen. */
@Composable
private fun rememberBitmap(bytes: ByteArray?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = if (bytes == null) null else withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    return bitmap
}

@Composable
internal fun FrameImage(bytes: ByteArray?, modifier: Modifier, selected: Boolean = false, label: String? = null) {
    val bitmap = rememberBitmap(bytes)
    Box(
        modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        label?.let {
            Text(
                it,
                Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A picture (camera, gallery or search) as a frame: work size, JPEG. */
private fun frameJpeg(bytes: ByteArray): ByteArray {
    val pixels = framePixels(bytes) ?: throw java.io.IOException(t(R.string.flip_android_cannot_read))
    return jpeg(pixels, WORK_WIDTH, WORK_HEIGHT)
}

/** The anti-AI frame of [draft] from four of its sources (at random), or from its story pictures without sources. */
private fun makeAdversarial(draft: FlipDraft): ByteArray? {
    val story = draft.frames.filterIndexed { i, f -> i != draft.adversarial && f != null }.map { it!! }
    val pool = draft.sources.ifEmpty { story }
    if (pool.isEmpty()) return null
    val random = kotlin.random.Random(java.security.SecureRandom().nextLong())
    val chosen = pool.shuffled(random).let { list -> List(FLIP_FRAMES) { list[it % list.size] } }
    val pixels = chosen.map { framePixels(it) ?: return null }
    // A picture used twice is mirrored the second time, so that the four differ.
    val four = pixels.mapIndexed { i, p -> if (i >= pool.size) mirrored(p, WORK_WIDTH) else p }
    return jpeg(adversarialFrame(four, WORK_WIDTH, WORK_HEIGHT, random), WORK_WIDTH, WORK_HEIGHT)
}

@Composable
private fun FramesStep(draft: FlipDraft, words: List<Keyword>, update: (FlipDraft) -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    // The draft as it is when a picture is ready (it may have changed while the picture was prepared).
    val latest by rememberUpdatedState(draft)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(draft.frames.indexOfFirst { it == null }.let { if (it < 0 || it == draft.adversarial) (draft.adversarial + 1) % FLIP_FRAMES else it }) }
    var note by remember { mutableStateOf<String?>(null) }
    var searchOn by remember { mutableStateOf(FlipDraftStore.searchOn(context)) }
    var askSearch by remember { mutableStateOf(false) }

    fun setFrame(slot: Int, bytes: ByteArray) {
        note = t(R.string.flip_preparing_picture)
        scope.launch {
            val frame = withContext(Dispatchers.Default) { runCatching { frameJpeg(bytes) } }
            frame.onSuccess { f ->
                var next = latest.copy(frames = latest.frames.toMutableList().also { it[slot] = f })
                // Without search pictures, the anti-AI frame follows the story pictures.
                if (next.sources.isEmpty()) next = next.copy(frames = next.frames.toMutableList().also { it[next.adversarial] = null })
                update(next)
                note = null
                if (selected == slot) selected = (0 until FLIP_FRAMES).firstOrNull { it != next.adversarial && next.frames[it] == null } ?: slot
            }.onFailure { note = it.message }
        }
    }

    val cameraFile = remember { File(context.cacheDir, "camera/flip.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) runCatching { cameraFile.readBytes() }.onSuccess { setFrame(selected, it) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { readImage(context, uri) } }.onSuccess { setFrame(selected, it) }.onFailure { note = it.message }
        }
    }

    // The anti-AI frame is made once the story pictures (or search pictures) are there.
    LaunchedEffect(draft.frames, draft.sources, draft.adversarial) {
        val storyDone = (0 until FLIP_FRAMES).all { it == draft.adversarial || draft.frames[it] != null }
        if (draft.frames[draft.adversarial] == null && (storyDone || draft.sources.size >= FLIP_FRAMES)) {
            val made = withContext(Dispatchers.Default) { runCatching { makeAdversarial(draft) }.getOrNull() }
            val now = latest
            if (made != null && now.adversarial == draft.adversarial && now.frames[now.adversarial] == null) {
                update(now.copy(frames = now.frames.toMutableList().also { it[now.adversarial] = made }))
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t(R.string.flip_story_in_order), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until FLIP_FRAMES).forEach { slot ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FrameImage(
                        draft.frames[slot],
                        Modifier.fillMaxWidth().clickable { selected = slot },
                        selected = slot == selected,
                        label = if (slot == draft.adversarial) t(R.string.flip_anti_ai) else "${slot + 1}",
                    )
                    Row {
                        TextButton(onClick = { update(moveFrame(draft, slot, -1)); if (selected == slot) selected = slot - 1 }, enabled = slot > 0) { Text("◀") }
                        TextButton(onClick = { update(moveFrame(draft, slot, 1)); if (selected == slot) selected = slot + 1 }, enabled = slot < FLIP_FRAMES - 1) { Text("▶") }
                    }
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (selected == draft.adversarial) {
            Text(
                if (draft.sources.isEmpty()) t(R.string.flip_adversarial_from_story) else t(R.string.flip_adversarial_from_search, draft.sources.size),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { update(draft.copy(frames = draft.frames.toMutableList().also { it[draft.adversarial] = null })) }) {
                Text(t(R.string.flip_new_anti_ai))
            }
        } else {
            Text(t(R.string.flip_picture_n, selected + 1), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    cameraFile.parentFile?.mkdirs()
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile))
                }) { Text(t(R.string.flip_camera)) }
                OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(t(R.string.flip_gallery)) }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t(R.string.flip_image_search), style = MaterialTheme.typography.titleSmall)
                Text(t(R.string.flip_image_search_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = searchOn, onCheckedChange = { on ->
                if (on) askSearch = true else { searchOn = false; FlipDraftStore.setSearchOn(context, false) }
            })
        }
        if (searchOn) {
            SearchPanel(words, onPick = { bytes ->
                if (selected != draft.adversarial) setFrame(selected, bytes)
            }, onSources = { sources ->
                update(latest.copy(sources = sources.take(8), frames = latest.frames.toMutableList().also { it[latest.adversarial] = null }))
            })
        }
        Button(onClick = onNext, enabled = draft.complete, modifier = Modifier.fillMaxWidth()) {
            Text(if (draft.complete) t(R.string.flip_next_protect) else t(R.string.flip_choose_pictures, FLIP_FRAMES - 1))
        }
    }

    if (askSearch) {
        AlertDialog(
            onDismissRequest = { askSearch = false },
            title = { Text(t(R.string.flip_search_ask_title)) },
            text = {
                Text(t(R.string.flip_search_ask_text))
            },
            confirmButton = {
                TextButton(onClick = { askSearch = false; searchOn = true; FlipDraftStore.setSearchOn(context, true) }) { Text(t(R.string.flip_turn_on)) }
            },
            dismissButton = { TextButton(onClick = { askSearch = false }) { Text(t(R.string.common_cancel)) } },
        )
    }
}

/** [draft] with the frame at [slot] moved one place by [by] (the anti-AI mark moves with its frame). */
private fun moveFrame(draft: FlipDraft, slot: Int, by: Int): FlipDraft {
    val to = slot + by
    if (to !in 0 until FLIP_FRAMES) return draft
    val frames = draft.frames.toMutableList()
    frames[slot] = draft.frames[to].also { frames[to] = draft.frames[slot] }
    val adversarial = when (draft.adversarial) {
        slot -> to
        to -> slot
        else -> draft.adversarial
    }
    return draft.copy(frames = frames, adversarial = adversarial, shuffled = null)
}

/**
 * The search: the words (or a text) to the three sources at once ([PictureSearch]), a grid of pictures that grows as
 * each answers; a tap takes one for the selected frame.
 */
@Composable
private fun SearchPanel(words: List<Keyword>, onPick: (ByteArray) -> Unit, onSources: (List<ByteArray>) -> Unit) {
    val context = LocalContext.current
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0" }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(words.firstOrNull()?.name.orEmpty()) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf<Set<PictureSource>>(emptySet()) }
    var failed by remember { mutableStateOf<Map<PictureSource, String>>(emptyMap()) }
    var searched by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val searches = remember { mutableListOf<Job>() }
    val thumbnails = remember { HashMap<String, ByteArray>() }

    fun search(text: String) {
        query = text
        // A new search drops what the last one has not given yet.
        searches.forEach { it.cancel() }
        searches.clear()
        results = emptyList()
        failed = emptyMap()
        pending = PictureSource.entries.toSet()
        searched = true
        status = null
        PictureSource.entries.forEach { source ->
            searches += scope.launch {
                withContext(Dispatchers.IO) { runCatching { PictureSearch.search(source, text, appVersion) } }
                    .onSuccess { found -> results = (results + found).distinct() }
                    .onFailure { failed = failed + (source to pictureSearchError(it)) }
                pending = pending - source
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            words.forEach { w -> AssistChip(onClick = { search(w.name) }, label = { Text(w.name) }) }
            if (words.size == 2) AssistChip(onClick = { search("${words[0].name} ${words[1].name}") }, label = { Text(t(R.string.flip_search_both)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(t(R.string.flip_search_pictures)) })
            Button(onClick = { search(query) }, enabled = query.isNotBlank()) { Text(t(R.string.flip_search)) }
        }
        (if (searched) pictureSearchStatus(pending, failed, results.size) else null)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (results.isNotEmpty()) {
            Text(t(R.string.flip_search_tap), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                status = t(R.string.flip_getting_anti_ai)
                scope.launch {
                    val picked = withContext(Dispatchers.IO) {
                        results.shuffled().take(12).mapNotNull { url -> thumbnails[url] ?: runCatching { PictureSearch.thumbnail(url, appVersion) }.getOrNull() }.take(8)
                    }
                    status = if (picked.size >= FLIP_FRAMES) null else t(R.string.flip_not_enough_pictures)
                    if (picked.size >= FLIP_FRAMES) onSources(picked)
                }
            }) { Text(t(R.string.flip_use_for_anti_ai)) }
            LazyVerticalGrid(
                GridCells.Fixed(3),
                Modifier.fillMaxWidth().height(360.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(results, key = { it }) { url ->
                    val bytes by produceState(thumbnails[url], url) {
                        if (value == null) value = withContext(Dispatchers.IO) { runCatching { PictureSearch.thumbnail(url, appVersion) }.getOrNull() }?.also { thumbnails[url] = it }
                    }
                    FrameImage(bytes, Modifier.fillMaxWidth().clickable(enabled = bytes != null) { bytes?.let(onPick) })
                }
            }
        }
    }
}

/** The protected frames in story order and in the shuffled order; a tap on two shuffled frames swaps them. */
@Composable
private fun ShuffleStep(draft: FlipDraft, frames: List<ByteArray>, update: (FlipDraft) -> Unit, onNext: () -> Unit) {
    val shuffled = draft.shuffled ?: return
    var picked by remember { mutableStateOf<Int?>(null) }
    val valid = isValidShuffle(shuffled, draft.adversarial)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            t(R.string.flip_shuffle_help),
            style = MaterialTheme.typography.bodySmall,
        )
        (0 until FLIP_FRAMES).forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrameImage(frames.getOrNull(i), Modifier.weight(1f), label = if (i == draft.adversarial) t(R.string.flip_anti_ai) else null)
                FrameImage(
                    frames.getOrNull(shuffled[i]),
                    Modifier.weight(1f).clickable {
                        val first = picked
                        if (first == null) picked = i
                        else {
                            picked = null
                            val next = shuffled.toMutableList()
                            next[first] = shuffled[i].also { next[i] = shuffled[first] }
                            update(draft.copy(shuffled = next))
                        }
                    },
                    selected = picked == i,
                    label = if (shuffled[i] == draft.adversarial) t(R.string.flip_anti_ai) else null,
                )
            }
        }
        if (!valid) Text(t(R.string.flip_shuffle_more), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { update(draft.copy(shuffled = randomShuffle(draft.adversarial))) }) { Text(t(R.string.flip_shuffle)) }
            Button(onClick = onNext, enabled = valid) { Text(t(R.string.common_next)) }
        }
    }
}

@Composable
private fun SubmitStep(draft: FlipDraft, words: List<Keyword>, frames: List<ByteArray>, deadline: Instant?, enabled: Boolean, onSubmit: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(words.joinToString(" / ") { it.name.uppercase() }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            frames.forEachIndexed { i, f -> FrameImage(f, Modifier.weight(1f), label = if (i == draft.adversarial) t(R.string.flip_anti_ai) else null) }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t(R.string.flip_submit_info))
                Text(t(R.string.flip_keep_running), style = MaterialTheme.typography.bodySmall)
                deadline?.let { Text(t(R.string.flip_accepted_until, dateFormat.format(it)), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(t(R.string.flip_submit_flip)) }
    }
}
