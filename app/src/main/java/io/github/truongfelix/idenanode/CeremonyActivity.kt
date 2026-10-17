package io.github.truongfelix.idenanode

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The short session lasts 2 minutes (config.ShortSession). */
private const val SHORT_SESSION_MS = 2 * 60_000L

/** The long session lasts one minute per flip (ValidationConfig.GetLongSessionDuration). */
private const val LONG_SESSION_MS_PER_FLIP = 60_000L

/**
 * The practice validation: the ceremony screens with the sample flips of the Idena web app, then the
 * results. Nothing is sent. The screen stays on while it is open.
 */
class CeremonyActivity : AppActivity() {
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

    // Back and Close: at once before and after the sessions, after a question during them.
    val close = { if (step == Step.Intro || step == Step.Results) onClose() else leaving = true }
    BackHandler(onBack = close)
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(t(R.string.ceremony_leave_title)) },
            text = { Text(t(R.string.ceremony_leave_text)) },
            confirmButton = { TextButton(onClick = onClose) { Text(t(R.string.ceremony_leave)) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(t(R.string.ceremony_stay)) } },
        )
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Close on every step (owner 2026-10-17: the practice had none but Back).
        Row(Modifier.fillMaxWidth()) { TextButton(onClick = close) { Text(t(R.string.common_close)) } }
        Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp)) {
            val loaded = practice
            if (loaded == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else key(run, step) {
                when (step) {
                    Step.Intro -> Intro(loaded) { step = Step.ShortSession }
                    Step.ShortSession -> PracticeSession(
                        title = t(R.string.validation_short_session),
                        flips = loaded.short.map { it.flip },
                        durationMs = SHORT_SESSION_MS,
                        withKeywords = false,
                    ) {
                        shortAnswers = it
                        step = Step.BetweenSessions
                    }
                    Step.BetweenSessions -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(t(R.string.ceremony_short_done), style = MaterialTheme.typography.headlineSmall)
                        Text(t(R.string.ceremony_long_follows))
                        Button(onClick = { step = Step.LongSession }) { Text(t(R.string.ceremony_start_long)) }
                    }
                    Step.LongSession -> PracticeSession(
                        title = t(R.string.validation_long_session),
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
}

@Composable
private fun Intro(practice: Practice, onStart: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t(R.string.ceremony_practice_title), style = MaterialTheme.typography.headlineSmall)
        Text(t(R.string.ceremony_practice_intro))
        Text(t(R.string.ceremony_practice_short, practice.short.size))
        Text(t(R.string.ceremony_practice_long, practice.long.size, practice.long.size / 3))
        Button(onClick = onStart) { Text(t(R.string.ceremony_start_short)) }
    }
}

/** A practice session: its timer starts when it opens, and the answers go when it runs out. */
@Composable
private fun PracticeSession(
    title: String,
    flips: List<Flip>,
    durationMs: Long,
    withKeywords: Boolean,
    onSubmit: (Answers) -> Unit,
) {
    val endsAt = remember { SystemClock.elapsedRealtime() + durationMs }
    Session(title, flips, endsAt = endsAt, totalMs = durationMs, submitAt = endsAt, withKeywords = withKeywords, onSubmit = onSubmit)
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
        Text(t(R.string.ceremony_results_title), style = MaterialTheme.typography.headlineSmall)
        ResultLine(t(R.string.validation_short_session), t(R.string.ceremony_stories_right, shortRight, practice.short.size))
        ResultLine(t(R.string.validation_long_session), t(R.string.ceremony_stories_right, longRight, solvable.size))
        ResultLine(t(R.string.ceremony_reports), t(R.string.ceremony_reports_result, reported, toReport.size, long.reportsAllowed))
        Text(t(R.string.ceremony_flip_by_flip), style = MaterialTheme.typography.titleMedium)
        practice.long.forEachIndexed { i, practiceFlip ->
            val hash = practiceFlip.flip.hash
            val relevance = long.relevance(hash)
            val story = when {
                practiceFlip.reportReason != 0 -> null
                long.choice(hash) == practiceFlip.answer -> t(R.string.ceremony_story_right)
                long.choice(hash) == Choice.None -> t(R.string.ceremony_story_not_solved)
                else -> t(R.string.ceremony_story_wrong)
            }
            val check = when {
                practiceFlip.reportReason != 0 && relevance == Relevance.Reported ->
                    t(R.string.ceremony_reported_ok, reportReasonText(practiceFlip.reportReason))
                practiceFlip.reportReason != 0 -> t(R.string.ceremony_to_report, reportReasonText(practiceFlip.reportReason))
                relevance == Relevance.Reported -> t(R.string.ceremony_good_flip_approve)
                relevance == Relevance.None -> t(R.string.ceremony_keywords_not_checked)
                else -> t(R.string.ceremony_approved_ok)
            }
            val words = practiceFlip.flip.keywords.joinToString(", ") { it.name }
            val best = if (long.best == hash) " ★" else ""
            Text(t(R.string.ceremony_flip_line, i + 1, words, listOfNotNull(story, check).joinToString(", "), best))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAgain) { Text(t(R.string.ceremony_practice_again)) }
            OutlinedButton(onClick = onClose) { Text(t(R.string.common_close)) }
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
