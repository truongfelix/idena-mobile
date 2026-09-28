package io.github.truongfelix.idenanode

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The short session lasts 2 minutes (config.ShortSession). */
private const val SHORT_SESSION_MS = 2 * 60_000L

/** The long session lasts one minute per flip (ValidationConfig.GetLongSessionDuration). */
private const val LONG_SESSION_MS_PER_FLIP = 60_000L

/**
 * The practice validation: the ceremony screens with the sample flips of the Idena web app, then the
 * results. Nothing is sent. The screen stays on while it is open.
 */
class CeremonyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    PracticeScreen(onClose = { finish() }, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

private enum class Step { Intro, ShortSession, BetweenSessions, LongSession, Results }

@Composable
private fun PracticeScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Each practice loads the flips again: a new order and new left/right sides.
    var run by remember { mutableIntStateOf(0) }
    var practice by remember { mutableStateOf<Practice?>(null) }
    LaunchedEffect(run) {
        practice = null
        practice = withContext(Dispatchers.IO) { Practice.load(context) }
    }
    var step by remember { mutableStateOf(Step.Intro) }
    var shortAnswers by remember { mutableStateOf<Answers?>(null) }
    var longAnswers by remember { mutableStateOf<Answers?>(null) }
    var leaving by remember { mutableStateOf(false) }

    BackHandler {
        if (step == Step.Intro || step == Step.Results) onClose() else leaving = true
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave the practice?") },
            text = { Text("The answers of this practice are lost.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay") } },
        )
    }

    Box(modifier.fillMaxSize().padding(16.dp)) {
        val loaded = practice
        if (loaded == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else key(run, step) {
            when (step) {
                Step.Intro -> Intro(loaded) { step = Step.ShortSession }
                Step.ShortSession -> Session(
                    title = "Short session",
                    flips = loaded.short.map { it.flip },
                    durationMs = SHORT_SESSION_MS,
                    withKeywords = false,
                ) {
                    shortAnswers = it
                    step = Step.BetweenSessions
                }
                Step.BetweenSessions -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Short session done", style = MaterialTheme.typography.headlineSmall)
                    Text("In the validation, the long session starts right after the short one.")
                    Button(onClick = { step = Step.LongSession }) { Text("Start the long session") }
                }
                Step.LongSession -> Session(
                    title = "Long session",
                    flips = loaded.long.map { it.flip },
                    durationMs = loaded.long.size * LONG_SESSION_MS_PER_FLIP,
                    withKeywords = true,
                ) {
                    longAnswers = it
                    step = Step.Results
                }
                Step.Results -> Results(
                    loaded,
                    shortAnswers ?: Answers(loaded.short.size),
                    longAnswers ?: Answers(loaded.long.size),
                    onAgain = {
                        run++
                        step = Step.ShortSession
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun Intro(practice: Practice, onStart: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Practice validation", style = MaterialTheme.typography.headlineSmall)
        Text("The validation screens with the sample flips of the Idena web app. Nothing is sent.")
        Text(
            "Short session: ${practice.short.size} flips in 2 minutes. For each flip, tap the story " +
                "that makes sense.",
        )
        Text(
            "Long session: ${practice.long.size} flips in ${practice.long.size} minutes. Solve them, then " +
                "check the keywords of each flip: approve it, or report it (up to ${practice.long.size / 3} " +
                "flips) when a keyword is not in the story, numbers or letters show the order, it needs " +
                "reading text, or it lists objects instead of telling a story. You can mark one approved " +
                "flip as the best.",
        )
        Button(onClick = onStart) { Text("Start the short session") }
    }
}

/**
 * A session: the flips one by one, the timer, and for the long session the keyword check. Submits
 * when the validator taps Submit or when the time is up.
 */
@Composable
private fun Session(
    title: String,
    flips: List<Flip>,
    durationMs: Long,
    withKeywords: Boolean,
    onSubmit: (Answers) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var answers by remember { mutableStateOf(Answers(flips.size)) }
    var index by remember { mutableIntStateOf(0) }
    var checkingKeywords by remember { mutableStateOf(false) }
    val deadline = remember { SystemClock.elapsedRealtime() + durationMs }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val currentAnswers by rememberUpdatedState(answers)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    LaunchedEffect(Unit) {
        while (now < deadline) {
            delay(200)
            now = SystemClock.elapsedRealtime()
        }
        currentOnSubmit(currentAnswers)
    }

    /** After a short pause that shows the choice, goes to the next flip [done] says is not done. */
    fun advance(done: (Flip) -> Boolean) {
        scope.launch {
            delay(300)
            val next = (1..flips.size).map { (index + it) % flips.size }.firstOrNull { !done(flips[it]) }
            if (next != null) index = next
        }
    }

    val colors = MaterialTheme.colorScheme
    val flip = flips[index]
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionHeader(title, deadline - now, durationMs)
        if (!checkingKeywords) {
            val answered = flips.count { answers.choice(it.hash) != Choice.None }
            Text("Flip ${index + 1} of ${flips.size}", style = MaterialTheme.typography.bodyMedium)
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
            Dots(flips.size, index, colorOf = { if (answers.choice(flips[it].hash) != Choice.None) colors.primary else null }) {
                index = it
            }
            if (withKeywords) {
                Button(
                    onClick = {
                        checkingKeywords = true
                        index = flips.indexOfFirst { answers.relevance(it.hash) == Relevance.None }.coerceAtLeast(0)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check keywords ($answered of ${flips.size} solved)") }
            } else {
                Button(onClick = { onSubmit(answers) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (answered == flips.size) "Submit answers" else "Submit answers ($answered of ${flips.size})")
                }
            }
        } else {
            val relevance = answers.relevance(flip.hash)
            Row {
                Text("Flip ${index + 1} of ${flips.size}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("Reports left ${answers.reportsLeft}", style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Story(
                    flip,
                    flip.order(answers.choice(flip.hash)),
                    selected = false,
                    modifier = Modifier.fillMaxHeight().aspectRatio(0.36f),
                    onClick = null,
                )
            }
            flip.keywords.forEach { keyword ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(keyword.name) }
                        if (keyword.description.isNotEmpty()) append(": ${keyword.description}")
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleButton("Approve", selected = relevance == Relevance.Approved, colors.primary, colors.onPrimary) {
                    answers = answers.approve(flip.hash)
                    advance { answers.relevance(it.hash) != Relevance.None }
                }
                ToggleButton(
                    "Report",
                    selected = relevance == Relevance.Reported,
                    colors.error,
                    colors.onError,
                    enabled = relevance == Relevance.Reported || answers.reportsLeft > 0,
                ) {
                    answers = answers.report(flip.hash)
                    advance { answers.relevance(it.hash) != Relevance.None }
                }
                Spacer(Modifier.weight(1f))
                if (relevance == Relevance.Approved) {
                    TextButton(onClick = { answers = answers.toggleBest(flip.hash) }) {
                        Text(if (answers.best == flip.hash) "★ Best" else "☆ Best")
                    }
                }
            }
            Dots(flips.size, index, colorOf = {
                when (answers.relevance(flips[it].hash)) {
                    Relevance.Approved -> colors.primary
                    Relevance.Reported -> colors.error
                    Relevance.None -> null
                }
            }) { index = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { checkingKeywords = false }) { Text("Back to the stories") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSubmit(answers) }) { Text("Submit answers") }
            }
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
    LinearProgressIndicator(progress = { remaining.toFloat() / totalMs }, modifier = Modifier.fillMaxWidth(), color = color)
}

/** The four images of a story, top to bottom. */
@Composable
private fun Story(flip: Flip, order: List<Int>, selected: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 4.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        order.forEach { FlipImage(flip, it, Modifier.fillMaxWidth().weight(1f)) }
    }
}

@Composable
private fun FlipImage(flip: Flip, index: Int, modifier: Modifier) {
    val bitmap by produceState(FlipImages.cached(flip, index), flip.hash, index) {
        if (value == null) value = withContext(Dispatchers.Default) { FlipImages.decode(flip, index) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
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

/** One dot per flip, colored once done; the current flip's dot is larger. */
@Composable
private fun Dots(count: Int, current: Int, colorOf: (Int) -> Color?, onClick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (i in 0 until count) {
                Box(Modifier.size(28.dp).clickable { onClick(i) }, contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(if (i == current) 14.dp else 10.dp)
                            .clip(CircleShape)
                            .background(colorOf(i) ?: colors.outlineVariant)
                            .then(if (i == current) Modifier.border(2.dp, colors.onSurface, CircleShape) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    selected: Boolean,
    color: Color,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(color, contentColor)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun Results(practice: Practice, short: Answers, long: Answers, onAgain: () -> Unit, onClose: () -> Unit) {
    val shortRight = practice.short.count { short.choice(it.flip.hash) == it.answer }
    // Flips to report do not count for the stories, as in the validation.
    val solvable = practice.long.filter { it.reportReason == 0 }
    val longRight = solvable.count { long.choice(it.flip.hash) == it.answer }
    val toReport = practice.long.filter { it.reportReason != 0 }
    val reported = toReport.count { long.relevance(it.flip.hash) == Relevance.Reported }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Practice results", style = MaterialTheme.typography.headlineSmall)
        ResultLine("Short session", "$shortRight of ${practice.short.size} stories right")
        ResultLine("Long session", "$longRight of ${solvable.size} stories right")
        ResultLine("Reports", "$reported of ${toReport.size} flips to report (${long.reportsAllowed} reports allowed)")
        Text("Long session, flip by flip", style = MaterialTheme.typography.titleMedium)
        practice.long.forEachIndexed { i, practiceFlip ->
            val hash = practiceFlip.flip.hash
            val relevance = long.relevance(hash)
            val story = when {
                practiceFlip.reportReason != 0 -> null
                long.choice(hash) == practiceFlip.answer -> "story right"
                long.choice(hash) == Choice.None -> "story not solved"
                else -> "story wrong"
            }
            val check = when {
                practiceFlip.reportReason != 0 && relevance == Relevance.Reported ->
                    "reported ✓ (${reportReasonText(practiceFlip.reportReason)})"
                practiceFlip.reportReason != 0 -> "to report: ${reportReasonText(practiceFlip.reportReason)}"
                relevance == Relevance.Reported -> "a good flip: approve it"
                relevance == Relevance.None -> "keywords not checked"
                else -> "approved ✓"
            }
            val words = practiceFlip.flip.keywords.joinToString(", ") { it.name }
            val best = if (long.best == hash) " ★" else ""
            Text("${i + 1}. $words: ${listOfNotNull(story, check).joinToString(", ")}$best")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAgain) { Text("Practice again") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun ResultLine(name: String, value: String) {
    Row {
        Text(name, Modifier.fillMaxWidth(0.35f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
