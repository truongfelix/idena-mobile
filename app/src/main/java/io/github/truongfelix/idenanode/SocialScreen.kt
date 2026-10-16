package io.github.truongfelix.idenanode

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.semantics.contentDescription
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The Social tab: idena.social read from the phone's node ([SocialModel] scans it), and posting with the
 * node's key. Feed: the search bar and the feed (newest posts or latest activity, with [ads] in it).
 * Messages: the conversations. Notifications: likes, comments and tips on the identity's posts. Profile:
 * the identity's. Over them threads, profiles, conversations and the editor. The bottom bar opens the lists
 * and the editor; the status is in the app's title row ([SocialInfoDialog]).
 */
@Composable
fun SocialScreen(model: SocialModel, ads: List<ChainAd>, topBar: @Composable () -> Unit, modifier: Modifier = Modifier) {
    // The post being tipped (the amount dialog), and the post whose likes and tips are listed.
    var tipping by remember { mutableStateOf<SocialPost?>(null) }
    var who by remember { mutableStateOf<SocialPost?>(null) }
    // The person being named (the name dialog).
    var naming by remember { mutableStateOf<Person?>(null) }
    // The feed's filter sheet is open.
    var filtering by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val feed = model.shownFeed
    val states = model.states
    val myAddress = model.myAddress
    val canAct = model.ready && !model.sending.busy

    // Back closes the editor, then the open pages, then goes from the Activity or the profile to the feed.
    BackHandler(enabled = model.sending.draft != null || model.pages.isNotEmpty() || model.view != SocialView.Feed) {
        when {
            model.sending.draft != null -> model.sending.draft = null
            model.pages.isNotEmpty() -> model.back()
            else -> model.view = SocialView.Feed
        }
    }

    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    CompositionLocalProvider(
        LocalNames provides model.names,
        LocalHiddenWords provides HiddenWords(model.wordFilter, myAddress),
        LocalNow provides now,
        LocalPendingLikes provides model.sending.sent.likes,
        LocalOpenTopic provides model::openTopic,
        LocalOpenChat provides ChatAccess(
            open = { address -> model.openChat(listOf(address.lowercase())) },
            canReceive = { address -> model.messageKeys[address.lowercase()]?.isNotEmpty() },
            lookUp = { address -> model.lookUpKeys(listOf(address)) },
        ),
    ) {
    Column(modifier.fillMaxSize()) {
    val current = model.sending.draft
    val page = model.pages.lastOrNull()
    // The full editor shows a new post; a reply on its thread stays in the reply bar until 🖼 opens the editor.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(current == null) { if (current == null) expanded = false }
    val editing = current?.takeIf { it.target == PostTarget.NEW_POST || page !is SocialPage.Thread || expanded }
    // Over the feed, the title row floats with the search (see the feed below); elsewhere it stays on top.
    if (current != null || page != null || model.view != SocialView.Feed) topBar()
    // The bottom bar fades out while the content scrolls down and back in when it scrolls up; it is back on
    // each new page or list.
    var barShown by remember { mutableStateOf(true) }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -BAR_SCROLL_STEP) barShown = false else if (available.y > BAR_SCROLL_STEP) barShown = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(page, model.view) { barShown = true }
    Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(barScroll)) {
        val historyDone = model.cache?.historyDone == true
        // A like of a post of a list (a reply or comment is liked from its thread).
        val likePost = { post: SocialPost -> model.sending.preparePost("Like", LIKE, PostTarget.onPost(post.id)) }
        val openThread = { threadId: Long, focusId: Long? -> model.open(SocialPage.Thread(threadId, focusId)) }
        when {
            editing != null -> Editor(model, editing, canPost = canAct)
            page is SocialPage.Thread -> key(page) {
                // 2 posts of each 5 in a row, at random, show an ad under the post, above the answers.
                val rank = remember(feed, page.postId) { feed.map { it.id }.sorted().binarySearch(page.postId) }
                ThreadView(
                    post = feed.firstOrNull { it.id == page.postId },
                    ad = if (threadShowsAd(rank, model.adSalt)) ({ FeedAd(ads, rank, Modifier.padding(vertical = 8.dp)) }) else null,
                    focusId = page.focusId,
                    pageState = model.pageState(page),
                    states = states,
                    myAddress = myAddress,
                    canAct = canAct,
                    header = { BackRow(model::back) },
                    onProfile = model::openProfile,
                    onLike = { target -> model.sending.preparePost("Like", LIKE, target) },
                    onAnswer = { model.sending.draft = it },
                    onTip = { tipping = it },
                    onWho = { who = it },
                    answeringId = current?.target?.replyTo?.takeIf { current.target.channel != null },
                    footer = { if (!it.isOlder) ReplyBar(model, it, canPost = canAct, onExpand = { expanded = true }) },
                )
            }
            page is SocialPage.Topic -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    TopicView(
                        name = page.name,
                        feed = feed,
                        following = page.name in model.followedTopics,
                        listState = model.pageState(page).list,
                        onFollow = { model.setTopicFollowed(page.name, it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    ) { post ->
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
                            topicShown = TopicShown.Not,
                        )
                    }
                }
            }
            page is SocialPage.Chat -> key(page) {
                ChatView(model, page.key, model.pageState(page).list, canSend = canAct, onBack = model::back, Modifier.fillMaxSize())
            }
            page is SocialPage.Settings -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    SocialSettingsView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.Blocked -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    BlockedView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.HiddenWords -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    HiddenWordsView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.Following -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    FollowingView(model, page.topics, model.pageState(page).list, onName = { naming = it }, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
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
                        onBlock = { model.setPerson(it) },
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
            model.view == SocialView.Feed -> {
                val people = model.people
                val epochStart = model.node?.epochStart
                val topics = model.followedTopics
                val filter = model.feedFilter
                val sorted = remember(feed, filter, people, topics, epochStart) {
                    filter.apply(feed, people, topics, System.currentTimeMillis() / 1000, epochStart)
                }
                val query = model.searchQuery.trim()
                // "r/…" lists the topics instead of searching the posts.
                val topicSearch = query.startsWith(TOPIC_PREFIX)
                val hits by produceState<List<SearchHit>?>(null, query, feed, people) {
                    if (query.length < MIN_SEARCH || topicSearch) {
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
                // The title row, the search and the options float over the feed and slide with the scroll,
                // as Reddit's: they never push the posts. Back at the top they are all there.
                var headerHeight by remember { mutableIntStateOf(0) }
                var headerOffset by remember { mutableFloatStateOf(0f) }
                val slide = remember(model) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            headerOffset = (headerOffset - available.y).coerceIn(0f, headerHeight.toFloat())
                            return Offset.Zero
                        }
                    }
                }
                LaunchedEffect(model.feedState) {
                    snapshotFlow { model.feedState.firstVisibleItemIndex == 0 && model.feedState.firstVisibleItemScrollOffset == 0 }
                        .collect { atTop -> if (atTop) headerOffset = 0f }
                }
                val headerDp = with(LocalDensity.current) { headerHeight.toDp() }
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    LazyColumn(
                        Modifier.fillMaxSize().nestedScroll(slide).padding(horizontal = 16.dp),
                        state = model.feedState,
                        contentPadding = PaddingValues(top = headerDp, bottom = POST_BUTTON_ROOM),
                    ) {
                        if (topicSearch) {
                            topicItems(feed, query, model.followedTopics, model::openTopic, model::setTopicFollowed)
                            return@LazyColumn
                        }
                        val found = hits
                        if (query.length >= MIN_SEARCH) {
                            when {
                                found == null -> item(key = "searching") { Text("Searching…", style = MaterialTheme.typography.bodySmall) }
                                found.isEmpty() -> item(key = "none") { Text("Nothing found in the posts read so far.", style = MaterialTheme.typography.bodySmall) }
                                else -> itemsIndexed(found, key = { _, hit -> hitKey(hit) }) { i, hit ->
                                    HitCard(
                                        hit,
                                        first = i == 0,
                                        states = states,
                                        myAddress = myAddress,
                                        canAct = canAct,
                                        onProfile = model::openProfile,
                                        onOpen = openThread,
                                        onLike = { target -> model.sending.preparePost("Like", LIKE, target) },
                                        onTip = { tipping = it },
                                        onWho = { who = it },
                                    )
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
                                        topicShown = TopicShown.InsteadOfAuthor,
                                    )
                                }
                            }
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerHeight = it.height }
                            .offset { IntOffset(0, -headerOffset.roundToInt()) }
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        topBar()
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spacer(Modifier.height(2.dp))
                            SearchField(model) { FeedFilterButton(filter) { filtering = true } }
                            FeedFilterLine(filter, onOpen = { filtering = true }, onReset = { model.filterFeed(FeedFilter()) })
                            HorizontalDivider()
                        }
                    }
                }
            }

            else -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.height(2.dp))
                    if (model.view == SocialView.Activity) Text("Notifications", style = MaterialTheme.typography.titleLarge)
                    when (model.view) {
                        SocialView.Activity -> ActivityList(model, Modifier.weight(1f)) { item -> openThread(item.threadId, item.focusId) }
                        SocialView.Messages -> MessagesList(model, Modifier.weight(1f))
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
                                onBlock = { model.setPerson(it) },
                                listState = model.myProfileState,
                                mine = MyProfileLinks(
                                    people = followedPeople(model.people).size,
                                    topics = model.followedTopics.size,
                                    onFollowing = { topics -> model.open(SocialPage.Following(topics)) },
                                    onSettings = { model.open(SocialPage.Settings) },
                                ),
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
                        SocialView.Feed -> {}
                    }
                }
            }
        }
        // + writes a new post, from the lists posts are read in (in the topic, on a topic's page); it fades with the bar.
        val topic = (page as? SocialPage.Topic)?.name
        val postHere = current == null && (if (page == null) model.view == SocialView.Feed || model.view == SocialView.Me else topic != null)
        androidx.compose.animation.AnimatedVisibility(postHere && barShown, Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
            PostButton(enabled = canAct) { newPost(model, topic) }
        }
    }
    // A conversation keeps the room for its messages and the keyboard.
    AnimatedVisibility(current == null && barShown && page !is SocialPage.Chat, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        BottomBar(model)
    }
    }

    model.sending.confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { model.sending.confirm = null },
            title = { Text("${pending.what} on idena.social") },
            text = { Text(confirmText(pending)) },
            confirmButton = { TextButton(onClick = { model.sending.confirm = null; model.sending.send(pending) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { model.sending.confirm = null }) { Text("Cancel") } },
        )
    }

    tipping?.let { post ->
        TipDialog(
            post = post,
            onDismiss = { tipping = null },
            onTip = { amount ->
                tipping = null
                model.sending.prepare("Tip", SocialPosting.tip(post.id, amount), "Tip $amount iDNA to ${displayName(post.call.author, model.names)} for this post")
            },
        )
    }

    who?.let { post ->
        WhoDialog(post, states, onProfile = { who = null; model.openProfile(it) }, onDismiss = { who = null })
    }

    if (filtering) {
        FeedFilterSheet(
            filter = model.feedFilter,
            peopleCount = followedPeople(model.people).size,
            topicsCount = model.followedTopics.size,
            onChange = model::filterFeed,
            onList = { topics -> filtering = false; model.open(SocialPage.Following(topics)) },
            onDismiss = { filtering = false },
        )
    }

    naming?.let { person ->
        NameDialog(person, onDismiss = { naming = null }) { name ->
            naming = null
            model.setPerson(person.copy(name = name))
        }
    }
    }
}

