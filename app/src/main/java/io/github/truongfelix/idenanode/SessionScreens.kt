package io.github.truongfelix.idenanode

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The screens of a session, shared by the practice and the real validation: the flips one by one, the
 * timer, and for the long session the keyword check.
 */

/**
 * A session. The timer runs to [endsAt] ([SystemClock.elapsedRealtime] time) and the answers go at
 * [submitAt] unless the validator submits first. A flip the node has not delivered yet shows as loading and
 * cannot be answered. Submit (and in the long session, the keyword check) only shows once every flip that
 * can be answered is answered, and asks to confirm: the bottom of the screen is Previous / Next, where the
 * thumb goes after a flip. As on the desktop, the keyword check does not go back to the stories.
 */
@Composable
fun Session(
    title: String,
    flips: List<Flip>,
    endsAt: Long,
    totalMs: Long,
    submitAt: Long,
    withKeywords: Boolean,
    onSubmit: (Answers) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var answers by remember { mutableStateOf(Answers(flips.size)) }
    var index by remember { mutableIntStateOf(0) }
    var checkingKeywords by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var confirmingKeywords by remember { mutableStateOf(false) }
    /** The flip to become the best in place of the current best, while the validator confirms. */
    var newBest by remember { mutableStateOf<String?>(null) }
    var showRules by remember { mutableStateOf(false) }
    /** The flip and the image shown full screen. */
    var zoomed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val currentAnswers by rememberUpdatedState(answers)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    LaunchedEffect(submitAt) {
        while (now < submitAt) {
            delay(200)
            now = SystemClock.elapsedRealtime()
        }
        currentOnSubmit(currentAnswers)
    }
    if (flips.isEmpty()) return
    index = index.coerceIn(flips.indices)

    /** After a short pause that shows the choice, goes to the next flip [done] says is not done. */
    fun advance(done: (Flip) -> Boolean) {
        scope.launch {
            delay(300)
            val next = (1..flips.size).map { (index + it) % flips.size }.firstOrNull { !done(flips[it]) }
            if (next != null) index = next
        }
    }

    /** In the keyword check, a flip whose keywords are not on screen is not asked about. */
    val graded = { f: Flip -> answers.relevance(f.hash) != Relevance.None || f.keywords.isEmpty() }

    if (confirming) {
        ConfirmDialog(
            t(R.string.validation_submit_title),
            t(R.string.validation_submit_text, summary(flips, answers, withKeywords)),
            confirmLabel = t(R.string.validation_submit),
            onConfirm = {
                confirming = false
                onSubmit(answers)
            },
            onDismiss = { confirming = false },
        )
    }
    if (confirmingKeywords) {
        ConfirmDialog(
            t(R.string.validation_check_keywords_title),
            t(R.string.validation_check_keywords_text, summary(flips, answers, withKeywords = false)),
            confirmLabel = t(R.string.validation_check_keywords),
            onConfirm = {
                confirmingKeywords = false
                checkingKeywords = true
                index = flips.indexOfFirst { it.keywords.isNotEmpty() && answers.relevance(it.hash) == Relevance.None }.coerceAtLeast(0)
            },
            onDismiss = { confirmingKeywords = false },
        )
    }
    newBest?.let { hash ->
        val previous = flips.indexOfFirst { it.hash == answers.best } + 1
        ConfirmDialog(
            t(R.string.validation_change_best_title),
            t(R.string.validation_change_best_text, previous),
            confirmLabel = t(R.string.validation_change),
            onConfirm = {
                newBest = null
                answers = answers.toggleBest(hash)
                advance(graded)
            },
            onDismiss = { newBest = null },
        )
    }
    if (showRules) KeywordRules(answers.reportsAllowed, flips.size) { showRules = false }

    val flip = flips[index]
    zoomed?.takeIf { it.first == flip.hash }?.let { ZoomedImage(flip, it.second) { zoomed = null } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionHeader(title, endsAt - now, totalMs)
        if (!checkingKeywords) {
            Text(t(R.string.validation_flip_of, index + 1, flips.size), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (side in listOf(Choice.Left, Choice.Right)) {
                    Story(
                        flip,
                        flip.order(side),
                        selected = answers.choice(flip.hash) == side,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        answers = answers.choose(flip.hash, side)
                        advance { answers.choice(it.hash) != Choice.None }
                    }
                }
            }
            Dots(flips.size, index, markOf = { if (answers.choice(flips[it].hash) != Choice.None) Mark.Check else null }) {
                index = it
            }
            PreviousNext(onPrevious = { index = (index + flips.size - 1) % flips.size }, onNext = { index = (index + 1) % flips.size })
            val solvable = answers.solvable(flips).size
            val waiting = when {
                solvable == 0 -> t(R.string.validation_flips_loading)
                withKeywords -> t(R.string.validation_wait_solve, answers.solvedCount(flips), solvable)
                else -> t(R.string.validation_wait_answer, answers.solvedCount(flips), solvable)
            }
            NextStep(
                ready = answers.storiesDone(flips),
                label = if (withKeywords) t(R.string.validation_check_keywords) else t(R.string.validation_submit_answers),
                waiting = waiting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (withKeywords) confirmingKeywords = true else confirming = true
            }
        } else {
            val relevance = answers.relevance(flip.hash)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(R.string.validation_flip_of, index + 1, flips.size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { showRules = true }, Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = t(R.string.validation_rules_description))
                }
            }
            // The story as a column on the left, as tall as the screen allows; on the right the keywords, the
            // counts and the buttons.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val storyWidth = minOf(maxHeight * 0.34f, maxWidth * 0.58f)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Story(
                        flip,
                        flip.order(answers.choice(flip.hash)),
                        selected = false,
                        modifier = Modifier.width(storyWidth).fillMaxHeight(),
                        onClick = null,
                        onImageClick = { zoomed = flip.hash to it },
                    )
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Keywords(flip, Modifier.weight(1f).fillMaxWidth())
                        Counts(answers)
                        val buttons = Modifier.fillMaxWidth()
                        ToggleButton(
                            t(R.string.validation_approve, Mark.Check.glyph),
                            selected = relevance == Relevance.Approved && answers.best != flip.hash,
                            Mark.Check.color(),
                            Color.White,
                            buttons,
                            enabled = flip.loaded,
                        ) {
                            answers = answers.approve(flip.hash)
                            advance(graded)
                        }
                        ToggleButton(
                            t(R.string.validation_report, Mark.Cross.glyph),
                            selected = relevance == Relevance.Reported,
                            Mark.Cross.color(),
                            Color.White,
                            buttons,
                            enabled = flip.loaded && (relevance == Relevance.Reported || answers.reportsLeft > 0),
                        ) {
                            answers = answers.report(flip.hash)
                            advance(graded)
                        }
                        // Best also approves the flip; one flip at most carries it.
                        ToggleButton(
                            t(R.string.validation_best, Mark.Star.glyph),
                            selected = answers.best == flip.hash,
                            Mark.Star.color(),
                            onGold(),
                            buttons,
                            enabled = flip.loaded,
                        ) {
                            if (answers.best != null && answers.best != flip.hash) {
                                newBest = flip.hash
                            } else {
                                answers = answers.toggleBest(flip.hash)
                                // Marking the best approves the flip: go on like Approve. Removing the mark stays.
                                if (answers.best == flip.hash) advance(graded)
                            }
                        }
                    }
                }
            }
            Dots(flips.size, index, markOf = {
                when {
                    answers.best == flips[it].hash -> Mark.Star
                    answers.relevance(flips[it].hash) == Relevance.Approved -> Mark.Check
                    answers.relevance(flips[it].hash) == Relevance.Reported -> Mark.Cross
                    else -> null
                }
            }) { index = it }
            PreviousNext(onPrevious = { index = (index + flips.size - 1) % flips.size }, onNext = { index = (index + 1) % flips.size })
            NextStep(
                ready = answers.keywordCheckDone(flips),
                label = t(R.string.validation_submit_answers),
                waiting = if (!answers.gradesDone(flips)) {
                    t(R.string.validation_wait_grades, answers.gradedCount(flips), answers.gradable(flips).size)
                } else {
                    t(R.string.validation_wait_best)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { confirming = true }
        }
    }
}

