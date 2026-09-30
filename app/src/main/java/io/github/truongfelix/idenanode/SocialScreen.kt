package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicBoolean

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** Why the scan waits, or null when the node is ready: synced and outside the validation. */
private fun notReadyReason(rpc: NodeRpc): String? {
    val syncing = rpc.call("bcn_syncing") as JSONObject
    if (syncing.optBoolean("syncing")) return "Waiting: the node is syncing."
    if (rpc.ceremony().period != "None") return "Paused during the validation."
    return null
}

/**
 * The Social tab: idena.social read from the phone's node, and posting with the node's key. While the
 * "Scan blocks" select button is on (remembered, on at first), the tab scans the blocks after the newest
 * scanned one up to the node's head, then the history back to the contract's first block (once), then
 * follows the head; unselected, it stops at its next batch. Nothing is scanned while the tab is hidden,
 * the node syncs or a validation runs.
 */
@Composable
fun SocialScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cache by remember { mutableStateOf<SocialCache?>(null) }
    var feed by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    val states = remember { mutableStateMapOf<String, String>() }
    var head by remember { mutableStateOf<Long?>(null) }
    var activity by remember { mutableStateOf("Loading…") }
    var ready by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(SocialStore.isScanning(context)) }
    // The scan runs on an IO thread and reads these between two batches.
    val scanningFlag = remember { AtomicBoolean(scanning) }
    // After a post, the new blocks are scanned once even without "Scan blocks".
    val scanNewOnce = remember { AtomicBoolean(false) }
    var wake by remember { mutableIntStateOf(0) }

    suspend fun show(next: SocialCache) {
        cache = next
        feed = withContext(Dispatchers.Default) { next.feed() }
        val unknown = feed.flatMap { withReplies(it) }.map { it.call.author }.distinct().filter { it !in states }
        if (unknown.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identityStates(unknown) }.getOrNull() }
                ?.let { states.putAll(it) }
        }
    }

    /** Waits [ms], or less when a button changes or a post was made. */
    suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { snapshotFlow { scanning to wake }.drop(1).first() }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SocialStore.load(context) }?.let { show(it) }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val source = NodeSocialSource(rpc(context))
                    val reason = withContext(Dispatchers.IO) { notReadyReason(rpc(context)) }
                    ready = reason == null
                    if (reason != null) {
                        activity = reason
                        pause(30_000)
                        continue
                    }
                    val nodeHead = withContext(Dispatchers.IO) { source.head() }
                    head = nodeHead
                    val current = cache
                    val wantsNew = scanningFlag.get() || scanNewOnce.get()
                    when {
                        // The first time, the scan starts at the head: nothing to read yet.
                        current == null && wantsNew -> {
                            activity = "Starting at the node's block…"
                            show(withContext(Dispatchers.IO) { checkNewBlocks(null, source) { SocialStore.save(context, it) } })
                        }
                        current == null -> {
                            activity = "Select \"Scan blocks\" to read the posts from the node."
                            pause(20_000)
                        }
                        wantsNew && (current.high < nodeHead || current.authorsHeight < current.high) -> {
                            activity = "Scanning new blocks…"
                            val next = withContext(Dispatchers.IO) {
                                checkNewBlocks(current, source, keepGoing = { scanningFlag.get() || scanNewOnce.get() }) { SocialStore.save(context, it) }
                            }
                            if (next.authorsHeight >= next.high) scanNewOnce.set(false)
                            show(next)
                        }
                        !current.historyDone && scanningFlag.get() -> {
                            activity = "Scanning the history…"
                            show(withContext(Dispatchers.IO) { scanOlderBlocks(current, source) { SocialStore.save(context, it) } })
                        }
                        else -> {
                            scanNewOnce.set(false)
                            activity = when {
                                !scanningFlag.get() -> "Stopped. Select \"Scan blocks\" to read the newest posts."
                                else -> "Up to date."
                            }
                            // A new block comes about every 20 seconds.
                            pause(20_000)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    activity = "The node did not answer: ${e.message}"
                    pause(10_000)
                }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Posts of idena.social", style = MaterialTheme.typography.titleMedium)
        Text(newestText(cache, head), style = MaterialTheme.typography.bodySmall)
        Text(historyText(cache, feed.size), style = MaterialTheme.typography.bodySmall)
        Text(activity, style = MaterialTheme.typography.bodySmall)
        FilterChip(
            selected = scanning,
            onClick = {
                scanning = !scanning
                scanningFlag.set(scanning)
                SocialStore.setScanning(context, scanning)
            },
            label = { Text("Scan blocks") },
        )
        Composer(enabled = ready) {
            scanNewOnce.set(true)
            wake++
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (feed.isEmpty()) item { Text("No posts found yet.", style = MaterialTheme.typography.bodySmall) }
            items(feed, key = { it.id }) { PostCard(it, states) }
        }
    }
}

private val blockNumber: NumberFormat = NumberFormat.getIntegerInstance()

