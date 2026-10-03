package io.github.truongfelix.idenanode

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * The validation ceremony with the node of the app: the short session, then the long session, with the
 * flips the node assigns to the identity. Answers go to the node, which sends them to the network. The
 * screen stays on while it is open.
 */
class ValidationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    ValidationScreen(onClose = { finish() }, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

/** What the screen shows, from the node's ceremony period and the answers already sent. */
private sealed interface CeremonyStep {
    data object Checking : CeremonyStep
    data class Message(val title: String, val text: String) : CeremonyStep
    data class Waiting(val times: CeremonyTimes) : CeremonyStep
    data class ShortSession(val times: CeremonyTimes) : CeremonyStep
    data class LongSession(val times: CeremonyTimes) : CeremonyStep
    data class Done(val times: CeremonyTimes) : CeremonyStep
}

private fun nextStep(context: Context, ceremony: CeremonyState, now: Instant): CeremonyStep {
    val start = parseValidationTime(ceremony.validationTime)
        ?: return CeremonyStep.Message("No validation now", "The node did not give the validation time.")
    val times = CeremonyTimes(start, ceremony.shortSessionSeconds, ceremony.longSessionSeconds)
    val shortSent = NodeSettings.answersSentFile(context, ceremony.epoch, short = true).exists()
    val longSent = NodeSettings.answersSentFile(context, ceremony.epoch, short = false).exists()
    return when (ceremony.period) {
        "FlipLottery" -> when {
            now < start -> CeremonyStep.Waiting(times)
            shortSent -> CeremonyStep.LongSession(times)
            else -> CeremonyStep.ShortSession(times)
        }
        "ShortSession" -> if (shortSent) CeremonyStep.LongSession(times) else CeremonyStep.ShortSession(times)
        "LongSession" -> if (longSent) CeremonyStep.Done(times) else CeremonyStep.LongSession(times)
        "AfterLongSession" -> CeremonyStep.Done(times)
        else -> CeremonyStep.Message("No validation now", "The next validation: ${dateTime(start)} (${timeUntil(now, start)}).")
    }
}

@Composable
private fun ValidationScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rpc = remember { NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)) }
    val keywords = remember { loadKeywords(context) }
    var step by remember { mutableStateOf<CeremonyStep>(CeremonyStep.Checking) }
    var epoch by remember { mutableIntStateOf(-1) }
    // Reads the ceremony period now, and again every few seconds.
    var checks by remember { mutableIntStateOf(0) }
    LaunchedEffect(checks) {
        while (true) {
            val ceremony = withContext(Dispatchers.IO) { runCatching { rpc.ceremony() }.getOrNull() }
            val inSession = step is CeremonyStep.ShortSession || step is CeremonyStep.LongSession
            if (ceremony != null) {
                epoch = ceremony.epoch
                val next = nextStep(context, ceremony, Instant.now())
                if (next != step) step = next
            } else if (!inSession) {
                // A node busy during a session may miss a call: the session and its answers stay.
                step = CeremonyStep.Message(
                    "The node does not answer",
                    "Switch sync on in the main screen, and wait until the node is synced.",
                )
            }
            delay(3_000)
        }
    }
    val shortFlips = remember(epoch) { SessionFlips(rpc, short = true, keywords = null) }
    val longFlips = remember(epoch) { SessionFlips(rpc, short = false, keywords = keywords) }

    var leaving by remember { mutableStateOf(false) }
    BackHandler {
        if (step is CeremonyStep.ShortSession || step is CeremonyStep.LongSession) leaving = true else onClose()
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave the validation?") },
            text = { Text("The answers of this session are not sent yet. You can come back while the session lasts.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay") } },
        )
    }

    Box(modifier.fillMaxSize().padding(16.dp)) {
        when (val current = step) {
            CeremonyStep.Checking -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is CeremonyStep.Message -> Info(current.title, current.text, onClose)
            is CeremonyStep.Waiting -> Waiting(current.times, shortFlips)
            is CeremonyStep.ShortSession -> RealSession(current.times, epoch, short = true, shortFlips) { checks++ }
            is CeremonyStep.LongSession -> RealSession(current.times, epoch, short = false, longFlips) { checks++ }
            is CeremonyStep.Done -> Done(current.times, epoch, onClose)
        }
    }
}

