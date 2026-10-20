package io.github.truongfelix.idenanode

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** The node's peers, sync and validation period. */
private fun readNode(rpc: NodeRpc): NodeView {
    val sync = rpc.call("bcn_syncing") as JSONObject
    val peers = (rpc.call("net_peers") as? JSONArray)?.length() ?: 0
    return NodeView(
        peers, sync.optBoolean("syncing"), sync.optLong("currentBlock"), sync.optLong("highestBlock"),
        validation = rpc.ceremony().period != "None", wrongTime = sync.optBoolean("wrongTime"),
        epochStart = (rpc.call("dna_epoch") as JSONObject).optLong("startBlock").takeIf { it > 0 },
    )
}

/** A page opened over the tab's lists; Back closes the last one. */
sealed interface SocialPage {
    /** The thread of post [postId], shown at [focusId] (a reply or comment of it) when given. */
    data class Thread(val postId: Long, val focusId: Long? = null) : SocialPage

    /** The profile of [address] (lowercase). */
    data class Profile(val address: String) : SocialPage

    /** The posts of topic [name]. */
    data class Topic(val name: String) : SocialPage

    /** The people followed, or the topics ([topics]). */
    data class Following(val topics: Boolean = false) : SocialPage

    /** The conversation of [key] ([conversationKey]: its participants, the account included; or a group's). */
    data class Chat(val key: String) : SocialPage

    /** A new group ([key] null), or the name and members of the group of [key] ([GroupEditView]). */
    data class GroupEdit(val key: String? = null) : SocialPage

    /** The conversations hidden on this phone ([HiddenChatsView]). */
    data object HiddenChats : SocialPage

    /** Social settings ([SocialSettingsView]). */
    data object Settings : SocialPage

    /** The accounts blocked ([BlockedView]). */
    data object Blocked : SocialPage

    /** The accounts muted ([MutedView]). */
    data object Muted : SocialPage

    /** One of the people lists of [address]'s profile ([ProfilePeopleView], B-89). */
    data class People(val address: String, val list: PeopleList) : SocialPage

    /** The hidden words ([HiddenWordsView]). */
    data object HiddenWords : SocialPage

    /** The posts hidden ([HiddenPostsView]). */
    data object HiddenPosts : SocialPage

    /** The topics blocked ([BlockedTopicsView]). */
    data object BlockedTopics : SocialPage
}

/** What an open page keeps while another page is opened over it: its place in its list. */
class PageState {
    val list = LazyListState()

    /** Whether a thread was already scrolled to its focus (not again when coming back to it). */
    var focused = false

    /** The replies and comments of a thread folded to one line (with everything under them). */
    val collapsed = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()
}

/** The tab's lists: the feed, the identity's own profile, the activity on its posts (Notifications) and the messages. */
enum class SocialView { Feed, Me, Activity, Messages }

/** The lists of a profile, as idena.social's profile page has them. */
enum class ProfileTab(@StringRes private val titleId: Int) {
    Posts(R.string.social_tab_posts), Replies(R.string.social_tab_replies), Comments(R.string.social_tab_comments),
    Likes(R.string.social_tab_likes), Tips(R.string.social_tab_tips), Media(R.string.social_tab_media),
    ;

    val title: String
        get() = t(titleId)
}

/**
 * The Social tab's state while the app runs, kept when the Node tab is shown: the scan of idena.social
 * from the node, the feed, the activity on the identity's posts and what is new there, the open pages,
 * and through [sending] the editor's draft and the paid actions.
 *
 * While "Scan blocks" is on, [run] scans the blocks after the newest scanned one up to the node's head,
 * then the history back to the contract's first block (once), then follows the head. On the Node tab it
 * only follows the head, and only when a kind of notification is on. Nothing is scanned while the app is
 * hidden, the node syncs or a validation runs; the first scan also waits for the node's newest blocks.
 */
class SocialModel(private val context: Context, private val scope: CoroutineScope) {
    var cache by mutableStateOf<SocialCache?>(null)
        private set
    var feed by mutableStateOf<List<SocialPost>>(emptyList())
        private set

    /** The identity state and age of the authors (Human, Verified...), read again at each new epoch. */
    val states = mutableStateMapOf<String, AuthorIdentity>()
    private var statesEpochStart: Long? = null