/** Opens the editor for a new post, in topic [topic] when given (else in the topic typed last, if any). */
private fun newPost(model: SocialModel, topic: String?) {
    topic?.let { model.sending.draftTopic = it }
    model.sending.draft = Draft("Post", "New post", PostTarget.NEW_POST, null)
}

/** The room a list leaves under its last item for [PostButton]. */
val POST_BUTTON_ROOM = 88.dp

/** + at the bottom right: the editor for a new post. */
@Composable
private fun PostButton(enabled: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) { Icon(Icons.Filled.Add, contentDescription = "New post") }
}

/** Back, at the top of a thread, a profile or a topic. */
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

/** The scroll (pixels in one step) that hides or shows the bottom bar: not a finger's tremor. */
private const val BAR_SCROLL_STEP = 2f

/** The bottom bar's height: the app keeps its notices over it. */
val SOCIAL_BAR_HEIGHT = 60.dp

/**
 * The bottom bar, as the usual social apps': the Feed, Messages, Notifications and the identity's Profile, four
 * slots of one width, the lists shown over the open pages, with what is new. A list is marked while it shows with
 * no page over it. A new post is written from [PostButton].
 */
@Composable
private fun BottomBar(model: SocialModel) {
    val unread = model.unread.size
    val onList = model.pages.isEmpty()
    Surface(Modifier.fillMaxWidth().height(SOCIAL_BAR_HEIGHT), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BarItem("Feed", Icons.Filled.Home, selected = onList && model.view == SocialView.Feed) { model.showList(SocialView.Feed) }
            BarItem("Messages", Icons.Filled.Email, selected = onList && model.view == SocialView.Messages, badge = model.unreadConversations) {
                model.showList(SocialView.Messages)
            }
            BarItem("Notifications", Icons.Filled.Notifications, selected = onList && model.view == SocialView.Activity, badge = unread) {
                model.showList(SocialView.Activity)
            }
            BarItem("Profile", Icons.Filled.AccountCircle, selected = onList && model.view == SocialView.Me) { model.showList(SocialView.Me) }
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
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (selected) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
