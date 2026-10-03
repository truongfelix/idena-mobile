package io.github.truongfelix.idenanode

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import java.math.BigDecimal
import java.text.NumberFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The Social tab: idena.social read from the phone's node ([SocialModel] scans it), and posting with the
 * node's key. Home: the search bar and the feed (newest posts or latest activity, with [ads] in it).
 * Profile: the identity's. Inbox: the notifications (likes, comments and tips on the identity's posts)
 * and the chats (the contacts). Over them threads, profiles and the editor. The bottom bar opens the lists
 * and the editor; the status is in the app's title row ([SocialInfoDialog]).
 */
@Composable
fun SocialScreen(model: SocialModel, ads: List<ChainAd>, modifier: Modifier = Modifier) {
    // The post being tipped (the amount dialog), and the post whose likes and tips are listed.
    var tipping by remember { mutableStateOf<SocialPost?>(null) }
    var who by remember { mutableStateOf<SocialPost?>(null) }
    // The person being named (the name dialog).
    var naming by remember { mutableStateOf<Person?>(null) }
    val context = LocalContext.current
    val feed = model.feed
    val states = model.states
    val myAddress = model.myAddress
    val canAct = model.ready && !model.busy

    // Back closes the editor, then the open pages, then goes from the Activity or the profile to the feed.
    BackHandler(enabled = model.draft != null || model.pages.isNotEmpty() || model.view != SocialView.Feed) {
        when {
            model.draft != null -> model.draft = null
            model.pages.isNotEmpty() -> model.back()
            else -> model.view = SocialView.Feed
        }
    }

    // The bars hide only over the feed: shown again on any other view, page or the editor.
    LaunchedEffect(model.view, model.pages.size, model.draft != null) {
        if (model.view != SocialView.Feed || model.pages.isNotEmpty() || model.draft != null) model.barsHidden = false
    }

    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    CompositionLocalProvider(LocalNames provides model.names, LocalNow provides now) {
    Column(modifier.fillMaxSize()) {
    val current = model.draft
    Box(Modifier.weight(1f).fillMaxWidth()) {
        val page = model.pages.lastOrNull()
        val historyDone = model.cache?.historyDone == true
        // A like of a post of a list (a reply or comment is liked from its thread).
        val likePost = { post: SocialPost -> model.preparePost("Like", LIKE, PostTarget.onPost(post.id)) }
        val openThread = { threadId: Long, focusId: Long? -> model.open(SocialPage.Thread(threadId, focusId)) }
        when {
            current != null -> Editor(model, current, canPost = canAct)
            page is SocialPage.Thread -> key(page) {
                ThreadView(
                    post = feed.firstOrNull { it.id == page.postId },
                    focusId = page.focusId,
                    pageState = model.pageState(page),
                    states = states,
                    myAddress = myAddress,
                    canAct = canAct,
                    header = { BackRow(model::back) },
                    onProfile = model::openProfile,
                    onLike = { target -> model.preparePost("Like", LIKE, target) },
                    onAnswer = { model.draft = it },
                    onTip = { tipping = it },
                    onWho = { who = it },
                )
            }
            page is SocialPage.Profile -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    ProfileList(
                        address = page.address,
                        feed = feed,
                        states = states,
                        myAddress = myAddress,
                        canAct = canAct,
                        historyDone = historyDone,
                        person = model.person(page.address),
                        onFollow = { model.setPerson(it) },
                        onName = { naming = it },
                        listState = model.pageState(page).list,
                        tab = model.profileTabs[page.address] ?: ProfileTab.Posts,
                        onTab = { model.profileTabs[page.address] = it },
                        onOpen = openThread,
                        onProfile = model::openProfile,
                        onLike = likePost,
                        onTip = { tipping = it },
                        onWho = { who = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.height(2.dp))
                    when (model.view) {
                        SocialView.Feed -> AnimatedVisibility(!model.barsHidden, enter = expandVertically(), exit = shrinkVertically()) {
                            // Slides away with the title row while the feed scrolls down ([SocialModel.barsHidden]).
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SearchField(model)
                                FeedOptions(model)
                                HorizontalDivider()
                            }
                        }
                        SocialView.Activity, SocialView.People -> {
                            InboxOptions(model)
                            HorizontalDivider()
                        }
                        SocialView.Me -> {}
                    }
                    when (model.view) {
                        SocialView.Activity -> ActivityList(model, Modifier.weight(1f)) { item -> openThread(item.threadId, item.focusId) }
                        SocialView.People -> PeopleList(model, Modifier.weight(1f), onName = { naming = it })
                        SocialView.Me -> {
                            if (myAddress == null) Text("Waiting for the node's address…", style = MaterialTheme.typography.bodyMedium)
                            else ProfileList(
                                address = myAddress,
                                feed = feed,
                                states = states,
                                myAddress = myAddress,
                                canAct = canAct,
                                historyDone = historyDone,
                                person = model.person(myAddress),
                                onFollow = { model.setPerson(it) },
                                onName = { naming = it },
                                listState = model.myProfileState,
                                tab = model.profileTabs[myAddress] ?: ProfileTab.Posts,
                                onTab = { model.profileTabs[myAddress] = it },
                                onOpen = openThread,
                                onProfile = model::openProfile,
                                onLike = likePost,
                                onTip = { tipping = it },
                                onWho = { who = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SocialView.Feed -> {
                            val people = model.people
                            val epochStart = model.node?.epochStart
                            val sorted = remember(feed, model.feedSort, model.feedPeriod, model.feedFollowing, people, epochStart) {
                                val shown = if (model.feedFollowing) followingFeed(feed, people) else feed
                                sortFeed(shown, model.feedSort, model.feedPeriod, System.currentTimeMillis() / 1000, epochStart)
                            }
                            val query = model.searchQuery.trim()
                            val hits by produceState<List<SearchHit>?>(null, query, feed, people) {
                                if (query.length < MIN_SEARCH) {
                                    value = null
                                    return@produceState
                                }
                                // Typing: search once it pauses.
                                kotlinx.coroutines.delay(250)
                                value = withContext(Dispatchers.IO) {
                                    searchSocial(feed, people, query, ipfsText = { cid ->
                                        IpfsStore.cached(context, cid)?.takeIf { it.size <= MAX_IPFS_FILE }?.toString(Charsets.UTF_8)
                                    })
                                }
                            }
                            // Scrolling down hides the bars above the feed, scrolling up or reaching the top shows them.
                            val hideBars = remember(model) {
                                object : NestedScrollConnection {
                                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                        if (available.y < -BAR_SCROLL_SLOP) model.barsHidden = true
                                        else if (available.y > BAR_SCROLL_SLOP) model.barsHidden = false
                                        return Offset.Zero
                                    }
                                }
                            }
                            LaunchedEffect(model.feedState) {
                                snapshotFlow { model.feedState.firstVisibleItemIndex == 0 && model.feedState.firstVisibleItemScrollOffset == 0 }
                                    .collect { atTop -> if (atTop) model.barsHidden = false }
                            }
                            LazyColumn(
                                Modifier.weight(1f).nestedScroll(hideBars),
                                state = model.feedState,
                                contentPadding = PaddingValues(bottom = 16.dp),
                            ) {
                                val found = hits
                                if (query.length >= MIN_SEARCH) {
                                    when {
                                        found == null -> item(key = "searching") { Text("Searching…", style = MaterialTheme.typography.bodySmall) }
                                        found.isEmpty() -> item(key = "none") { Text("Nothing found in the posts read so far.", style = MaterialTheme.typography.bodySmall) }
                                        else -> items(found, key = { hitKey(it) }) { hit ->
                                            HitCard(hit, states, onProfile = model::openProfile, onOpen = openThread)
                                        }
                                    }
                                    return@LazyColumn
                                }
                                if (feed.isEmpty()) item(key = "empty") { Text("No posts found yet.", style = MaterialTheme.typography.bodySmall) }
                                else if (sorted.isEmpty()) item(key = "empty") { Text(emptyFeedText(model), style = MaterialTheme.typography.bodySmall) }
                                // An ad after each POSTS_PER_AD posts.
                                items(
                                    feedRows(sorted.size),
                                    key = { row -> feedAdAt(row)?.let { "ad:$it" } ?: sorted[feedPostAt(row)].id },
                                    contentType = { row -> if (feedAdAt(row) != null) "ad" else "post" },
                                ) { row ->
                                    val adNumber = feedAdAt(row)
                                    // A thin line between the rows, as Reddit's feed.
                                    Column {
                                        if (row > 0) HorizontalDivider()
                                        if (adNumber != null) {
                                            FeedAd(ads, adNumber, Modifier.padding(vertical = 8.dp))
                                        } else {
                                            val post = sorted[feedPostAt(row)]
                                            PostCard(
                                                post = post,
                                                states = states,
                                                myAddress = myAddress,
                                                canAct = canAct,
                                                onOpen = { openThread(post.id, null) },
                                                onProfile = model::openProfile,
                                                onLike = { likePost(post) },
                                                onTip = { tipping = post },
                                                onWho = { who = post },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (current == null) BottomBar(model)
    }

    model.confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { model.confirm = null },
            title = { Text("${pending.what} on idena.social") },
            text = { Text(confirmText(pending)) },
            confirmButton = { TextButton(onClick = { model.confirm = null; model.send(pending) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { model.confirm = null }) { Text("Cancel") } },
        )
    }

    tipping?.let { post ->
        TipDialog(
            post = post,
            onDismiss = { tipping = null },
            onTip = { amount ->
                tipping = null
                model.prepare("Tip", SocialPosting.tip(post.id, amount), "Tip $amount iDNA to ${displayName(post.call.author, model.names)} for this post")
            },
        )
    }

    who?.let { post ->
        WhoDialog(post, states, onProfile = { who = null; model.openProfile(it) }, onDismiss = { who = null })
    }

    naming?.let { person ->
        NameDialog(person, onDismiss = { naming = null }) { name ->
            naming = null
            model.setPerson(person.copy(name = name))
        }
    }
    }
}

/** Back, at the top of a thread or a profile. */
@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, Modifier.height(48.dp), contentPadding = PaddingValues(start = 6.dp, end = 14.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The bottom bar's height: the app keeps its notices over it. */
val SOCIAL_BAR_HEIGHT = 60.dp

/**
 * The bottom bar: Home (the feed), the identity's Profile, the Inbox (with what is new), each shown over
 * the open pages, and Post (the editor). A list is marked while it shows with no page over it.
 */
@Composable
private fun BottomBar(model: SocialModel) {
    val unread = model.unread.size
    val onList = model.pages.isEmpty()
    Surface(Modifier.fillMaxWidth().height(SOCIAL_BAR_HEIGHT), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BarItem("Home", Icons.Filled.Home, selected = onList && model.view == SocialView.Feed) { model.showList(SocialView.Feed) }
            BarItem("Profile", Icons.Filled.AccountCircle, selected = onList && model.view == SocialView.Me) { model.showList(SocialView.Me) }
            val inInbox = model.view == SocialView.Activity || model.view == SocialView.People
            BarItem("Inbox", Icons.Filled.Email, selected = onList && inInbox, badge = unread) { model.showList(model.inboxView) }
            BarItem("Post", Icons.Filled.Edit, selected = false) { model.draft = Draft("Post", "New post", PostTarget.NEW_POST, null) }
        }
    }
}

@Composable
private fun RowScope.BarItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
            Icon(icon, contentDescription = null, tint = color)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (selected) FontWeight.Bold else null)
    }
}

/** The details behind the Social status (title row): the node, the blocks read, the history, the last action, and "Scan blocks". */
@Composable
fun SocialInfoDialog(model: SocialModel, onDismiss: () -> Unit) {
    val status = model.status
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.health, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(socialBarLabel(status, model.node?.peers))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val node = model.node
                Text(
                    when {
                        node == null -> "Node: no answer."
                        node.syncing -> "Node: syncing, block ${blockNumber.format(node.currentBlock)} of ${blockNumber.format(node.highestBlock)} · ${peerText(node.peers)}."
                        else -> "Node: block ${model.head?.let { blockNumber.format(it) } ?: "…"} · ${peerText(node.peers)}."
                    },
                )
                Text(newestText(model.cache, model.head) + ".")
                Text(historyText(model.cache, model.feed.size) + ".")
                Text("Scan: ${model.scanStatus}")
                model.lastAction?.let { Text("Last action: $it") }
                HorizontalDivider()
                FilterChip(selected = model.scanning, onClick = { model.setScanningOn(!model.scanning) }, label = { Text("Scan blocks") })
                Text(
                    "On: the new blocks, then the older posts once, then each new block. Off: nothing is read; the posts " +
                        "already read stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DotLegend(
                    Health.Good to "Up to date",
                    Health.Warn to "Behind or reading older posts",
                    Health.Bad to "No node or no peers",
                    Health.Off to "Off, or paused during a validation",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun peerText(peers: Int): String = if (peers == 1) "1 peer" else "$peers peers"

/** What a paid action does and costs: the files stored on IPFS first, each with its fee, then the call. */
private fun confirmText(pending: PendingPost): String = buildString {
    pending.detail?.let { append(it).append(". ") }
    append("It is public and stays on the blockchain for good, signed by your identity ${shortAddress(pending.from)}.")
    for (file in pending.files) {
        append("\n\nStoring the ${file.what} on IPFS (${kilobytes(file.bytes.size)}): about ${fee(file.fee)} iDNA (at most ${fee(file.fee * BigDecimal(2))}).")
    }
    append("\n\n${if (pending.files.isEmpty()) "Fee" else "The post"}: about ${fee(pending.fee.fee)} iDNA (at most ${fee(pending.fee.maxFee)}).")
    if (pending.files.isNotEmpty()) append("\n\nTotal: about ${fee(pending.totalFee)} iDNA.")
}

private fun fee(amount: BigDecimal): String = amount.setScale(2, RoundingMode.UP).toPlainString()

private fun kilobytes(bytes: Int): String = "${(bytes + 999) / 1000} KB"

/**
 * The Activity: what others did on the identity's posts, newest first, the new ones marked; above it,
 * which kinds notify (a count on the Social tab and a notice while the app is open).
 */
@Composable
private fun ActivityList(model: SocialModel, modifier: Modifier, onOpen: (ActivityItem) -> Unit) {
    val items = model.activity
    val newAfter = model.highlightAfter
    LazyColumn(modifier, state = model.activityState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // What to be notified of; the bell says what the chips are for.
            var about by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NotifyKind.entries.forEach { kind ->
                    FilterChip(selected = kind in model.notify, onClick = { model.setNotifyOn(kind, kind !in model.notify) }, label = { Text(kind.title) })
                }
                IconButton(onClick = { about = true }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (about) {
                AlertDialog(
                    onDismissRequest = { about = false },
                    title = { Text("Notifications") },
                    text = { Text("A notice comes for each new like, comment or tip on your posts of the kinds selected. All of them are listed here.") },
                    confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
                )
            }
        }
        if (items.isEmpty()) item { Text("No likes, answers or tips on your posts yet.", style = MaterialTheme.typography.bodyMedium) }
        items(items, key = { "${it.kind}:${it.height}:${it.index}:${it.what.hash}" }) { item ->
            ActivityCard(item, isNew = newAfter != null && item.height > newAfter, onProfile = model::openProfile) { onOpen(item) }
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


/** The time now (Unix seconds), moved on every 30 s while the tab is shown: the posts' ages follow it. */
val LocalNow = compositionLocalOf { System.currentTimeMillis() / 1000 }

/** How long ago [seconds] was ([timeAgo]): 5s, 10min, 7h, 1d, 5mo, 2y. */
@Composable
private fun ago(seconds: Long): String = timeAgo(seconds, LocalNow.current)



/** The names of the contact list by lowercase address, shown instead of the addresses. */
val LocalNames = compositionLocalOf<Map<String, String>> { emptyMap() }

/** "  ·  Human  ·  age 12", the state and its age in the state's color. */
private fun AnnotatedString.Builder.appendStatus(identity: AuthorIdentity, palette: (String) -> Color) {
    append("  ·  ")
    withStyle(SpanStyle(color = palette(identity.state), fontWeight = FontWeight.Medium)) { append(identity.label) }
}

/** How an address shows as a link to its profile: the style, and the names to show. */
class AddressLink(val styles: TextLinkStyles, val names: Map<String, String>)

@Composable
private fun addressLinkStyles() =
    AddressLink(TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)), LocalNames.current)

/** [address], by its name or shortened ([displayName]), as a link that opens its profile. */
private fun AnnotatedString.Builder.appendAddress(address: String, styles: AddressLink, onProfile: (String) -> Unit) {
    withLink(LinkAnnotation.Clickable(address, styles.styles) { onProfile(address) }) { append(displayName(address, styles.names)) }
}

/**
 * The author of a post, reply or comment on two lines next to their avatar ([avatar] high): the name (or
 * the short address) with how long ago at the top right, then the state and age in the state's color. A tap on the avatar
 * or the name opens the profile.
 */
@Composable
private fun PostHeader(
    call: PostCall,
    states: Map<String, AuthorIdentity>,
    onProfile: (String) -> Unit,
    avatar: Dp = 36.dp,
    compact: Boolean = false,
) {
    // No state while the node is asked for it.
    val identity = states[call.author]
    val styles = addressLinkStyles()
    val palette = statusPalette()
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (compact) {
        // The feed's one line, as Reddit's: avatar, name, state, time; the name is cut first.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.author, 24.dp, Modifier.clickable { onProfile(call.author) })
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString { appendAddress(call.author, styles, onProfile) },
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            identity?.let {
                Text(
                    "  ·  " + identityStatus(it.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette(it.state),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text("  ·  " + ago(call.time), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, softWrap = false)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(call.author, avatar, Modifier.clickable { onProfile(call.author) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // The time stays at the top right; the name is cut first.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString { appendAddress(call.author, styles, onProfile) },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(ago(call.time), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, softWrap = false)
            }
            identity?.let {
                Text(
                    it.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette(it.state),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A post of the feed: its start and its action pills; a tap on the text or on 💬 opens the thread, a tap
 * on the address the author's profile.
 */
@Composable
private fun PostCard(
    post: SocialPost,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    onOpen: () -> Unit,
    onProfile: (String) -> Unit,
    onLike: () -> Unit,
    onTip: () -> Unit,
    onWho: () -> Unit,
) {
    // Flat, as Reddit's feed: the list draws a thin line between posts.
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PostHeader(post.call, states, onProfile, compact = true)
            PostContent(post.call, MaterialTheme.typography.bodyLarge, maxLines = FEED_TEXT_LINES, mediaMaxHeight = 220.dp)
        }
        ActionBar(post, myAddress, canAct, "💬 ${replyCount(post)}", answerEnabled = true, onLike = onLike, onAnswer = onOpen, onTip = onTip, onWho = onWho)
    }
}

/** How far (px) a scroll goes before the bars above the feed hide or show. */
private const val BAR_SCROLL_SLOP = 4f

/** The lines of a post's text in the feed; the thread shows all of it. */
const val FEED_TEXT_LINES = 3

/** A small action button, as Reddit's pills under a post (lower than Material's chips). */
@Composable
private fun Pill(text: String, enabled: Boolean, description: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.45f))
    }
}

/**
 * The pills under a post, reply or comment: its likes (Like; "❤️" once the identity liked it), the
 * answer pill ([answer]), and its tips (Tip; not on one's own posts).
 */
@Composable
private fun ActionBar(
    post: SocialPost,
    myAddress: String?,
    canAct: Boolean,
    answer: String,
    answerEnabled: Boolean = canAct,
    onLike: () -> Unit,
    onAnswer: () -> Unit,
    onTip: () -> Unit,
    onWho: () -> Unit,
) {
    val liked = myAddress != null && myAddress in post.likedBy
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(if (liked) "$LIKE ${post.likes}" else "♡ ${post.likes}", canAct && !liked, onClick = onLike)
        Pill(answer, answerEnabled, onClick = onAnswer)
        Pill(if (post.tipTotal > 0) "🪙 ${post.tipTotal}" else "🪙 Tip", canAct && post.call.author != myAddress, onClick = onTip)
        Spacer(Modifier.weight(1f))
        // Who liked and tipped, in place of the line "Liked by · tipped by" under the pills.
        if (post.likes > 0 || post.tips.isNotEmpty()) Pill("👁", enabled = true, description = "Who liked and tipped", onClick = onWho)
    }
}

/** Who liked and who tipped [post], newest first, as idena.social's likes and tips lists; a tap on an address opens its profile. */
@Composable
private fun WhoDialog(post: SocialPost, states: Map<String, AuthorIdentity>, onProfile: (String) -> Unit, onDismiss: () -> Unit) {
    val styles = addressLinkStyles()
    val palette = statusPalette()
    fun AnnotatedString.Builder.who(address: String) {
        appendAddress(address, styles, onProfile)
        states[address]?.let { appendStatus(it, palette) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Likes and tips") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (post.likeCalls.isNotEmpty()) {
                    item { Text(if (post.likes == 1) "$LIKE 1 like" else "$LIKE ${post.likes} likes", style = MaterialTheme.typography.titleSmall) }
                    items(post.likeCalls.sortedByDescending { it.height }, key = { it.hash }) { like ->
                        Text(buildAnnotatedString { who(like.author); append("  ·  ${ago(like.time)}") }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (post.tips.isNotEmpty()) {
                    item {
                        val count = if (post.tips.size == 1) "1 tip" else "${post.tips.size} tips"
                        Text("🪙 ${post.tipTotal} iDNA in $count", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    items(post.tips.sortedByDescending { it.height }, key = { it.hash }) { tip ->
                        Text(
                            buildAnnotatedString { who(tip.from); append("  ·  ${tip.validAmount} iDNA  ·  ${ago(tip.time)}") },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The amount of a tip: whole iDNA (the contract refuses fractions), with the balance for reference. */
@Composable
private fun TipDialog(post: SocialPost, onDismiss: () -> Unit, onTip: (Long) -> Unit) {
    val context = LocalContext.current
    var amount by remember { mutableStateOf("1") }
    var balance by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        balance = withContext(Dispatchers.IO) {
            runCatching {
                val rpc = rpc(context)
                SocialPosting(rpc).balance(rpc.call("dna_getCoinbaseAddr") as String).setScale(2, RoundingMode.DOWN).toPlainString()
            }.getOrNull()
        }
    }
    val value = amount.toLongOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tip ${displayName(post.call.author, LocalNames.current)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The tip goes to the author of this post, through the idena.social contract. Whole iDNA only.")
                balance?.let { Text("Your balance: $it iDNA", style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { text -> amount = text.filter { it.isDigit() }.take(9) },
                    label = { Text("iDNA") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onTip) }, enabled = value != null) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One entry of the Activity list, marked when [isNew]; a tap opens its thread, a tap on the address its profile. */
@Composable
private fun ActivityCard(item: ActivityItem, isNew: Boolean, onProfile: (String) -> Unit, onOpen: () -> Unit) {
    val action = when (item.kind) {
        ActivityKind.Like -> "liked"
        ActivityKind.Reply -> "replied to"
        ActivityKind.Comment -> "commented on"
        ActivityKind.Tip -> "tipped you ${item.amount} iDNA for"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val styles = addressLinkStyles()
            if (isNew) Text("● New", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.actor, 32.dp, Modifier.clickable { onProfile(item.actor) })
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString {
                        appendAddress(item.actor, styles, onProfile)
                        append(" $action your post  ·  ${ago(item.time)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PostContent(item.what, MaterialTheme.typography.bodyMedium, maxLines = 2, showMedia = false)
            item.answer?.let {
                HorizontalDivider()
                PostContent(it, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
            }
        }
    }
}

private fun SocialProfile.list(tab: ProfileTab): List<ProfileItem> = when (tab) {
    ProfileTab.Posts -> posts
    ProfileTab.Replies -> replies
    ProfileTab.Comments -> comments
    ProfileTab.Likes -> likes
    ProfileTab.Tips -> tips
    ProfileTab.Media -> media
}

/**
 * The profile of [address], as idena.social's: the identity (state, age and stake from the node), what
 * it received and gave, then one list at a time ([tab]): its posts with their pills as in the feed;
 * its replies, comments, likes, tips and media as entries that open their thread at them.
 */
@Composable
private fun ProfileList(
    address: String,
    feed: List<SocialPost>,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    historyDone: Boolean,
    person: Person,
    onFollow: (Person) -> Unit,
    onName: (Person) -> Unit,
    listState: LazyListState,
    tab: ProfileTab,
    onTab: (ProfileTab) -> Unit,
    onOpen: (threadId: Long, focusId: Long?) -> Unit,
    onProfile: (String) -> Unit,
    onLike: (SocialPost) -> Unit,
    onTip: (SocialPost) -> Unit,
    onWho: (SocialPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profile = remember(feed, address) { socialProfile(feed, address) }
    // Null while the node is asked.
    var identity by remember(address) { mutableStateOf<Result<ProfileIdentity>?>(null) }
    LaunchedEffect(address) {
        identity = withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identity(address) } }
    }
    val items = profile.list(tab)
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        item { ProfileHeader(address, identity, profile, isMe = address == myAddress, historyDone = historyDone, person, onFollow, onName) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTab.entries.forEach {
                    FilterChip(selected = tab == it, onClick = { onTab(it) }, label = { Text("${it.title} ${profile.list(it).size}") })
                }
            }
        }
        if (items.isEmpty()) item { Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium) }
        if (tab == ProfileTab.Posts) {
            items(items, key = { it.node.id }) { item ->
                PostCard(
                    post = item.node,
                    states = states,
                    myAddress = myAddress,
                    canAct = canAct,
                    onOpen = { onOpen(item.threadId, null) },
                    onProfile = onProfile,
                    onLike = { onLike(item.node) },
                    onTip = { onTip(item.node) },
                    onWho = { onWho(item.node) },
                )
            }
        } else {
            items(items, key = { "${it.node.id}:${it.like?.hash ?: it.tip?.hash ?: ""}" }) { item ->
                ProfileItemCard(tab, item, states, onProfile) { onOpen(item.threadId, item.node.id) }
            }
        }
    }
}

/**
 * The identity of a profile, compact: the avatar; then the name (or the short address) with ✎ and Follow,
 * the short address with copy, the state, age and stake in the state's color; under them the likes and
 * the tips, received and given.
 */
@Composable
private fun ProfileHeader(
    address: String,
    identity: Result<ProfileIdentity>?,
    profile: SocialProfile,
    isMe: Boolean,
    historyDone: Boolean,
    person: Person,
    onFollow: (Person) -> Unit,
    onName: (Person) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(address, 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val title = person.name.ifEmpty { shortAddress(address) }
                    Text(
                        if (isMe) "$title (you)" else title,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Without a name the title is the short address: copy it there.
                    if (person.name.isEmpty()) CopyAddressButton(address)
                    SmallIconButton(Icons.Filled.Edit, if (person.name.isEmpty()) "Name" else "Rename", muted) { onName(person) }
                    Spacer(Modifier.weight(1f))
                    if (!isMe) {
                        FilterChip(
                            selected = person.following,
                            onClick = { onFollow(person.copy(following = !person.following)) },
                            label = { Text(if (person.following) "Following" else "Follow") },
                        )
                    }
                }
                if (person.name.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shortAddress(address), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = muted)
                        CopyAddressButton(address)
                    }
                }
                val palette = statusPalette()
                Text(
                    identity?.fold(
                        {
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = palette(it.state), fontWeight = FontWeight.Medium)) {
                                    append("${it.status} · age ${it.age} · stake ${blockNumber.format(it.stake.setScale(0, RoundingMode.DOWN))} iDNA")
                                }
                            }
                        },
                        { AnnotatedString("Identity: the node did not answer (${it.message}).") },
                    ) ?: AnnotatedString("Identity: asking the node…"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val counts = MaterialTheme.typography.bodyMedium
        Text("$LIKE ${blockNumber.format(profile.likesReceived)} received · ${blockNumber.format(profile.likes.size)} given", style = counts)
        Text("🪙 ${blockNumber.format(profile.tipsReceived)} received · ${blockNumber.format(profile.tipsGiven)} given iDNA", style = counts)
        if (!historyDone) {
            Text(
                "From the blocks scanned so far: older posts appear as the history scan goes on.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}

/**
 * An entry of a profile's list other than its posts: a reply or comment with what it answers, a like or
 * a tip with the post concerned, or a post with media. A tap opens the thread at it.
 */
@Composable
private fun ProfileItemCard(
    tab: ProfileTab,
    item: ProfileItem,
    states: Map<String, AuthorIdentity>,
    onProfile: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val call = item.node.call
    val styles = addressLinkStyles()
    val muted = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tab) {
                ProfileTab.Replies, ProfileTab.Comments -> {
                    item.parent?.let { parent ->
                        Text(
                            buildAnnotatedString {
                                append(if (tab == ProfileTab.Replies) "Reply to " else "Comment on ")
                                appendAddress(parent.author, styles, onProfile)
                            },
                            style = muted,
                        )
                        PostContent(parent, muted, maxLines = 2, showMedia = false)
                        HorizontalDivider()
                    }
                    PostHeader(call, states, onProfile)
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 6)
                }
                ProfileTab.Likes -> {
                    val like = item.like ?: return@Column
                    Text(
                        buildAnnotatedString {
                            append("$LIKE Liked ")
                            appendAddress(call.author, styles, onProfile)
                            append("  ·  ${ago(like.time)}")
                        },
                        style = muted,
                    )
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
                }
                ProfileTab.Tips -> {
                    val tip = item.tip ?: return@Column
                    Text(
                        buildAnnotatedString {
                            append("🪙 Tipped ${tip.validAmount} iDNA to ")
                            appendAddress(call.author, styles, onProfile)
                            append("  ·  ${ago(tip.time)}")
                        },
                        style = muted,
                    )
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
                }
                ProfileTab.Posts, ProfileTab.Media -> {
                    PostHeader(call, states, onProfile)
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 6)
                }
            }
            Text(
                "♡ ${item.node.likes}  ·  💬 ${replyCount(item.node)}  ·  🪙 ${item.node.tipTotal}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A post with its answers, as Reddit shows a post and its comments: the replies, and under each reply
 * its comments nested by what they answer ([commentTree]), each level foldable. Every item has its pills.
 * Opened at [focusId] (from a profile or the Activity), the thread scrolls to it once and marks it.
 */
@Composable
private fun ThreadView(
    post: SocialPost?,
    focusId: Long?,
    pageState: PageState,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    header: @Composable () -> Unit,
    onProfile: (String) -> Unit,
    onLike: (PostTarget) -> Unit,
    onAnswer: (Draft) -> Unit,
    onTip: (SocialPost) -> Unit,
    onWho: (SocialPost) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        header()
        if (post == null) {
            Text("This post is not in the scanned blocks.", Modifier.padding(16.dp))
            return
        }
        LaunchedEffect(post.id) {
            if (pageState.focused || focusId == null) return@LaunchedEffect
            pageState.focused = true
            // Item 0 is the post, then one item per reply with its comments.
            val index = post.replies.indexOfFirst { reply -> reply.id == focusId || reply.replies.any { it.id == focusId } }
            if (index >= 0) pageState.list.scrollToItem(index + 1)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = pageState.list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PostHeader(post.call, states, onProfile)
                    PostContent(post.call, MaterialTheme.typography.bodyLarge)
                    ActionBar(post, myAddress, canAct, "💬 Reply", onLike = { onLike(PostTarget.onPost(post.id)) }, onAnswer = {
                        onAnswer(Draft("Reply", "Reply", PostTarget.onPost(post.id), post.call))
                    }, onTip = { onTip(post) }, onWho = { onWho(post) })
                    HorizontalDivider()
                    val answers = replyCount(post)
                    Text(if (answers == 1) "1 answer" else "$answers answers", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(post.replies, key = { it.id }) { reply ->
                val tree = remember(reply) { commentTree(reply.id, reply.replies) }
                val thread = ThreadContext(states, myAddress, canAct, focusId, pageState.collapsed, onProfile, onLike, onAnswer, onTip, onWho)
                ThreadItem(reply, tree, depth = 0, replyId = reply.id, thread = thread)
            }
        }
    }
}

/** What every reply and comment of a thread shares: who is shown how, what is folded, and the actions. */
private class ThreadContext(
    val states: Map<String, AuthorIdentity>,
    val myAddress: String?,
    val canAct: Boolean,
    val focusId: Long?,
    val collapsed: MutableMap<Long, Boolean>,
    val onProfile: (String) -> Unit,
    val onLike: (PostTarget) -> Unit,
    val onAnswer: (Draft) -> Unit,
    val onTip: (SocialPost) -> Unit,
    val onWho: (SocialPost) -> Unit,
)

/** The width of the rail left of a comment's answers; a tap on it folds the comment. */
private val RAIL = 18.dp

/** Deeper answers are not indented further (the screen is narrow). */
private const val MAX_INDENT = 6

/**
 * A reply ([depth] 0) or a comment, then its answers behind a rail, as Reddit nests comments. A tap on
 * the header or on the rail folds it and everything under it to one line ("name · 4d · text…"); a tap on
 * that line unfolds it. Each level folds on its own, like the rungs of a ladder.
 */
@Composable
private fun ThreadItem(item: SocialPost, children: List<CommentNode>, depth: Int, replyId: Long, thread: ThreadContext) {
    val folded = thread.collapsed[item.id] == true
    val toggle = { if (folded) thread.collapsed.remove(item.id) else thread.collapsed[item.id] = true }
    val isReply = depth == 0
    Column(Modifier.fillMaxWidth().padding(top = if (isReply) 8.dp else 4.dp)) {
        if (folded) {
            FoldedLine(item, hidden = children.sumOf { 1 + it.descendants }, onProfile = thread.onProfile, onUnfold = { toggle() })
            return@Column
        }
        Column(Modifier.focused(item.id == thread.focusId), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().clickable { toggle() }) {
                PostHeader(item.call, thread.states, thread.onProfile, avatar = if (isReply) 36.dp else 28.dp)
            }
            PostContent(item.call, MaterialTheme.typography.bodyLarge)
            val target = if (isReply) PostTarget.onReply(item.id) else PostTarget.onComment(replyId, item.id)
            ActionBar(
                item, thread.myAddress, thread.canAct, "💬 Reply",
                onLike = { thread.onLike(target) },
                onAnswer = { thread.onAnswer(Draft("Comment", "Comment", target, item.call)) },
                onTip = { thread.onTip(item) },
                onWho = { thread.onWho(item) },
            )
        }
        if (children.isEmpty()) return@Column
        val indent = depth < MAX_INDENT
        val line = MaterialTheme.colorScheme.outlineVariant
        val railPx = with(LocalDensity.current) { RAIL.toPx() }
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (!indent) Modifier
                    else Modifier
                        // The rail: a thin line, and a tap beside the answers folds this item.
                        .drawBehind { drawLine(line, Offset(railPx / 2, 0f), Offset(railPx / 2, size.height), strokeWidth = 2.dp.toPx()) }
                        .pointerInput(item.id, folded) { detectTapGestures { if (it.x < railPx) toggle() } }
                        .padding(start = RAIL),
                ),
        ) {
            children.forEach { node -> key(node.comment.id) { ThreadItem(node.comment, node.children, depth + 1, replyId, thread) } }
        }
    }
}

/** A folded reply or comment: its author, its time, the start of its text and how many answers it hides. */
@Composable
private fun FoldedLine(item: SocialPost, hidden: Int, onProfile: (String) -> Unit, onUnfold: () -> Unit) {
    val styles = addressLinkStyles()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val preview = when {
        ipfsCid(item.call.message) != null -> "Text on IPFS"
        item.call.message.isEmpty() && item.call.hasMedia -> "🖼 Media"
        else -> item.call.message.replace(Regex("\\s+"), " ").trim()
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onUnfold).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(item.call.author, 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString { appendAddress(item.call.author, styles, onProfile) },
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("  ·  ${ago(item.call.time)}  ·  ", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, softWrap = false)
        Text(preview, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hidden > 0) Text("  +$hidden", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The reply or comment a thread was opened at: a light background behind it. */
@Composable
private fun Modifier.focused(focused: Boolean): Modifier =
    if (!focused) this
    else background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)

/**
 * The full-screen editor of a post, reply or comment: the answered message in full above a text box of
 * a few lines, then an image (stored on IPFS, at a size chosen with its cost) and whether the text goes
 * on IPFS; the screen scrolls. The draft stays in [model] when the tab changes.
 */
@Composable
private fun Editor(model: SocialModel, draft: Draft, canPost: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The image being prepared or why it was refused, and the fee per gas for the size choice.
    var imageNote by remember { mutableStateOf<String?>(null) }
    var feePerGas by remember { mutableStateOf<BigDecimal?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageNote = "Preparing the image…"
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { encodeImageSizes(context, uri) } }
                .onSuccess { sizes ->
                    model.draftImage = ImageDraft(sizes, if (DEFAULT_IMAGE_SIZE in sizes) DEFAULT_IMAGE_SIZE else sizes.keys.max())
                    imageNote = null
                }
                .onFailure { imageNote = "This image cannot be used: ${it.message}" }
            if (feePerGas == null) feePerGas = withContext(Dispatchers.IO) { runCatching { SocialPosting(rpc(context)).feePerGas() }.getOrNull() }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val image = model.draftImage
    val text = model.draftText
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.draft = null }) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = model::prepareDraft, enabled = canPost && (text.isNotBlank() || image != null) && imageNote == null) { Text("Post") }
        }
        draft.answering?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${displayName(it.author, model.names)}  ·  ${ago(it.time)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PostContent(it, MaterialTheme.typography.bodyLarge)
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { model.draftText = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(if (draft.answering == null) "What's new?" else "Your answer") },
            minLines = 4,
            maxLines = 10,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (image == null) "🖼 Image" else "🖼 Other image", enabled = imageNote != "Preparing the image…") {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            FilterChip(selected = model.textOnIpfs, onClick = { model.textOnIpfs = !model.textOnIpfs }, label = { Text("Text on IPFS") })
        }
        model.sendStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        imageNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (image != null) {
            DraftImage(image.bytes)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                image.sizes.forEach { (side, bytes) ->
                    FilterChip(
                        selected = side == image.chosen,
                        onClick = { model.draftImage = image.copy(chosen = side) },
                        label = { Text("$side px · ${kilobytes(bytes.size)}") },
                    )
                }
            }
            val cost = feePerGas?.let { "about ${fee(storeToIpfsFeeEstimate(it, image.bytes.size))} iDNA" } ?: "a fee that grows with its size"
            Text(
                "The image is stored on IPFS (a storeToIpfs transaction): $cost, on top of the post's fee. " +
                    "It is sent as WebP, without the photo's details (place, camera).",
                style = MaterialTheme.typography.bodySmall,
            )
            Pill("✕ Remove the image", enabled = true) { model.draftImage = null }
        }
        if (model.textOnIpfs) {
            Text(
                "The text is stored on IPFS (a storeToIpfs transaction, cheaper than the post for long texts) and the " +
                    "post holds its address. It stays readable while IPFS nodes keep it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Posts are public and stay on the blockchain for good. The fees are shown before sending.",
            style = MaterialTheme.typography.bodySmall,
        )
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
            contentDescription = "The image of the post",
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

/** The feed's search bar, a rounded bar: texts of the posts read, contact names, or a pasted address. */
@Composable
private fun SearchField(model: SocialModel) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = model.searchQuery,
        onValueChange = {
            model.searchQuery = it.take(200)
            // The results show in the feed.
            if (model.view != SocialView.Feed) model.showFeed(following = model.feedFollowing)
        },
        modifier = Modifier.fillMaxWidth().height(40.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().background(colors.surfaceContainerHighest, RoundedCornerShape(20.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (model.searchQuery.isEmpty()) Text("Search", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    field()
                }
                if (model.searchQuery.isNotEmpty()) {
                    TextButton(onClick = { model.searchQuery = "" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
                } else Spacer(Modifier.width(12.dp))
            }
        },
    )
}

/** Home's lists: Feed and Following (all posts, or the people followed), then the order and the period of a ranked order as menus. */
@Composable
private fun FeedOptions(model: SocialModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = !model.feedFollowing, onClick = { model.showFeed(following = false) }, label = { Text("Feed") })
        FilterChip(selected = model.feedFollowing, onClick = { model.showFeed(following = true) }, label = { Text("Following") })
        if (model.searchQuery.trim().length < MIN_SEARCH) {
            MenuChip(model.feedSort.title, FeedSort.entries, { it.title }, model::sortFeedBy)
            if (model.feedSort.ranked) MenuChip(model.feedPeriod.title, FeedPeriod.entries, { it.title }) { model.feedPeriod = it }
        }
    }
}

/** The Inbox's lists: Notifications (likes, comments and tips on the identity's posts, with what is new) and Contacts. */
@Composable
private fun InboxOptions(model: SocialModel) {
    val unread = model.unread.size
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = model.view == SocialView.Activity,
            onClick = { model.showList(SocialView.Activity) },
            label = { Text(if (unread > 0) "Notifications · $unread new" else "Notifications") },
        )
        FilterChip(selected = model.view == SocialView.People, onClick = { model.showList(SocialView.People) }, label = { Text("Contacts") })
    }
}

/** A chip showing the choice made ([label] ▾); a tap opens the menu of [options]. */
@Composable
private fun <T> MenuChip(label: String, options: List<T>, title: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label  ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(title(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

/** Why the feed shows nothing although posts were read. */
private fun emptyFeedText(model: SocialModel): String {
    val from = if (model.feedFollowing) " from the people you follow" else ""
    return when {
        model.feedFollowing && model.people.values.none { it.following } -> "You follow nobody yet: open a profile and select Follow."
        model.feedSort == FeedSort.Likes -> "No liked post in this period$from."
        model.feedSort == FeedSort.Comments -> "No post with answers in this period$from."
        model.feedSort == FeedSort.Tips -> "No tipped post in this period$from."
        else -> "No posts$from in the posts read so far."
    }
}

private fun hitKey(hit: SearchHit): String = when (hit) {
    is SearchHit.Address -> "address:${hit.address}"
    is SearchHit.Named -> "person:${hit.person.address}"
    is SearchHit.Text -> "text:${hit.node.id}"
}

/** One search result: a profile to open (an address, a person), or a text opening its thread at it. */
@Composable
private fun HitCard(
    hit: SearchHit,
    states: Map<String, AuthorIdentity>,
    onProfile: (String) -> Unit,
    onOpen: (threadId: Long, focusId: Long?) -> Unit,
) {
    when (hit) {
        is SearchHit.Address -> Card(Modifier.fillMaxWidth().clickable { onProfile(hit.address) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(hit.address, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Open the profile of ${displayName(hit.address, LocalNames.current)}  ›", style = MaterialTheme.typography.titleSmall)
                    Text(shortAddress(hit.address), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        is SearchHit.Named -> PersonRow(hit.person) { onProfile(hit.person.address) }
        is SearchHit.Text -> Card(Modifier.fillMaxWidth().clickable { onOpen(hit.threadId, hit.node.id.takeIf { it != hit.threadId }) }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PostHeader(hit.node.call, states, onProfile)
                PostContent(hit.node.call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
            }
        }
    }
}

/** A contact: the avatar, the name (or the short address), the short address under a name; a tap opens the profile. */
@Composable
private fun PersonRow(person: Person, trailing: @Composable () -> Unit = {}, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(person.address, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.name.ifEmpty { shortAddress(person.address) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Following shows on the row's chip.
                if (person.name.isNotEmpty()) {
                    Text(shortAddress(person.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing()
        }
    }
}

/**
 * The Inbox's Contacts: the names given and the people followed, kept on the phone only.
 * A tap opens the profile; ✎ (name) and Follow there or here. Contacts are added from their profile.
 */
@Composable
private fun PeopleList(model: SocialModel, modifier: Modifier, onName: (Person) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people = remember(model.people) { sortedPeople(model.people) }
    // What the last export or import did, and whether the explanations (ⓘ) are open.
    var note by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Contacts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Names and follows are private: they stay on this phone and are never published. To add someone, open " +
                            "their profile (a tap on an address, or paste the address in the feed's search) and tap ✎ (name) or Follow.",
                    )
                    Text(
                        "Export writes the list to a file of your choice (to keep it, or to move it to another phone); whoever " +
                            "gets the file sees your names. Import adds a file's contacts to this list: nothing here is removed.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
        )
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val list = model.people
        scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("the file cannot be written")
                    out.use { it.write(peopleToJson(list).toString(2).toByteArray(Charsets.UTF_8)) }
                }.fold({ "Exported ${peopleCount(list.size)}." }, { "Export failed: ${it.message}" })
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the file cannot be read")
                    val bytes = input.use { stream ->
                        // At most one byte more than allowed (InputStream.readNBytes needs Android 13).
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= MAX_PEOPLE_FILE) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.size > MAX_PEOPLE_FILE) throw IOException("the file is too large")
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (json?.optJSONArray("people") == null) throw IOException("this is not an exported contact list")
                    peopleFromJson(json)
                }
            }
            note = read.fold(
                { imported ->
                    val result = model.importPeople(imported)
                    "Imported: ${peopleCount(result.added)} added, ${result.changed} changed."
                },
                { "Import failed: ${it.message}" },
            )
        }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "about") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Export", enabled = people.isNotEmpty()) { exporter.launch("idena-contacts.json") }
                    Pill("Import", enabled = true) { importer.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { about = true }) { Text("ⓘ", fontSize = 20.sp) }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (people.isEmpty()) item(key = "empty") { Text("No contacts yet.", style = MaterialTheme.typography.bodyMedium) }
        items(people, key = { it.address }) { person ->
            PersonRow(person, trailing = {
                IconButton(onClick = { onName(person) }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                FilterChip(
                    selected = person.following,
                    onClick = { model.setPerson(person.copy(following = !person.following)) },
                    label = { Text(if (person.following) "Following" else "Follow") },
                )
            }) { model.openProfile(person.address) }
        }
    }
}

/** The largest contact file imported (about 10,000 contacts). */
private const val MAX_PEOPLE_FILE = 2_000_000

private fun peopleCount(n: Int): String = if (n == 1) "1 contact" else "$n contacts"

/** A private name for [person]'s address; an empty name removes it. */
@Composable
private fun NameDialog(person: Person, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(person.address) { mutableStateOf(person.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person.name.isEmpty()) "Name ${shortAddress(person.address)}" else "Rename ${person.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shown instead of the address in the Social tab. It stays on this phone: nobody else sees it.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.takeWhole(Person.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(cleanName(name)) }) { Text(if (cleanName(name).isEmpty() && person.name.isNotEmpty()) "Remove the name" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