    /** The authors' profiles in the profiles contract ([ContractProfile]), read with [states] and again when one opens. */
    val profiles = mutableStateMapOf<String, ContractProfile>()
    var head by mutableStateOf<Long?>(null)
        private set

    /** What the scan is doing, shown at the top of the tab. */
    var scanStatus by mutableStateOf(t(R.string.social_scan_loading))
        private set

    /** Whether the node is synced and outside a validation: posting needs it. */
    var ready by mutableStateOf(false)
        private set

    /** The node at the last scan step; null when it did not answer ([nodeFailed]) or before it was asked. */
    var node by mutableStateOf<NodeView?>(null)
        private set
    var nodeFailed by mutableStateOf(false)
        private set

    /**
     * The status bar's dot and line, with the node's [peers] as the title row reads them every few seconds: the scan
     * reads them only at each step, minutes apart while it scans. Null [peers]: the scan's.
     */
    fun status(peers: Int?): BarState {
        val view = node?.let { if (peers != null) it.copy(peers = peers) else it }
        return if (view == null && !nodeFailed) BarState(Health.Off, t(R.string.social_bar_connecting)) else scanState(view, scanning, head, cache)
    }
    var scanning by mutableStateOf(SocialStore.isScanning(context))
        private set
    /** The node's account (lowercase), read at each scan step; null before the first. */
    var myAddress by mutableStateOf<String?>(null)
        private set

    /** Whether the Social tab is shown: the whole scan runs only then. */
    var visible by mutableStateOf(false)

    // The scan runs on an IO thread and reads these between two batches.
    private val scanningFlag = AtomicBoolean(scanning)

    // After a post, the new blocks are scanned once even without "Scan blocks".
    private val scanNewOnce = AtomicBoolean(false)
    private var wake by mutableIntStateOf(0)

    // The likes, answers and tips of others on the identity's posts, newest first, before hiding.
    private var allActivity by mutableStateOf<List<ActivityItem>>(emptyList())

    /** The likes, answers and tips of others on the identity's posts, newest first; none hidden ([visibleActivity]). */
    val activity: List<ActivityItem>
        get() = visibleActivity(allActivity, hiding, myAddress)

    /** The kinds of activity that notify. */
    var notify by mutableStateOf(SocialStore.notifyKinds(context))
        private set

    /** The block through which the Activity was seen (the scan's authors block then); null before the first scan. */
    var seenThrough by mutableStateOf(SocialStore.seenThrough(context))
        private set

    /** The block the Activity was seen through when it was opened: what came after shows as new. */
    var highlightAfter by mutableStateOf<Long?>(null)
        private set

    // The block through which news were announced, so that each is announced once.
    private var announcedThrough = seenThrough ?: Long.MAX_VALUE

    /** The in-app notices of new activity, shown by the main screen. */
    val notices = Channel<String>(Channel.CONFLATED)

    /** The conversation just hidden ([hideChat]), for the main screen's "Conversation hidden" bar and its Undo. */
    val hiddenNotices = Channel<String>(Channel.CONFLATED)

    /** The activity of the kinds that notify, not seen yet. */
    val unread: List<ActivityItem>
        get() = newActivity(activity, seenThrough ?: Long.MAX_VALUE, notify)

    // Navigation: the list shown, the open threads and profiles (Back closes the last), each list's place.
    var view by mutableStateOf(SocialView.Feed)

    /** Whose posts the feed shows, in which order ([FeedFilter]). */
    var feedFilter by mutableStateOf(FeedFilter())
        private set

    /** The random picks of the posts whose thread shows an ad ([threadShowsAd]), new at each app start. */
    val adSalt = kotlin.random.Random.nextLong()

    /** What the feed's search bar has; the feed shows what it finds while it has [MIN_SEARCH] characters. */
    var searchQuery by mutableStateOf("")

    /** The People list: names and marks kept on the phone ([Person]). */
    var people by mutableStateOf(SocialStore.people(context))
        private set
    /** The names shown: those given on the phone, else the names claimed in the profiles contract ([mergedNames]). */
    val names: Map<String, String>
        get() = mergedNames(peopleNames(people), shownNames(profiles.values))

