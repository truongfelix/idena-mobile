@file:OptIn(ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

/** What identifies [draft]'s pictures ([draftFingerprint]). */
private fun fingerprintOf(draft: FlipDraft) = draftFingerprint(draft.frames, draft.antiAi ?: -1)

/**
 * The frame [bytes] protected as sent ([flipFrameJpeg]): with [effects] when it has some, else with the automatic
 * noise; with the effects used. [seed] draws the grain (the noise window's, so that it is the picture it showed).
 */
private fun protectedFrame(bytes: ByteArray, effects: NoiseEffects?, seed: Long = java.security.SecureRandom().nextLong()): Pair<ByteArray, NoiseEffects> {
    val pixels = framePixels(bytes) ?: error(t(R.string.flip_picture_unreadable))
    val random = kotlin.random.Random(seed)
    if (effects != null) return flipFrameJpeg(protectFrame(pixels, WORK_WIDTH, WORK_HEIGHT, effects, random)) to effects.normalized()
    val made = protectFrame(pixels, WORK_WIDTH, WORK_HEIGHT, random)
    return flipFrameJpeg(made.pixels) to made.effects
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
    // A picture's noise being made again (B-215): its window stays open until it is done; and why it failed.
    var noiseBusy by remember { mutableStateOf(false) }
    var noiseError by remember { mutableStateOf<Pair<Int, String>?>(null) }
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
                    val fingerprint = fingerprintOf(current)
                    scope.launch {
                        protectedFor = null
                        // Each picture with the noise it had (B-215), else the automatic noise, whose effects are kept.
                        val made = withContext(Dispatchers.Default) {
                            runCatching {
                                current.frames.mapIndexed { i, bytes -> protectedFrame(bytes!!, current.noiseOf(i)) }
                            }.onFailure { message = t(R.string.flip_prepare_failed, it.message.toString()) }.getOrNull()
                        }
                        protected = made?.map { it.first }
                        working = null
                        if (made != null) {
                            protectedFor = fingerprint
                            var next = made.foldIndexed(current) { i, d, (_, effects) -> d.withNoise(i, effects) }
                            // The shuffle starts from the story order, as on the desktop (B-205).
                            if (next.shuffled == null || next.shuffled!!.sorted() != STORY_ORDER) next = next.copy(shuffled = STORY_ORDER)
                            if (next != current) update(next)
                            step = FlipStep.Shuffle
                        }
                    }
                })
                FlipStep.Shuffle, FlipStep.Submit -> {
                    // Pictures changed since they were protected (or never protected): back to the pictures.
                    if (protected == null || protectedFor != fingerprintOf(current)) {
                        LaunchedEffect(Unit) { step = FlipStep.Frames }
                        return@Box
                    }
                    if (step == FlipStep.Shuffle) {
                        ShuffleStep(current, protected.orEmpty(), ::update, noiseBusy, noiseError, onNoise = { slot, effects, seed ->
                            val bytes = current.frames[slot] ?: return@ShuffleStep
                            if (noiseBusy) return@ShuffleStep
                            noiseBusy = true
                            noiseError = null
                            scope.launch {
                                val made = withContext(Dispatchers.Default) { runCatching { protectedFrame(bytes, effects, seed) } }
                                made.onSuccess { (frame, used) ->
                                    // Kept only while the pictures are still those protected.
                                    val now = draft ?: return@onSuccess
                                    if (now.frames[slot]?.contentEquals(bytes) == true && protectedFor == fingerprintOf(now)) {
                                        protected = protected?.toMutableList()?.also { it[slot] = frame }
                                        update(now.withNoise(slot, used))
                                    }
                                }.onFailure { noiseError = slot to t(R.string.flip_prepare_failed, it.message.toString()) }
                                noiseBusy = false
                            }
                        }, onNext = { step = FlipStep.Submit })
                        return@Box
                    }
                    SubmitStep(current, words, protected.orEmpty(), nodeState.deadline, enabled = !sending && sent == null && nodeState.synced) {
                    val frames = protected ?: return@SubmitStep
                    val shuffled = current.shuffled?.takeIf { isValidShuffle(it, current.antiAi) } ?: return@SubmitStep
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

/** The anti-AI frame of [draft] from its search pictures, its story pictures or both, by [source] ([adversarialSources]). */
private fun makeAdversarial(draft: FlipDraft, source: AdversarialSource): ByteArray? {
    val random = kotlin.random.Random(java.security.SecureRandom().nextLong())
    val pool = adversarialSources(draft.sources, draft.storyFrames, source, random)
    if (pool.isEmpty()) return null
    val chosen = List(FLIP_FRAMES) { pool[it % pool.size] }
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
    var selected by remember { mutableIntStateOf(draft.frames.indexOfFirst { it == null }.let { if (it < 0 || it == draft.antiAi) (draft.adversarial + 1) % FLIP_FRAMES else it }) }
    var note by remember { mutableStateOf<String?>(null) }
    var searchOn by remember { mutableStateOf(FlipDraftStore.searchOn(context)) }
    var askSearch by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(FlipDraftStore.adversarialSource(context)) }
    var askOwn by remember { mutableStateOf(false) }
    var askBack by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var viewing by remember { mutableStateOf<ByteArray?>(null) }

    /** [next] with its anti-AI picture to make again when it takes the story pictures. */
    fun refreshed(next: FlipDraft) = if (next.antiAiUsesStory(source)) next.withoutAntiAi() else next

    // A new picture at [slot], or the picture there edited: the frame is made from the original with the edit.
    // Only a new picture moves the selection on, to the next empty frame.
    fun setPicture(slot: Int, original: () -> ByteArray, edit: FrameEdit, isNew: Boolean) {
        note = t(R.string.flip_preparing_picture)
        scope.launch {
            val made = withContext(Dispatchers.Default) { runCatching { original().let { o -> o to editedFrame(o, edit) } } }
            made.onSuccess { (o, frame) ->
                val next = refreshed(latest.withPicture(slot, frame, o, edit))
                update(next)
                note = null
                if (isNew && selected == slot) selected = (0 until FLIP_FRAMES).firstOrNull { next.isOwn(it) && next.frames[it] == null } ?: slot
            }.onFailure { note = it.message }
        }
    }

    fun setFrame(slot: Int, bytes: ByteArray) = setPicture(slot, { originalJpeg(bytes) }, FrameEdit(), isNew = true)

    val cameraFile = remember { File(context.cacheDir, "camera/flip.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) runCatching { cameraFile.readBytes() }.onSuccess { setFrame(selected, it) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { readImage(context, uri) } }.onSuccess { setFrame(selected, it) }.onFailure { note = it.message }
        }
    }

    // The anti-AI frame is made once the pictures it takes are there.
    LaunchedEffect(draft.frames, draft.sources, draft.adversarial, draft.ownPicture, source) {
        val slot = draft.antiAi ?: return@LaunchedEffect
        val storyDone = (0 until FLIP_FRAMES).all { it == slot || draft.frames[it] != null }
        if (draft.frames[slot] == null && (storyDone || !draft.antiAiUsesStory(source))) {
            val made = withContext(Dispatchers.Default) { runCatching { makeAdversarial(draft, source) }.getOrNull() }
            val now = latest
            if (made != null && now.antiAi == slot && now.frames[slot] == null) {
                update(now.copy(frames = now.frames.toMutableList().also { it[slot] = made }))
            }
        }
    }

    /** The anti-AI picture again in its place: the own picture there is dropped. */
    fun backToAntiAi() {
        val now = latest
        val slot = now.adversarial
        update(
            now.copy(
                ownPicture = false,
                frames = now.frames.toMutableList().also { it[slot] = null },
                originals = now.originals.toMutableList().also { it[slot] = null },
                edits = now.edits.toMutableList().also { it[slot] = FrameEdit() },
                shuffled = null,
            ),
        )
    }

    /** A tap on a frame selects it; a tap on the frame selected opens it: in the editor for an own picture. */
    fun open(slot: Int) {
        val frame = draft.frames[slot] ?: return
        if (draft.isOwn(slot)) editing = slot else viewing = frame
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t(R.string.flip_story_in_order), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until FLIP_FRAMES).forEach { slot ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FrameImage(
                        draft.frames[slot],
                        Modifier.fillMaxWidth().clickable { if (selected == slot) open(slot) else selected = slot },
                        selected = slot == selected,
                        label = if (slot == draft.antiAi) t(R.string.flip_anti_ai) else "${slot + 1}",
                    )
                    Row {
                        TextButton(onClick = { update(moveFrame(draft, slot, -1)); if (selected == slot) selected = slot - 1 }, enabled = slot > 0) { Text("◀") }
                        TextButton(onClick = { update(moveFrame(draft, slot, 1)); if (selected == slot) selected = slot + 1 }, enabled = slot < FLIP_FRAMES - 1) { Text("▶") }
                    }
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (selected == draft.antiAi) {
            Text(t(R.string.flip_adversarial_info), style = MaterialTheme.typography.bodySmall)
            Text(t(R.string.flip_made_from), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    AdversarialSource.Both to R.string.flip_source_both,
                    AdversarialSource.Story to R.string.flip_source_story,
                    AdversarialSource.Search to R.string.flip_source_search,
                ).forEach { (choice, label) ->
                    FilterChip(selected = source == choice, onClick = {
                        if (source != choice) {
                            source = choice
                            FlipDraftStore.setAdversarialSource(context, choice)
                            update(latest.withoutAntiAi())
                        }
                    }, label = { Text(t(label)) })
                }
            }
            if (source != AdversarialSource.Story) {
                Text(
                    if (draft.sources.isEmpty()) t(R.string.flip_no_search_pictures) else t(R.string.flip_search_pictures_ready, draft.sources.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { update(draft.withoutAntiAi()) }) { Text(t(R.string.flip_new_anti_ai)) }
                draft.frames[selected]?.let { frame -> OutlinedButton(onClick = { viewing = frame }) { Text(t(R.string.flip_full_size)) } }
                TextButton(onClick = { askOwn = true }) { Text(t(R.string.flip_use_own_picture)) }
            }
        } else {
            Text(t(R.string.flip_picture_n, selected + 1), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    cameraFile.parentFile?.mkdirs()
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile))
                }) { Text(t(R.string.flip_camera)) }
                OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(t(R.string.flip_gallery)) }
                if (draft.frames[selected] != null) Button(onClick = { editing = selected }) { Text(t(R.string.flip_edit_picture)) }
            }
            if (draft.ownPicture && selected == draft.adversarial) {
                TextButton(onClick = { if (draft.frames[selected] == null) backToAntiAi() else askBack = true }) { Text(t(R.string.flip_back_to_anti_ai)) }
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
            SearchPanel(words, canUseForAntiAi = !draft.ownPicture && source != AdversarialSource.Story, onPick = { bytes ->
                if (draft.isOwn(selected)) setFrame(selected, bytes)
            }, onSources = { sources ->
                update(latest.copy(sources = sources.take(8)).withoutAntiAi())
            })
        }
        val needed = if (draft.ownPicture) FLIP_FRAMES else FLIP_FRAMES - 1
        Button(onClick = onNext, enabled = draft.complete, modifier = Modifier.fillMaxWidth()) {
            Text(if (draft.complete) t(R.string.flip_next_protect) else t(R.string.flip_choose_pictures, needed))
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
    if (askOwn) {
        // The desktop's "Unlock image": the anti-AI picture stays the advice. Actions one per line (L-65).
        AlertDialog(
            onDismissRequest = { askOwn = false },
            title = { Text(t(R.string.flip_own_ask_title)) },
            text = { Text(t(R.string.flip_own_ask_text)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        askOwn = false
                        val slot = latest.adversarial
                        update(latest.copy(ownPicture = true, frames = latest.frames.toMutableList().also { it[slot] = null }, shuffled = null))
                        selected = slot
                    }) { Text(t(R.string.flip_own_confirm)) }
                    TextButton(onClick = { askOwn = false }) { Text(t(R.string.flip_own_keep_anti_ai)) }
                }
            },
        )
    }
    if (askBack) {
        AlertDialog(
            onDismissRequest = { askBack = false },
            title = { Text(t(R.string.flip_back_ask_title)) },
            text = { Text(t(R.string.flip_back_ask_text)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { askBack = false; backToAntiAi() }) { Text(t(R.string.flip_back_confirm)) }
                    TextButton(onClick = { askBack = false }) { Text(t(R.string.flip_back_keep_mine)) }
                }
            },
        )
    }
    editing?.let { slot ->
        val original = draft.originals[slot] ?: draft.frames[slot] ?: return@let
        FrameEditor(slot + 1, original, draft.edits[slot], onClose = { editing = null }) { edit ->
            editing = null
            // Done without a change keeps the frame, and the anti-AI picture made from it.
            if (edit != draft.edits[slot].normalized() || draft.originals[slot] == null) setPicture(slot, { original }, edit, isNew = false)
        }
    }
    viewing?.let { FrameViewer(it) { viewing = null } }
}

