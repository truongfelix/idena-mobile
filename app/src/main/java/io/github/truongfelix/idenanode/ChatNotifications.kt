package io.github.truongfelix.idenanode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * New messages while the app is not on screen (B-117): the node's service ([SocialBadge.check]) opens the new calls
 * with the node's key and the groups' keys, and posts one notification per conversation with messages not read, in
 * Android's conversation style: a group's name as its title, each message under its writer's name (the names given on
 * the phone first). Never for a conversation hidden on the phone, in Requests, or with a blocked account. Reading the
 * conversation in the app, or hiding it, removes its notification. Nothing is saved in the app's files: the service
 * keeps what it opened in its own process, and in its own file what it announced.
 */

/** One conversation's notification: its [title], its unread messages (writer's name, text; oldest first), the newest one. */
data class ChatNotice(
    val key: String,
    val title: String,
    val isGroup: Boolean,
    val lines: List<Pair<String, String>>,
    val times: List<Long>,
    val count: Int,
    val newest: Pair<Long, Int>,
)

/** The unread messages shown at most in one notification (the newest). */
const val NOTICE_LINES = 6

/** One line of a message for a list or a notification: its text, or what it is. */
fun messagePreview(message: ChatMessage): String = when {
    message.text.startsWith("ipfs://") -> t(R.string.socialb_preview_ipfs)
    message.text.isNotEmpty() -> message.text.replace('\n', ' ')
    message.hasMedia -> t(R.string.socialb_preview_picture)
    else -> "…"
}

/**
 * The notifications of [store] for [me]: one per conversation shown (not hidden on the phone, not in Requests, no
 * blocked account) with messages from others not read yet, a group's key messages too ("… added you").
 */
fun chatNotices(store: ChatStore, me: String, people: Map<String, Person>): List<ChatNotice> {
    val names = peopleNames(people)
    val blocked = people.values.filter { it.blocked }.mapTo(HashSet()) { it.address }
    return visibleConversations(conversations(store.messages, me, people, store.groupKeys), blocked)
        .filter { !it.isRequest && it.key !in store.hiddenChats }
        .mapNotNull { conversation ->
            val seen = store.seen[conversation.key]
            val unread = conversation.messages.filter {
                it.from != me && (seen == null || it.height > seen.first || it.height == seen.first && it.index > seen.second)
            }
            if (unread.isEmpty()) return@mapNotNull null
            val chatNames = store.memberNames[conversation.key]?.let { names + it } ?: names
            val group = conversation.group
            val title = if (group != null) groupTitle(group, me, chatNames, store.groupNames[conversation.key])
            else conversation.others.joinToString(", ") { displayName(it, chatNames) }
            val shown = unread.takeLast(NOTICE_LINES)
            ChatNotice(
                key = conversation.key,
                title = title,
                isGroup = conversation.isGroup,
                lines = shown.map { m ->
                    val text = if (m.kind.isEmpty()) messagePreview(m) else groupEventText(m, previousKeyLine(conversation, m), me, chatNames)
                    displayName(m.from, chatNames) to text
                },
                times = shown.map { it.time },
                count = unread.size,
                newest = unread.last().let { it.height to it.index },
            )
        }
}

/** The conversations of [notices] to sound for: a message newer than the one announced before ([announced], by key). */
fun noticesToSound(notices: List<ChatNotice>, announced: Map<String, Pair<Long, Int>>): Set<String> =
    notices.filter { n -> announced[n.key]?.let { a -> n.newest.first > a.first || n.newest.first == a.first && n.newest.second > a.second } ?: true }
        .mapTo(HashSet()) { it.key }

/**
 * [store] (the app's file: what it read, seen, hid and named) with what the service opened before ([opened], kept in
 * its own process), so that no call is opened twice.
 */
fun withOpened(store: ChatStore, opened: ChatStore?): ChatStore {
    if (opened == null) return store
    val known = store.messages.mapTo(HashSet()) { it.hash }
    val keys = store.groupKeys.mapTo(HashSet()) { it.hash }
    return store.copy(
        tried = store.tried + opened.tried,
        messages = store.messages + opened.messages.filter { it.hash !in known },
        groupKeys = store.groupKeys + opened.groupKeys.filter { it.hash !in keys },
        groupTried = store.groupTried + opened.groupTried,
    )
}