@Composable
private fun PreviousNext(onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPrevious, Modifier.weight(1f)) { Text(t(R.string.validation_previous)) }
        OutlinedButton(onClick = onNext, Modifier.weight(1f)) { Text(t(R.string.validation_next)) }
    }
}

/** The keyword check so far: reports used and left, approvals, the best; a green check once a limit is reached. */
@Composable
private fun Counts(answers: Answers) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        CountLine(t(R.string.validation_count_reported, answers.reportsUsed, answers.reportsAllowed), done = answers.reportsLeft == 0)
        Text(tp(R.plurals.validation_count_left, answers.reportsLeft, answers.reportsLeft), style = MaterialTheme.typography.bodySmall, color = muted)
        CountLine(t(R.string.validation_count_approved, answers.relevanceByHash.count { it.value == Relevance.Approved }), done = false)
        CountLine(t(R.string.validation_count_best, if (answers.best != null) 1 else 0), done = answers.best != null)
    }
}

@Composable
private fun CountLine(text: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (done) Text("✓", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Health.Good.color())
    }
}

/** What the confirmation says about the answers about to go. */
private fun summary(flips: List<Flip>, answers: Answers, withKeywords: Boolean): String {
    val solved = answers.solvedCount(flips)
    val text = if (!withKeywords) {
        t(R.string.validation_summary_answered, solved, flips.size)
    } else {
        val approved = flips.count { answers.relevance(it.hash) == Relevance.Approved }
        val sentence = if (answers.best != null) R.string.validation_summary_solved_best else R.string.validation_summary_solved
        t(sentence, solved, flips.size, approved, answers.reportsUsed) +
            if (answers.hasApproved) "" else " " + t(R.string.validation_summary_none_approved)
    }
    val loading = flips.size - answers.solvable(flips).size
    return if (loading > 0) tp(R.plurals.validation_summary_not_loaded, loading, text, loading) else text
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_back)) } },
    )
}

