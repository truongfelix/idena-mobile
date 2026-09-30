package io.github.truongfelix.idenanode

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val scope = rememberCoroutineScope()
    // The open thread (a post id), the editor's draft, and a paid action waiting for confirmation.
    var threadId by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var draftText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<PendingPost?>(null) }
    var busy by remember { mutableStateOf(false) }
    // What happened to the last paid action, shown at the top of the tab.
    var sendStatus by remember { mutableStateOf<String?>(null) }
    var myAddress by remember { mutableStateOf<String?>(null) }

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

    /** Asks the node for the fee of [message] answering [target], then for the user's confirmation. */
    fun prepare(what: String, message: String, target: PostTarget) {
        busy = true
        sendStatus = null
        scope.launch {
            val argument = makePostArgument(message, target.replyTo, target.channel)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = rpc(context)
                    val from = rpc.call("dna_getCoinbaseAddr") as String
                    from to SocialPosting(rpc).estimate(from, argument)
                }
            }
            busy = false
            result.onSuccess { (from, fee) -> confirm = PendingPost(what, argument, from, fee) }
                .onFailure { sendStatus = "$what: the node refused: ${it.message}" }
        }
    }

    /** Sends a confirmed action, then scans the new blocks once it is in one. */
    fun send(pending: PendingPost) {
        busy = true
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { SocialPosting(rpc(context)).send(pending.from, pending.argument, pending.fee.maxFee) }
            }.getOrElse {
                busy = false
                sendStatus = "${pending.what}: the node refused: ${it.message}"
                return@launch
            }
            busy = false
            if (draft != null) {
                draft = null
                draftText = ""
            }
            sendStatus = "${pending.what} sent, waiting for a block (${hash.take(10)}…)"
            while (!withContext(Dispatchers.IO) { runCatching { rpc(context).isMined(hash) }.getOrDefault(false) }) delay(10_000)
            sendStatus = "${pending.what} is in a block."
            scanNewOnce.set(true)
            wake++
        }
    }

    LaunchedEffect(Unit) {
        myAddress = withContext(Dispatchers.IO) { runCatching { rpc(context).call("dna_getCoinbaseAddr") as String }.getOrNull() }?.lowercase()
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

    BackHandler(enabled = draft != null || threadId != null) {
        if (draft != null) draft = null else threadId = null
    }

    Box(modifier.fillMaxSize()) {
        val current = draft
        val thread = threadId?.let { id -> feed.firstOrNull { it.id == id } }
        when {
            current != null -> Editor(
                draft = current,
                text = draftText,
                onText = { draftText = it },
                canPost = ready && !busy,
                onCancel = { draft = null },
                onPost = { prepare(current.what, draftText.trim(), current.target) },
            )
            threadId != null -> ThreadView(
                post = thread,
                states = states,
                myAddress = myAddress,
                canAct = ready && !busy,
                waitReason = activity.takeUnless { ready },
                sendStatus = sendStatus,
                onBack = { threadId = null },
                onLike = { target -> prepare("Like", LIKE, target) },
                onAnswer = { draft = it },
            )
            else -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.width(0.dp))
                    Text("Posts of idena.social", style = MaterialTheme.typography.titleMedium)
                    Text(newestText(cache, head), style = MaterialTheme.typography.bodySmall)
                    Text(historyText(cache, feed.size), style = MaterialTheme.typography.bodySmall)
                    Text(activity, style = MaterialTheme.typography.bodySmall)
                    sendStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    FilterChip(
                        selected = scanning,
                        onClick = {
                            scanning = !scanning
                            scanningFlag.set(scanning)
                            SocialStore.setScanning(context, scanning)
                        },
                        label = { Text("Scan blocks") },
                    )
                    HorizontalDivider()
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Room for the write button over the last post.
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        if (feed.isEmpty()) item { Text("No posts found yet.", style = MaterialTheme.typography.bodySmall) }
                        if (!ready) item { WaitNote(activity) }
                        items(feed, key = { it.id }) { post ->
                            PostCard(
                                post = post,
                                states = states,
                                myAddress = myAddress,
                                canAct = ready && !busy,
                                onOpen = { threadId = post.id },
                                onLike = { prepare("Like", LIKE, PostTarget.onPost(post.id)) },
                                onReply = { draft = Draft("Reply", "Reply", PostTarget.onPost(post.id), post.call) },
                            )
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { draft = Draft("Post", "New post", PostTarget.NEW_POST, null) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) { Text("✎", fontSize = 22.sp) }
            }
        }
    }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${pending.what} on idena.social") },
            text = {
                Text(
                    "It is public and stays on the blockchain for good, signed by your identity " +
                        "${shortAddress(pending.from)}. Fee: about ${pending.fee.fee.setScale(2, RoundingMode.UP).toPlainString()} iDNA " +
                        "(at most ${pending.fee.maxFee.setScale(2, RoundingMode.UP).toPlainString()} iDNA).",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; send(pending) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** A post, reply, comment or like waiting for the user's confirmation, with the node's fee estimate. */
private data class PendingPost(val what: String, val argument: String, val from: String, val fee: PostFee)

/** What the editor writes: the action's name, the editor's title, what it answers and that text. */
private data class Draft(val what: String, val title: String, val target: PostTarget, val answering: PostCall?)

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

/** "0x1234…abcd · Human · 30 Sep 2026, 10:05". */
@Composable
private fun PostHeader(call: PostCall, states: Map<String, String>) {
    val state = states[call.author]?.takeIf { it.isNotEmpty() && it != "Undefined" }
    Text(
        listOfNotNull(shortAddress(call.author), state, formatTime(call.time)).joinToString("  ·  "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Why Like and the answers are off: the node must be synced and outside a validation. */
@Composable
private fun WaitNote(reason: String) {
    Text("Like and reply need the node synced. $reason", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** A post of the feed: its start, its answer count, Like and Reply; a tap on the text opens the thread. */
@Composable
private fun PostCard(
    post: SocialPost,
    states: Map<String, String>,
    myAddress: String?,
    canAct: Boolean,
    onOpen: () -> Unit,
    onLike: () -> Unit,
    onReply: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PostHeader(post.call, states)
                Text(postText(post.call), style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
                val replies = replyCount(post)
                if (replies > 0) {
                    Text(if (replies == 1) "💬 1 answer · open" else "💬 $replies answers · open", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Actions(post, myAddress, canAct, "Reply", onLike, onReply)
        }
    }
}

/** Like and answer buttons; "Liked" once the identity liked it. */
@Composable
private fun Actions(post: SocialPost, myAddress: String?, canAct: Boolean, answer: String, onLike: () -> Unit, onAnswer: () -> Unit) {
    val liked = myAddress != null && myAddress in post.likedBy
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onLike, enabled = canAct && !liked, modifier = Modifier.height(48.dp)) {
            Text(if (liked) "$LIKE ${post.likes}" else "♡ ${post.likes}  Like", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onAnswer, enabled = canAct, modifier = Modifier.height(48.dp)) {
            Text("💬 $answer", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * A post with its replies and, under each reply, its comments (idena.social's levels), each with Like
 * and answer buttons.
 */
@Composable
private fun ThreadView(
    post: SocialPost?,
    states: Map<String, String>,
    myAddress: String?,
    canAct: Boolean,
    waitReason: String?,
    sendStatus: String?,
    onBack: () -> Unit,
    onLike: (PostTarget) -> Unit,
    onAnswer: (Draft) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        sendStatus?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        waitReason?.let { Column(Modifier.padding(horizontal = 16.dp)) { WaitNote(it) } }
        if (post == null) {
            Text("This post is not in the scanned blocks.", Modifier.padding(16.dp))
            return
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PostHeader(post.call, states)
                        Text(postText(post.call), style = MaterialTheme.typography.bodyLarge)
                        Actions(post, myAddress, canAct, "Reply", { onLike(PostTarget.onPost(post.id)) }) {
                            onAnswer(Draft("Reply", "Reply", PostTarget.onPost(post.id), post.call))
                        }
                    }
                }
            }
            if (post.replies.isEmpty()) item { Text("No replies yet.", style = MaterialTheme.typography.bodySmall) }
            items(post.replies, key = { it.id }) { reply ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider()
                    PostHeader(reply.call, states)
                    Text(postText(reply.call), style = MaterialTheme.typography.bodyLarge)
                    Actions(reply, myAddress, canAct, "Comment", { onLike(PostTarget.onReply(reply.id)) }) {
                        onAnswer(Draft("Comment", "Comment", PostTarget.onReply(reply.id), reply.call))
                    }
                    for (comment in reply.replies) {
                        Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            PostHeader(comment.call, states)
                            Text(postText(comment.call), style = MaterialTheme.typography.bodyLarge)
                            Actions(comment, myAddress, canAct, "Answer", { onLike(PostTarget.onComment(reply.id, comment.id)) }) {
                                onAnswer(Draft("Comment", "Comment", PostTarget.onComment(reply.id, comment.id), comment.call))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The full-screen editor of a post, reply or comment. */
@Composable
private fun Editor(draft: Draft, text: String, onText: (String) -> Unit, canPost: Boolean, onCancel: () -> Unit, onPost: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = onPost, enabled = canPost && text.isNotBlank()) { Text("Post") }
        }
        draft.answering?.let {
            Text(
                "Answering ${shortAddress(it.author)}: ${postText(it)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
            placeholder = { Text("What's new?") },
        )
        Text(
            "Posts are public and stay on the blockchain for good. The fee is shown before sending.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