/** The notifications of new messages (see the top of this file). */
object ChatNotifications {
    private const val CHANNEL_ID = "messages"
    private const val NOTIFICATION_ID = 3

    /** The extra of the intent that opens a conversation: its key. */
    const val EXTRA_OPEN_CHAT = "openChat"

    // What the service opened, kept in its process only.
    private var opened: ChatStore? = null

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, t(R.string.chat_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = t(R.string.chat_channel_description)
        channel.setShowBadge(true)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Removes the notification of the conversation of [key] (read, or hidden). */
    fun cancel(context: Context, key: String) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(key, NOTIFICATION_ID) }
        runCatching {
            val state = announced(context) ?: return
            if (key in state.second) save(context, state.first, state.second - key)
        }
    }

    /** Removes every message notification ("Messages" switched off). */
    fun clearAll(context: Context) {
        val state = announced(context) ?: return
        state.second.keys.forEach { runCatching { context.getSystemService(NotificationManager::class.java).cancel(it, NOTIFICATION_ID) } }
        runCatching { save(context, state.first, emptyMap()) }
    }

    private fun file(context: Context) = File(context.filesDir, "social/messages_announced.json")

    /**
     * The first block whose messages notify (the newest one at the first check: older ones were there before) and,
     * by conversation, the notification posted and the newest message it announced; null before the first check.
     */
    private fun announced(context: Context): Pair<Long, Map<String, Pair<Long, Int>>>? = runCatching {
        val json = JSONObject(file(context).readText())
        val posted = json.getJSONObject("posted")
        json.getLong("since") to posted.keys().asSequence().associateWith { key -> posted.getJSONArray(key).let { it.getLong(0) to it.getInt(1) } }
    }.getOrNull()

    private fun save(context: Context, since: Long, posted: Map<String, Pair<Long, Int>>) {
        val json = JSONObject()
        posted.forEach { (key, at) -> json.put(key, JSONArray().put(at.first).put(at.second)) }
        file(context).writeText(JSONObject().put("since", since).put("posted", json).toString())
    }

    /**
     * One check of the node's service: opens [calls] (the scan's messages) for [me] with the node ([rpc]) and the
     * groups' keys, then posts, updates or removes each conversation's notification.
     */
    @Synchronized
    fun check(context: Context, rpc: NodeRpc, me: String, calls: List<MessageCall>, people: Map<String, Person>) {
        val saved = runCatching { ChatStore.fromJson(JSONObject(SocialChats.file(context, me).readText())) }.getOrDefault(ChatStore())
        val store = readNewMessages(withOpened(saved, opened), calls, me) { chunk ->
            rpc.batch("social_decrypt", chunk.map { JSONArray().put(JSONObject().put("parts", JSONArray(it.parts))) }).map { it as? JSONObject }
        }
        opened = store
        val state = announced(context)
        val since = state?.first ?: calls.maxOfOrNull { it.height } ?: 0
        val posted = state?.second.orEmpty()
        val notices = chatNotices(store, me, people).filter { it.newest.first > since }
        val sound = noticesToSound(notices, posted)
        val manager = context.getSystemService(NotificationManager::class.java)
        (posted.keys - notices.mapTo(HashSet()) { it.key }).forEach { runCatching { manager.cancel(it, NOTIFICATION_ID) } }
        createChannel(context)
        notices.forEach { runCatching { manager.notify(it.key, NOTIFICATION_ID, notification(context, it, silent = it.key !in sound)) } }
        save(context, since, notices.associate { it.key to it.newest })
    }

    private fun notification(context: Context, notice: ChatNotice, silent: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(
            context, notice.key.hashCode(), Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_CHAT, notice.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName(t(R.string.group_you)).build())
            .setGroupConversation(notice.isGroup)
        if (notice.isGroup) style.setConversationTitle(notice.title)
        notice.lines.forEachIndexed { i, (writer, text) ->
            style.addMessage(text, notice.times[i] * 1000, androidx.core.app.Person.Builder().setName(writer).build())
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_idena)
            .setStyle(style)
            .setContentTitle(notice.title)
            .setContentText(notice.lines.last().second)
            .setNumber(notice.count)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            .build()
    }
}