@Composable
private fun Info(title: String, text: String, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text)
        OutlinedButton(onClick = onClose) { Text("Close") }
    }
}

/**
 * After the long answers: the node still sends the short answers (their hash went in the short session)
 * once the long ones are sent. Both in blocks, the app may close; until then the node must run.
 */
@Composable
private fun Done(times: CeremonyTimes, epoch: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    var inBlocks by remember { mutableStateOf(false) }
    LaunchedEffect(epoch) {
        while (!inBlocks) {
            inBlocks = withContext(Dispatchers.IO) {
                runCatching { answersInBlocks(rpc(context).ownTransactions(), epoch) }.getOrDefault(false)
            }
            if (!inBlocks) delay(5_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Answers sent", style = MaterialTheme.typography.headlineSmall)
        if (inBlocks) {
            Text("Your short and long answers are in blocks: you can close the app.")
        } else {
            Text("The node is putting your answers in blocks. Keep the app open until this screen says it is done.")
            CircularProgressIndicator()
        }
        Text(
            "The validation ends at ${dateTime(times.longEnd)}. Its result shows on the main screen a few minutes " +
                "later, as the identity status.",
        )
        OutlinedButton(onClick = onClose) { Text("Close") }
    }
}

/** Before the short session: when it starts, and the flips loaded so far. */
@Composable
private fun Waiting(times: CeremonyTimes, flips: SessionFlips) {
    val state = flips.watch(active = true)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Validation", style = MaterialTheme.typography.headlineSmall)
        Text("The short session starts at ${dateTime(times.start)} (${timeUntil(Instant.now(), times.start)}).")
        Text("Keep this screen open: the session opens by itself. The flips load in the meantime.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.hashes.isNotEmpty()) {
            val regular = state.hashes.filter { !it.extra }
            Text("Flips ready: ${regular.count { it.hash in state.loaded }} of ${regular.size}")
        }
    }
}

/**
 * A session with the node's flips: the timer ends with the session, the answers go 10 s before, or when
 * the validator taps Submit. [onSent] runs once the node has them.
 */
@Composable
private fun RealSession(times: CeremonyTimes, epoch: Int, short: Boolean, flips: SessionFlips, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = flips.watch(active = true, extrasFrom = if (short) times.extraFlipsFrom else null)
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<SubmittedAnswer>?>(null) }
    val sessionStart = if (short) times.start else times.shortEnd
    val sessionEnd = if (short) times.shortEnd else times.longEnd
    val endsAt = remember(sessionEnd) { elapsedAt(sessionEnd) }
    val submitAt = remember(sessionEnd) { elapsedAt(if (short) times.shortSubmit else times.longSubmit) }
    val totalMs = Duration.between(sessionStart, sessionEnd).toMillis()

    /** Sends the answers, again every 2 s while the node refuses them and the session lasts. */
    fun send(answers: List<SubmittedAnswer>) {
        if (sending) return
        pending = answers
        sending = true
        sendError = null
        scope.launch {
            while (true) {
                val error = withContext(Dispatchers.IO) {
                    runCatching { rpc(context).submitAnswers(short, answers) }.exceptionOrNull()?.message
                }
                if (error == null || isAlreadySent(error)) {
                    withContext(Dispatchers.IO) { NodeSettings.answersSentFile(context, epoch, short).createNewFile() }
                    onSent()
                    return@launch
                }
                sendError = error
                if (Instant.now() >= sessionEnd) break
                delay(2_000)
            }
            sending = false
        }
    }

    val shown = if (short) shortSessionFlips(state.hashes, state.replaced) else state.hashes.map { it.hash }
    val title = if (short) "Short session" else "Long session"
    when {
        sending || pending != null && sendError == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("Sending the answers…")
            sendError?.let { Text("The node did not take them yet ($it): trying again.", color = MaterialTheme.colorScheme.error) }
            CircularProgressIndicator()
        }
        sendError != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("The node did not take the answers: $sendError", color = MaterialTheme.colorScheme.error)
            Button(onClick = { pending?.let { send(it) } }) { Text("Try again") }
        }
        shown.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(state.error ?: "Loading the flips…")
        }
        else -> Session(
            title = title,
            flips = shown.map { state.flip(it) },
            endsAt = endsAt,
            totalMs = totalMs,
            submitAt = submitAt,
            withKeywords = !short,
        ) { answers ->
            send(if (short) shortSessionAnswers(state.hashes.map { it.hash }, answers) else longSessionAnswers(shown, answers))
        }
    }
}

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The flips of one session as the node delivers them: the node lists the flips, marks each one ready
 * once it can decrypt it, then gives its images and, in the long session, its keywords.
 */