    /** Puts [person] in the People list (or takes it out once it has no name and no mark) and saves the list. */
    fun setPerson(person: Person) {
        people = people.with(person)
        savePeople()
    }

    /** The names, the people and the topics followed, and what is hidden, as Social's settings export them ([socialListsToJson]). */
    val lists: SocialLists
        get() = SocialLists(people, followedTopics, hidden.words, hidden.posts, hidden.topics)

    /** Merges an exported file's lists into these ([mergeLists]) and saves them. */
    fun importLists(imported: SocialLists): ListsImport {
        val result = mergeLists(lists, imported)
        people = result.lists.people
        savePeople()
        followedTopics = result.lists.topics
        saveTopics()
        hidden.changeWords(result.lists.words)
        hidden.changePosts(result.lists.hiddenPosts)
        hidden.changeTopics(result.lists.blockedTopics)
        return result
    }

    /** The accounts blocked ([Person.blocked]). */
    val blocked: Set<String> by derivedStateOf { blockedAddresses(people) }

    private fun savePeople() {
        // One save at a time, each of the newest list.
        scope.launch(Dispatchers.IO) { peopleSave.withLock { runCatching { SocialStore.setPeople(context, people) } } }
    }
    private val peopleSave = Mutex()

    /** The hidden words, posts hidden and topics blocked. */
    val hidden = SocialHiddenLists(context, scope, peopleSave)

    /** The accounts muted ([Person.muted]). */
    val muted: Set<String> by derivedStateOf { mutedAddresses(people) }

    /** The Social setting "Pictures in posts", kept on the phone. */
    var picturesMode by mutableStateOf(SocialStore.picturesMode(context))
        private set

    fun changePicturesMode(mode: PicturesMode) {
        picturesMode = mode
        scope.launch(Dispatchers.IO) { runCatching { SocialStore.setPicturesMode(context, mode) } }
    }

    /** Everything hidden ([Hiding]). */
    val hiding: Hiding by derivedStateOf { Hiding(blocked, hidden.wordFilter, hidden.posts, hidden.topics, muted, picturesMode) }

    /** The feed as shown ([visibleFeed]). */
    val shownFeed: List<SocialPost> by derivedStateOf { visibleFeed(feed, hiding, myAddress) }

    /** Blocks topic [name] (no longer followed), or unblocks it. */
    fun setTopicBlocked(name: String, block: Boolean) {
        hidden.changeTopics(if (block) hidden.topics + name else hidden.topics - name)
        if (block && name in followedTopics) setTopicFollowed(name, false)
    }

    /** The topics followed (kept on the phone): their posts are in the Following feed. */
    var followedTopics by mutableStateOf(SocialStore.followedTopics(context))
        private set

    fun setTopicFollowed(name: String, follow: Boolean) {
        followedTopics = if (follow) followedTopics + name else followedTopics - name
        saveTopics()
    }

    private fun saveTopics() {
        val topics = followedTopics
        scope.launch(Dispatchers.IO) { peopleSave.withLock { runCatching { SocialStore.setFollowedTopics(context, topics) } } }
    }

    /** [address]'s entry of the People list, or an empty one. */
    fun person(address: String): Person = people[address.lowercase()] ?: Person(address.lowercase())
    // Each bottom-bar list's open pages, the last on top: a list keeps them while another is shown (B-101).
    private val stacks = SocialView.entries.associateWith { mutableStateListOf<SocialPage>() }

    /** The open pages of the list shown, the last on top. */
    val pages: SnapshotStateList<SocialPage>
        get() = stacks.getValue(view)

    /** Each conversation's text not sent yet, by its key, while the app runs (emptied once the node took it). */
    val chatDrafts = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val pageStates = HashMap<SocialPage, PageState>()
    val profileTabs = mutableStateMapOf<String, ProfileTab>()
    val feedState = LazyListState()
    val activityState = LazyListState()
    val messagesState = LazyListState()
    val myProfileState = LazyListState()

    /** The editor and the paid actions. */
    /** Counts the sent actions found in a block: the profile pages read the contract again at each. */
    var profileWrites by mutableIntStateOf(0)
        private set

