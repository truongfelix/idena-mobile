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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
 * [submitAt] unless the validator taps Submit first. A flip the node has not delivered yet shows as
 * loading and cannot be answered.
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

    val colors = MaterialTheme.colorScheme
    val flip = flips[index]
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionHeader(title, endsAt - now, totalMs)
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
                Text(
                    "Reports ${answers.reportsUsed} of ${answers.reportsAllowed}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (answers.reportsLeft == 0) colors.error else Color.Unspecified,
                )
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
            if (flip.loaded && flip.keywords.isEmpty()) {
                Text("The keywords of this flip are not available yet.", style = MaterialTheme.typography.bodyMedium)
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
                ToggleButton("Approve", selected = relevance == Relevance.Approved && answers.best != flip.hash, colors.primary, colors.onPrimary, enabled = flip.loaded) {
                    answers = answers.approve(flip.hash)
                    advance { answers.relevance(it.hash) != Relevance.None }
                }
                ToggleButton(
                    "Report",
                    selected = relevance == Relevance.Reported,
                    colors.error,
                    colors.onError,
                    enabled = flip.loaded && (relevance == Relevance.Reported || answers.reportsLeft > 0),
                ) {
                    answers = answers.report(flip.hash)
                    advance { answers.relevance(it.hash) != Relevance.None }
                }
                // Best also approves the flip; one flip at most carries it.
                ToggleButton("★ Best", selected = answers.best == flip.hash, colors.tertiary, colors.onTertiary, enabled = flip.loaded) {
                    answers = answers.toggleBest(flip.hash)
                }
            }
            Text(
                "Report at most ${answers.reportsAllowed} of these ${flips.size} flips and mark at most one as the best: " +
                    "more makes the node ignore all your grades.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Dots(flips.size, index, colorOf = {
                when (answers.relevance(flips[it].hash)) {
                    Relevance.Approved -> colors.primary
                    Relevance.Reported -> colors.error
                    Relevance.None -> null
                }
            }) { index = it }
            if (!answers.hasApproved) {
                Text(
                    "Approve at least one flip: without an approved flip the node ignores your grades.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
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
    LinearProgressIndicator(
        progress = { if (totalMs > 0) (remaining.toFloat() / totalMs).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth(),
        color = color,
    )
}

/** The four images of a story, top to bottom; a flip still loading shows as such and cannot be picked. */
@Composable
private fun Story(flip: Flip, order: List<Int>, selected: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
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
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            order.forEach { FlipImage(flip, it, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}

@Composable
private fun FlipImage(flip: Flip, index: Int, modifier: Modifier) {
    val bitmap by produceState(FlipImages.cached(flip, index), flip.hash, index) {
        // produceState keeps its state when the keys change: start from this flip's image, not the last one's.
        value = FlipImages.cached(flip, index)
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