/** The newest block scanned against the node's block, and how far behind it is (a block every ~20 s). */
private fun newestText(cache: SocialCache?, head: Long?): String {
    val node = head?.let { "node: ${blockNumber.format(it)}" } ?: "node: …"
    if (cache == null || cache.high < cache.low) return "Newest block scanned: none yet · $node"
    val scanned = "Newest block scanned: ${blockNumber.format(cache.high)} · $node"
    val behind = (head ?: return scanned) - cache.high
    if (behind <= 0) return scanned
    val minutes = behind * 20 / 60
    val time = if (minutes >= 120) "about ${minutes / 60} h" else "about $minutes min"
    return "$scanned (${blockNumber.format(behind)} behind, $time)"
}

/** How far back the history scan has come, and the posts found. */
private fun historyText(cache: SocialCache?, posts: Int): String {
    if (cache == null || cache.high < cache.low) return "History: not scanned yet"
    val found = if (posts == 1) "1 post" else "$posts posts"
    if (cache.historyDone) return "History: complete since block ${blockNumber.format(SOCIAL_FIRST_BLOCK)} · $found"
    val percent = (cache.scannedShare * 100).toInt()
    return "History: $percent% scanned, back to block ${blockNumber.format(cache.low)} · $found"
}

/** The post and all its replies. */
private fun withReplies(post: SocialPost): List<SocialPost> = listOf(post) + post.replies.flatMap { withReplies(it) }

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTime(seconds: Long): String =
    Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(timeFormat)

/** "0x1234…abcd". */
private fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"

/** The text of a post; idena.social can keep it (or its media) on IPFS, which this screen does not read yet. */
private fun postText(call: PostCall): String = buildString {
    if (call.message.startsWith("ipfs://")) append("(text stored on IPFS, not shown here)") else append(call.message)
    if (call.hasMedia) append(if (isEmpty()) "(image or video, not shown here)" else "\n(image or video, not shown here)")
}

@Composable
private fun PostCard(post: SocialPost, states: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    val replies = replyCount(post)
    Card(Modifier.fillMaxWidth().clickable(enabled = replies > 0) { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PostBody(post, states)
            val counts = listOfNotNull(
                post.likes.takeIf { it > 0 }?.let { "$LIKE $it" },
                replies.takeIf { it > 0 }?.let { if (expanded) "Hide replies" else if (it == 1) "1 reply" else "$it replies" },
            )
            if (counts.isNotEmpty()) Text(counts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
            if (expanded) Replies(post.replies, states, depth = 1)
        }
    }
}

@Composable
private fun Replies(replies: List<SocialPost>, states: Map<String, String>, depth: Int) {
    for (reply in replies) {
        Column(Modifier.padding(start = (12 * depth).dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HorizontalDivider()
            PostBody(reply, states)
            if (reply.likes > 0) Text("$LIKE ${reply.likes}", style = MaterialTheme.typography.bodySmall)
        }
        Replies(reply.replies, states, depth + 1)
    }
}

@Composable
private fun PostBody(post: SocialPost, states: Map<String, String>) {
    val author = post.call.author
    val state = states[author]?.takeIf { it.isNotEmpty() && it != "Undefined" }
    Text(
        listOfNotNull(shortAddress(author), state, formatTime(post.call.time)).joinToString("  ·  "),
        style = MaterialTheme.typography.labelMedium,
    )
    Text(postText(post.call), style = MaterialTheme.typography.bodyMedium)
}

/**
 * Writes a post: the node estimates the fee, the user confirms, the node signs and sends the makePost
 * call, and the screen checks the new blocks once it is in one.
 */
@Composable
private fun Composer(enabled: Boolean, onPosted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, PostFee>?>(null) }

    fun estimate() {
        message = null
        busy = true
        scope.launch {
            val argument = makePostArgument(text.trim())
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = rpc(context)
                    val from = rpc.call("dna_getCoinbaseAddr") as String
                    from to SocialPosting(rpc).estimate(from, argument)
                }
            }
            busy = false
            result.onSuccess { (from, fee) -> confirm = from to fee }
                .onFailure { message = "The node refused: ${it.message}" }
        }
    }

    fun send(from: String, fee: PostFee) {
        busy = true
        scope.launch {
            val argument = makePostArgument(text.trim())
            val hash = withContext(Dispatchers.IO) { runCatching { SocialPosting(rpc(context)).send(from, argument, fee.maxFee) } }
                .getOrElse {
                    busy = false
                    message = "The node refused: ${it.message}"
                    return@launch
                }
            message = "Post sent, waiting for a block (${hash.take(10)}…)"
            while (!withContext(Dispatchers.IO) { runCatching { rpc(context).isMined(hash) }.getOrDefault(false) }) delay(10_000)
            text = ""
            busy = false
            message = "Posted."
            onPosted()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Write a post") },
            enabled = !busy,
            minLines = 2,
            maxLines = 5,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { estimate() }, enabled = enabled && !busy && text.isNotBlank()) { Text("Post") }
            Spacer(Modifier.width(8.dp))
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    confirm?.let { (from, fee) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Post on idena.social") },
            text = {
                Text(
                    "The post is public and stays on the blockchain for good, signed by your identity " +
                        "${shortAddress(from)}. Fee: about ${fee.fee.setScale(2, RoundingMode.UP).toPlainString()} iDNA " +
                        "(at most ${fee.maxFee.setScale(2, RoundingMode.UP).toPlainString()} iDNA).",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; send(from, fee) }) { Text("Post") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