@Composable
private fun KeywordRules(reportsAllowed: Int, flipCount: Int, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t(R.string.validation_keyword_check)) },
        text = {
            Text(t(R.string.validation_keyword_rules, reportsAllowed, flipCount))
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t(R.string.common_ok)) } },
    )
}

/** The flip's two keywords, large, each with its description below in small grey text. */
@Composable
private fun Keywords(flip: Flip, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (flip.loaded && flip.keywords.isEmpty()) {
            Text(t(R.string.validation_keywords_not_available), style = MaterialTheme.typography.bodyMedium)
        }
        flip.keywords.forEach { keyword ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FittedText(keyword.name, MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), minSize = 18.sp)
                if (keyword.description.isNotEmpty()) {
                    Text(
                        keyword.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The way on: the button once [ready], else what is still missing, in the same height. */
@Composable
private fun NextStep(ready: Boolean, label: String, waiting: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (ready) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } else {
            Text(
                waiting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One image of a flip over the whole screen; a tap closes it. */
@Composable
private fun ZoomedImage(flip: Flip, image: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            FlipImage(flip, image, Modifier.fillMaxSize(), background = Color.Black)
        }
    }
}

@Composable
private fun SessionHeader(title: String, remainingMs: Long, totalMs: Long) {
    val remaining = remainingMs.coerceAtLeast(0)
    val seconds = (remaining + 999) / 1000
    val color = if (seconds <= 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleMedium, color = color)
    }
    LinearProgressIndicator(
        progress = { if (totalMs > 0) (remaining.toFloat() / totalMs).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth(),
        color = color,
    )
}

/** The four images of a story, top to bottom; a flip still loading shows as such and cannot be picked. */
@Composable
internal fun Story(
    flip: Flip,
    order: List<Int>,
    selected: Boolean,
    modifier: Modifier,
    onImageClick: ((Int) -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 4.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .then(if (onClick != null && flip.loaded) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!flip.loaded) {
            Box(Modifier.fillMaxSize().background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(t(R.string.validation_loading), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            order.forEach { image ->
                val click = if (onImageClick != null) Modifier.clickable { onImageClick(image) } else Modifier
                FlipImage(flip, image, Modifier.fillMaxWidth().weight(1f).then(click))
            }
        }
    }
}

@Composable
private fun FlipImage(flip: Flip, index: Int, modifier: Modifier, background: Color = MaterialTheme.colorScheme.surfaceVariant) {
    val bitmap by produceState(FlipImages.cached(flip, index), flip.hash, index) {
        // produceState keeps its state when the keys change: start from this flip's image, not the last one's.
        value = FlipImages.cached(flip, index)
        if (value == null) value = withContext(Dispatchers.Default) { FlipImages.decode(flip, index) }
    }
    Box(modifier.background(background)) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Decoded flip images, least recently used out first; large images are decoded at a reduced size. */
private object FlipImages {
    private const val MAX_SIDE = 800
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(flip: Flip, index: Int): ImageBitmap? = cache.get("${flip.hash}/$index")

    fun decode(flip: Flip, index: Int): ImageBitmap? {
        val bytes = flip.images[index]
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SIDE) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() ?: return null
        cache.put("${flip.hash}/$index", bitmap)
        return bitmap
    }
}

/** What a flip's place at the bottom shows once done: solved or approved, reported, the best. */
private enum class Mark(val glyph: String) { Check("✓"), Cross("✗"), Star("★") }

@Composable
private fun Mark.color(): Color = when (this) {
    Mark.Check -> Health.Good.color()
    Mark.Cross -> Health.Bad.color()
    Mark.Star -> if (darkTheme()) Color(0xFFFFD54F) else Color(0xFFB8860B)
}

/** The text on gold: dark on the dark theme's light gold, white on the light theme's dark gold. */
@Composable
private fun onGold(): Color = if (darkTheme()) Color.Black else Color.White

@Composable
private fun darkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** One place per flip: a grey dot until done, then its mark; the current flip's place has a ring. */
@Composable
private fun Dots(count: Int, current: Int, markOf: (Int) -> Mark?, onClick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (i in 0 until count) {
                val mark = markOf(i)
                Box(Modifier.size(30.dp).clickable { onClick(i) }, contentAlignment = Alignment.Center) {
                    val ring = if (i == current) Modifier.border(2.dp, colors.onSurface, CircleShape) else Modifier
                    Box(Modifier.size(26.dp).then(ring), contentAlignment = Alignment.Center) {
                        if (mark == null) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.outlineVariant))
                        } else {
                            Text(mark.glyph, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = mark.color())
                        }
                    }
                }
            }
        }
    }
}

/** [text] in [style], or smaller (down to [minSize]) when its longest word does not fit: words never break. */
@Composable
private fun FittedText(text: String, style: TextStyle, minSize: TextUnit) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val width = constraints.maxWidth
        val size = remember(text, style, width) {
            var size = style.fontSize
            fun widest() = text.split(' ').maxOf { measurer.measure(it, style.copy(fontSize = size)).size.width }
            while (size > minSize && widest() > width) size *= 0.92f
            size
        }
        Text(text, style = style.copy(fontSize = size))
    }
}

@Composable
private fun ToggleButton(
    label: String,
    selected: Boolean,
    color: Color,
    contentColor: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    if (selected) {
        Button(onClick = onClick, modifier, colors = ButtonDefaults.buttonColors(color, contentColor), contentPadding = padding) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier, enabled = enabled, contentPadding = padding) { Text(label) }
    }
}