/** [draft] with the frame at [slot] moved one place by [by] (the anti-AI mark, the originals, edits and noise move with it). */
private fun moveFrame(draft: FlipDraft, slot: Int, by: Int): FlipDraft {
    val to = slot + by
    if (to !in 0 until FLIP_FRAMES) return draft
    fun <T> swapped(list: List<T>) = list.toMutableList().also { it[slot] = list[to]; it[to] = list[slot] }
    val adversarial = when (draft.adversarial) {
        slot -> to
        to -> slot
        else -> draft.adversarial
    }
    return draft.copy(
        frames = swapped(draft.frames),
        originals = swapped(draft.originals),
        edits = swapped(draft.edits),
        noise = swapped(draft.noise),
        adversarial = adversarial,
        shuffled = null,
    )
}

/**
 * The search: the words (or a text) to the sources at once ([PictureSearch]), a grid of pictures that grows as
 * each answers; a tap takes one for the selected frame.
 */
@Composable
private fun SearchPanel(words: List<Keyword>, canUseForAntiAi: Boolean, onPick: (ByteArray) -> Unit, onSources: (List<ByteArray>) -> Unit) {
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
            if (canUseForAntiAi) TextButton(onClick = {
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

/**
 * The protected frames in story order and in the shuffled order (B-205): it starts as the story; ▲ ▼ move a picture
 * of the shuffled order one place, Shuffle mixes all four, Reset goes back to the story. A tap opens a frame full size.
 */
@Composable
private fun ShuffleStep(
    draft: FlipDraft,
    frames: List<ByteArray>,
    update: (FlipDraft) -> Unit,
    noiseBusy: Boolean,
    noiseError: Pair<Int, String>?,
    onNoise: (Int, NoiseEffects, Long) -> Unit,
    onNext: () -> Unit,
) {
    val shuffled = draft.shuffled ?: return
    var viewing by remember { mutableStateOf<ByteArray?>(null) }
    // The story picture whose noise is shown (B-215).
    var noiseOf by remember { mutableStateOf<Int?>(null) }
    val valid = isValidShuffle(shuffled, draft.antiAi)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            t(R.string.flip_shuffle_help),
            style = MaterialTheme.typography.bodySmall,
        )
        (0 until FLIP_FRAMES).forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FrameImage(
                    frames.getOrNull(i),
                    Modifier.weight(1f).clickable { frames.getOrNull(i)?.let { viewing = it } },
                    label = if (i == draft.antiAi) t(R.string.flip_anti_ai) else null,
                )
                FrameImage(
                    frames.getOrNull(shuffled[i]),
                    Modifier.weight(1f).clickable { frames.getOrNull(shuffled[i])?.let { viewing = it } },
                    label = if (shuffled[i] == draft.antiAi) t(R.string.flip_anti_ai) else null,
                )
                Column {
                    TextButton(onClick = { update(draft.copy(shuffled = movedInOrder(shuffled, i, -1))) }, enabled = i > 0) { Text("▲") }
                    TextButton(onClick = { update(draft.copy(shuffled = movedInOrder(shuffled, i, 1))) }, enabled = i < FLIP_FRAMES - 1) { Text("▼") }
                }
            }
            // On a line of its own, so that both columns stay level (owner 2026-11-10).
            TextButton(onClick = { noiseOf = i }) { Text(t(R.string.flip_noise_open, i + 1)) }
        }
        if (!valid) Text(t(R.string.flip_shuffle_more), color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { update(draft.copy(shuffled = randomShuffle(draft.antiAi))) }) { Text(t(R.string.flip_shuffle)) }
            OutlinedButton(onClick = { update(draft.copy(shuffled = STORY_ORDER)) }, enabled = shuffled != STORY_ORDER) { Text(t(R.string.flip_shuffle_reset)) }
            Button(onClick = onNext, enabled = valid) { Text(t(R.string.common_next)) }
        }
    }
    viewing?.let { FrameViewer(it) { viewing = null } }
    noiseOf?.let { slot ->
        NoiseEditor(
            slot + 1,
            draft.frames.getOrNull(slot),
            frames.getOrNull(slot),
            draft.noiseOf(slot) ?: NoiseEffects(),
            noiseBusy,
            noiseError?.takeIf { it.first == slot }?.second,
            onChange = { effects, seed -> onNoise(slot, effects, seed) },
            onClose = { noiseOf = null },
        )
    }
}

@Composable
private fun SubmitStep(draft: FlipDraft, words: List<Keyword>, frames: List<ByteArray>, deadline: Instant?, enabled: Boolean, onSubmit: () -> Unit) {
    var viewing by remember { mutableStateOf<ByteArray?>(null) }
    viewing?.let { FrameViewer(it) { viewing = null } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(words.joinToString(" / ") { it.name.uppercase() }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            frames.forEachIndexed { i, f -> FrameImage(f, Modifier.weight(1f).clickable { viewing = f }, label = if (i == draft.antiAi) t(R.string.flip_anti_ai) else null) }
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