private class SessionFlips(private val rpc: NodeRpc, val short: Boolean, private val keywords: List<Keyword>?) {
    private val flips = ConcurrentHashMap<String, Flip>()
    private val words = ConcurrentHashMap<String, List<Keyword>>()

    /** The last list from the node: a screen that opens later starts from it. */
    @Volatile
    private var lastHashes: List<FlipHash> = emptyList()

    /** The regular flips replaced by extra flips ([replaceWithExtras]), kept for the session. */
    @Volatile
    private var replaced: Map<String, String> = emptyMap()

    class State(
        val hashes: List<FlipHash>,
        val loaded: Set<String>,
        val replaced: Map<String, String>,
        val error: String?,
        private val of: (String) -> Flip,
    ) {
        fun flip(hash: String): Flip = of(hash)
    }

    private fun state(hashes: List<FlipHash>, error: String?) = State(hashes, flips.keys.toSet(), replaced, error) { hash ->
        val flip = flips[hash]
        val flipWords = words[hash]
        when {
            flip == null -> Flip.loading(hash)
            flipWords == null -> flip
            else -> Flip(flip.hash, flip.images, flip.leftOrder, flip.rightOrder, flipWords)
        }
    }

    /**
     * Asks the node for the flips again and loads those that became ready; from [extrasFrom], extra flips
     * replace the flips that did not load. Blocks: not on the main thread.
     */
    fun refresh(extrasFrom: Instant?): State {
        val hashes = try {
            rpc.flipHashes(short)
        } catch (e: Exception) {
            // Keeps the flips already listed: a session on screen must not lose them to a missed call.
            return state(lastHashes, e.message ?: "The node did not list the flips.")
        }
        lastHashes = hashes
        val all = keywords
        for (hash in hashes.filter { it.ready }.map { it.hash }) {
            if (!flips.containsKey(hash)) runCatching { rpc.flip(hash) }.onSuccess { flips[hash] = it }
            if (all != null && flips.containsKey(hash) && !words.containsKey(hash)) {
                runCatching { rpc.flipKeywords(hash, all) }.onSuccess { if (it.isNotEmpty()) words[hash] = it }
            }
        }
        if (extrasFrom != null && Instant.now() >= extrasFrom) replaced = replaceWithExtras(hashes, flips.keys, replaced)
        return state(hashes, null)
    }

    /** Keeps the state current while [active]: every 2 s while flips are missing, else every 10 s. */
    @Composable
    fun watch(active: Boolean, extrasFrom: Instant? = null): State {
        var state by remember { mutableStateOf(state(lastHashes, null)) }
        LaunchedEffect(this, active, extrasFrom) {
            while (active) {
                state = withContext(Dispatchers.IO) { refresh(extrasFrom) }
                val complete = state.hashes.isNotEmpty() && state.hashes.all {
                    it.hash in state.loaded && (keywords == null || words.containsKey(it.hash))
                }
                delay(if (complete) 10_000 else 2_000)
            }
        }
        return state
    }
}

/** The node's keyword list (keywords/keywords.json of idena-go): flip_words gives indexes into it. */
internal fun loadKeywords(context: Context): List<Keyword> {
    val array = JSONArray(context.assets.open("keywords.json").bufferedReader().use { it.readText() })
    return (0 until array.length()).map {
        val item = array.getJSONObject(it)
        Keyword(item.getString("name"), item.optString("desc"))
    }
}

/** [instant] as [SystemClock.elapsedRealtime] time, for the session timer. */
private fun elapsedAt(instant: Instant): Long =
    SystemClock.elapsedRealtime() + Duration.between(Instant.now(), instant).toMillis()

private fun dateTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)