    val sending = SocialSending(context, scope) {
        scanNewOnce.set(true)
        wake++
        // A profile call changes what the contract holds for the account: shown at once, not at the next epoch.
        refreshProfile(myAddress)
        profileWrites++
        refreshOffers()
    }

    /** The vouch offers made to the account that wait for its answer (B-81): in Notifications, counted in its badge. */
    var offersToMe by mutableStateOf<List<VouchEntry>>(emptyList())
        private set

    /** Reads the vouch offers to the account again (after each scan step and each action in a block). */
    fun refreshOffers() {
        val me = myAddress ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { NodeProfilesSource(rpc(context)).offersTo(me) }.getOrNull() }?.let { offersToMe = it }
        }
    }

    /** Sends a call to the profiles contract, its fees shown first. */
    val profileAction: ProfileAction = { what, detail, call -> sending.prepareProfile(what, detail) { call } }

    /** Reads [address]'s profile in the profiles contract again. */
    fun refreshProfile(address: String?) {
        val key = address?.lowercase() ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { NodeProfilesSource(rpc(context)).profiles(listOf(key)) }.getOrNull() }
                ?.let { profiles.putAll(it) }
        }
    }

    fun setScanningOn(on: Boolean) {
        scanning = on
        scanningFlag.set(on)
        SocialStore.setScanning(context, on)
    }

    fun setNotifyOn(kind: NotifyKind, on: Boolean) {
        notify = if (on) notify + kind else notify - kind
        SocialStore.setNotifyKinds(context, notify)
    }

    /** Shows the Activity: what is new now stays marked while it is open, and counts as seen. */
    fun openActivity() {
        view = SocialView.Activity
        highlightAfter = seenThrough
        markSeen()
    }

    /** Shows the conversation of [key] (a message notification's tap) over the Messages list. */
    fun showChat(key: String) {
        stacks.getValue(SocialView.Messages).clear()
        dropClosedPageStates()
        sending.draft = null
        view = SocialView.Messages
        open(SocialPage.Chat(key))
    }

    /** Shows the Activity from a notice: its list, not a page over it nor the editor (the draft's text stays). */
    fun showActivity() {
        stacks.getValue(SocialView.Activity).clear()
        dropClosedPageStates()
        sending.draft = null
        openActivity()
    }

    /**
     * Shows the list [list] from the bottom bar, with the pages it had open (B-101); the Activity counts as seen.
     * A tap on the list already shown closes its pages, a second one takes it back to its top. You ([SocialView.Me])
     * always comes back to the account's own profile, its pages closed (owner 2026-10-17: it showed the profile opened
     * last from it).
     */
    fun showList(list: SocialView) {
        if (view == list) {
            if (pages.isEmpty()) scope.launch { listState(list).animateScrollToItem(0) }
            else {
                pages.clear()
                dropClosedPageStates()
            }
            return
        }
        if (list == SocialView.Me && stacks.getValue(list).isNotEmpty()) {
            stacks.getValue(list).clear()
            dropClosedPageStates()
        }
        if (list == SocialView.Activity) openActivity() else view = list
    }

    /** Shows the feed's own list, its pages closed: where the search's results show. */
    fun showFeedList() {
        if (view == SocialView.Feed && pages.isEmpty()) return
        view = SocialView.Feed
        pages.clear()
        dropClosedPageStates()
    }

    /** Forgets the places of the pages no list has open any more. */
    private fun dropClosedPageStates() {
        pageStates.keys.retainAll { page -> stacks.values.any { page in it } }
    }

    private fun listState(list: SocialView): LazyListState = when (list) {
        SocialView.Feed -> feedState
        SocialView.Me -> myProfileState
        SocialView.Activity -> activityState
        SocialView.Messages -> messagesState
    }

    private val viewingActivity: Boolean
        get() = visible && view == SocialView.Activity && pages.isEmpty() && sending.draft == null

    private fun markSeen() {
        val through = cache?.authorsHeight ?: return
        if (through > (seenThrough ?: Long.MIN_VALUE)) {
            seenThrough = through
            SocialStore.setSeenThrough(context, through)
        }
        announcedThrough = maxOf(announcedThrough, through)
    }

    /** Filters the feed by [filter], from its top (the place in the other list means nothing there). */
    fun filterFeed(filter: FeedFilter) {
        if (filter == feedFilter) return
        feedFilter = filter
        scope.launch { feedState.scrollToItem(0) }
    }

    fun openTopic(name: String) = open(SocialPage.Topic(name))

    fun open(page: SocialPage) {
        if (pages.lastOrNull() != page) pages += page
    }

    fun openProfile(address: String) {
        val lower = address.lowercase()
        // Already shown: the identity's own profile.
        if (pages.isEmpty() && view == SocialView.Me && lower == myAddress) return
        open(SocialPage.Profile(lower))
    }

    fun back() {
        // A second tap on Back may come before the page is gone.
        if (pages.isEmpty()) return
        val page = pages.removeAt(pages.lastIndex)
        if (stacks.values.none { page in it }) pageStates.remove(page)
        // A reply started in the thread's bar goes with it: else, with no thread left, it showed in the full editor.
        if (page is SocialPage.Thread && sending.draft?.target?.let { it != PostTarget.NEW_POST } == true) sending.draft = null
    }

    fun pageState(page: SocialPage) = pageStates.getOrPut(page) { PageState() }

    private suspend fun show(next: SocialCache) {
        cache = next
        feed = withContext(Dispatchers.Default) { next.feed() }
        // What was sent is in the feed now when its block was read.
        sending.sent.scanned(next.authorsHeight)
        // The ages and states change at the validation: ask again in a new epoch.
        node?.epochStart?.let { start ->
            if (statesEpochStart != null && statesEpochStart != start) {
                states.clear()
                profiles.clear()
            }
            statesEpochStart = start
        }
        val authors = feed.flatMap { withReplies(it) }.map { it.call.author }.distinct()
        val unknown = authors.filter { it !in states }
        if (unknown.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identityStates(unknown) }.getOrNull() }
                ?.let { states.putAll(it) }
        }
        // The authors, the account and the people named or followed on the phone (contacts, chats).
        val noProfile = (authors + listOfNotNull(myAddress) + people.keys).map { it.lowercase() }.distinct().filter { it !in profiles }
        if (noProfile.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { NodeProfilesSource(rpc(context)).profiles(noProfile) }.getOrNull() }
                ?.let { profiles.putAll(it) }
        }
        updateActivity()
        updateChats()
        // An address without a key may have signed a call since: looked up again when asked.
        messageKeys.entries.removeAll { it.value.isEmpty() }
    }

    /** The private messages of the account ([SocialChats]), read from the scan with the node's key. */
    val chats = SocialChats(context)

    /** Every conversation of the account, the latest first ([conversations]), none with a blocked account; built once per change. */
    private val allConversations: List<Conversation> by derivedStateOf {
        myAddress?.let { me ->
            val store = chats.store
            visibleConversations(conversations(store.messages, me, people, store.groupKeys), blocked)
        }.orEmpty()
    }

    /** The account's conversations, those of strangers in Requests, without those hidden on this phone. */
    val conversations: List<Conversation>
        get() = allConversations.filter { it.key !in chats.store.hiddenChats }

    /** The conversations hidden on this phone ([hideChat]). */
    val hiddenConversations: List<Conversation>
        get() = allConversations.filter { it.key in chats.store.hiddenChats }

    /** The names shown in the conversation of [key]: those given to its members on this phone first. */
    fun chatNames(key: String): Map<String, String> = chats.store.chatNames(key, names)

    /** A conversation's title on this phone ([ChatStore.titleOf]). */
    fun chatTitle(conversation: Conversation): String = chats.store.titleOf(conversation, myAddress.orEmpty(), names)

    /** The conversations (not Requests) with a message not read yet. */
    val unreadConversations: Int
        get() = conversations.count { !it.isRequest && chats.store.unreadCount(it, myAddress.orEmpty()) > 0 }

    private suspend fun updateChats() {
        val me = myAddress ?: return
        val calls = cache?.messages ?: return
        withContext(Dispatchers.IO) {
            chats.load(me)
            // A node that does not answer, or without social_decrypt: tried again after the next scan step.
            runCatching { chats.update(calls, rpc(context)) }
        }
    }

    /** Opens the conversation of the account with [others] (lowercase), with or without messages yet. */
    fun openChat(others: List<String>) {
        val me = myAddress ?: return
        // Writing to someone again (a profile's ✉, a search) shows the conversation hidden with them.
        chats.setHidden(conversationKey(others + me), false)
        open(SocialPage.Chat(conversationKey(others + me)))
    }

    /** By address (lowercase): its public key once looked up ([findPublicKey]), "" when it has none; absent until then. */
    val messageKeys = mutableStateMapOf<String, String>()

    /** Looks up the public keys of [addresses] not known yet; a node that does not answer leaves them unknown. */
    fun lookUpKeys(addresses: List<String>) {
        val todo = addresses.map { it.lowercase() }.filter { it !in messageKeys }.distinct()
        if (todo.isEmpty()) return
        val scanned = cache
        scope.launch {
            for (address in todo) {
                val key = withContext(Dispatchers.IO) {
                    runCatching {
                        val posting = SocialPosting(rpc(context))
                        findPublicKey(
                            address, posting::publicKey, scanned?.keys.orEmpty(), scanned?.callHeightsOf(address).orEmpty(), posting::callKeysAt,
                        ).orEmpty()
                    }.getOrNull()
                } ?: continue
                messageKeys[address] = key
            }
        }
    }

    /** Sends [text] to [others] (lowercase) once each one's public key is known. */
    fun sendMessage(others: List<String>, text: String, conversation: String) {
        val keys = others.map { messageKeys[it].orEmpty() }
        if (keys.any { it.isEmpty() }) return
        sending.prepareMessage(others, keys, text) { chatDrafts.remove(conversation) }
    }

    /** Takes [conversation] out of Requests: its people (a group's admin) are marked to message ([Person.message]). */
    fun acceptChat(conversation: Conversation) {
        (conversation.group?.let { listOf(it.key.admin) } ?: conversation.others).forEach { setPerson(person(it).copy(message = true)) }
    }

    /** Sends [text] to the group of [conversation], under its current key. */
    fun sendGroupMessage(conversation: Conversation, text: String) {
        val key = conversation.group?.key ?: return
        sending.prepareGroupMessage(key, text, chatTitle(conversation)) { chatDrafts.remove(conversation.key) }
    }

    /**
     * Hides [conversation] on this phone (one-to-one or a group): nobody is told, its messages still come and wait
     * there, Social settings shows it again. In a group, only its admin removes a member.
     */
    fun hideChat(conversation: Conversation) {
        chats.setHidden(conversation.key, true)
        hiddenNotices.trySend(conversation.key)
        back()
    }

    /**
     * Creates a group ([group] null) or changes its [name] and [members], or only its key (the same name and members),
     * once each member's public key is known.
     */
    fun saveGroup(group: GroupKey?, name: String, members: List<String>) {
        val keys = members.map { messageKeys[it].orEmpty() }
        if (keys.any { it.isEmpty() }) return
        sending.prepareGroupKey(group, name, members, keys) { back() }
    }

    /**
     * The node's account is [address] (lowercase): at the first scan step, or another one after a key import.
     * For another one, what is on its posts already is not news, and its own profile shows from the top.
     */
    private suspend fun setAccount(address: String) {
        val switched = myAddress != null
        myAddress = address
        if (switched) {
            ProfileReads.clear()
            markSeen()
            highlightAfter = seenThrough
            scope.launch { myProfileState.scrollToItem(0) }
        }
        updateActivity()
        updateChats()
    }

    /** The activity on the identity's posts after a scan, and a notice of what is new. */
    private suspend fun updateActivity() {
        val me = myAddress ?: return
        refreshOffers()
        val current = feed
        allActivity = withContext(Dispatchers.Default) { socialActivity(current, me) }
        val through = cache?.authorsHeight ?: return
        if (seenThrough == null) {
            // The first scan: what is there already is not news.
            seenThrough = through
            SocialStore.setSeenThrough(context, through)
            announcedThrough = through
            return
        }
        if (viewingActivity) {
            markSeen()
            return
        }
        val news = newActivity(activity, maxOf(seenThrough!!, announcedThrough), notify)
        announcedThrough = maxOf(announcedThrough, through)
        if (news.isNotEmpty()) notices.trySend(noticeText(news) { displayName(it, names) })
    }

    /** Waits [ms], or less when a button changes, the tab is shown or hidden, or a post was made. */
    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { snapshotFlow { listOf(scanning, wake, visible, notify) }.drop(1).first() }
    }

    /** Scans while the app is shown (see the class); returns when [lifecycle] ends. */
    suspend fun run(lifecycle: Lifecycle) {
        withContext(Dispatchers.IO) { SocialStore.load(context) }?.let { show(it) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // The Social tab on screen shows the news: the icon's notification goes ([SocialBadge]).
                if (visible) SocialBadge.clear(context)
                try {
                    // On the Node tab, only the new blocks for the notifications; else nothing is asked.
                    if (!visible && !(scanning && notify.isNotEmpty() && cache != null)) {
                        pause(60_000)
                        continue
                    }
                    val view = try {
                        withContext(Dispatchers.IO) { readNode(rpc(context)) }
                    } catch (e: IOException) {
                        node = null
                        nodeFailed = true
                        ready = false
                        scanStatus = t(R.string.social_scan_no_answer, "${e.message}")
                        pause(10_000)
                        continue
                    }
                    node = view
                    nodeFailed = false
                    // The node's account, at each step: another one after a key import (the node restarts with it).
                    val address = withContext(Dispatchers.IO) { rpc(context).call("dna_getCoinbaseAddr") as String }.lowercase()
                    if (address != myAddress) setAccount(address)
                    ready = !view.syncing && !view.validation
                    if (!ready) {
                        scanStatus = if (view.syncing) t(R.string.social_scan_waiting_sync) else t(R.string.social_scan_paused_validation)
                        pause(30_000)
                        continue
                    }
                    val source = NodeSocialSource(rpc(context))
                    val (nodeHead, headTime) = withContext(Dispatchers.IO) { source.headBlock() }
                    head = nodeHead
                    val current = cache
                    val wantsNew = scanningFlag.get() || scanNewOnce.get()
                    val startWait = scanStartWait(nodeHead, headTime, System.currentTimeMillis() / 1000)
                    when {
                        current == null && wantsNew && startWait != null -> {
                            scanStatus = startWait
                            pause(30_000)
                        }
                        // The first time, the scan starts at the head: nothing to read yet.
                        current == null && wantsNew -> {
                            scanStatus = t(R.string.social_scan_starting)
                            show(withContext(Dispatchers.IO) { checkNewBlocks(null, source) { SocialStore.save(context, it) } })
                        }
                        current == null -> {
                            scanStatus = t(R.string.social_scan_select)
                            pause(20_000)
                        }
                        wantsNew && (current.high < nodeHead || current.authorsHeight < current.high) -> {
                            scanStatus = t(R.string.social_scan_new_blocks)
                            val next = withContext(Dispatchers.IO) {
                                checkNewBlocks(current, source, keepGoing = { scanningFlag.get() || scanNewOnce.get() }) { SocialStore.save(context, it) }
                            }
                            if (next.authorsHeight >= next.high) scanNewOnce.set(false)
                            show(next)
                        }
                        // Once, for a scan made before the app read messages: those of the blocks already read.
                        visible && current.messagesPending && scanningFlag.get() -> {
                            scanStatus = t(R.string.social_scan_messages)
                            show(withContext(Dispatchers.IO) { scanOlderMessages(current, source) { SocialStore.save(context, it) } })
                        }
                        visible && !current.historyDone && scanningFlag.get() -> {
                            scanStatus = t(R.string.social_scan_history)
                            show(withContext(Dispatchers.IO) { scanOlderBlocks(current, source) { SocialStore.save(context, it) } })
                        }
                        else -> {
                            scanNewOnce.set(false)
                            scanStatus = when {
                                !scanningFlag.get() -> t(R.string.social_scan_stopped)
                                else -> t(R.string.social_scan_up_to_date)
                            }
                            // A new block comes about every 20 seconds.
                            pause(20_000)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    scanStatus = t(R.string.social_scan_no_answer, "${e.message}")
                    pause(10_000)
                }
            }
        }
    }

}

/** The post and all its replies. */
fun withReplies(post: SocialPost): List<SocialPost> = listOf(post) + post.replies.flatMap { withReplies(it) }

/** "0x1234…abcd". */
fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"
