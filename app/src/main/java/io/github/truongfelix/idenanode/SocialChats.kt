package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/*
 * The private messages of the node's account: the scan keeps every sendMessage call still encrypted
 * ([SocialCache.messages]); each new one is tried once with the node's key (social_decrypt), and those for the account
 * are kept on the phone, per account (files/social/messages-<address>.json), with what was read of each conversation.
 */

/**
 * What the phone keeps of an account's messages: the calls tried with the node's key, the messages for it, how far
 * each conversation was read, the groups' keys ([GroupKey]) and the one-part calls tried with all of them, and what
 * is of this phone only: the conversations hidden here, and the names given here to groups and to their members.
 */
data class ChatStore(
    val tried: Set<String> = emptySet(),
    val messages: List<ChatMessage> = emptyList(),
    /** By conversation key: the last message read (its block and index). */
    val seen: Map<String, Pair<Long, Int>> = emptyMap(),
    val groupKeys: List<GroupKey> = emptyList(),
    /** The one-part calls tried with every key of [groupKeys]: a new key tries only itself on them. */
    val groupTried: Set<String> = emptySet(),
    /** The conversations (their keys) hidden on this phone, one-to-one or groups: nobody is told. */
    val hiddenChats: Set<String> = emptySet(),
    /** By conversation key: the group's name on this phone, in place of the one its admin gave. */
    val groupNames: Map<String, String> = emptyMap(),
    /** By conversation key: names given on this phone to the group's members, shown in that group only. */
    val memberNames: Map<String, Map<String, String>> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val seenJson = JSONObject()
        seen.forEach { (key, at) -> seenJson.put(key, JSONArray().put(at.first).put(at.second)) }
        val memberJson = JSONObject()
        memberNames.forEach { (key, names) -> memberJson.put(key, JSONObject(names)) }
        return JSONObject().put("tried", JSONArray(tried.sorted())).put("messages", JSONArray(messages.map { it.toJson() })).put("seen", seenJson)
            .put("groupKeys", JSONArray(groupKeys.map { it.toJson() })).put("groupTried", JSONArray(groupTried.sorted()))
            .put("hiddenChats", JSONArray(hiddenChats.sorted())).put("groupNames", JSONObject(groupNames)).put("memberNames", memberJson)
    }

    companion object {
        private fun strings(array: JSONArray?): List<String> = array?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

        private fun stringMap(json: JSONObject?): Map<String, String> =
            json?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()

        fun fromJson(json: JSONObject): ChatStore {
            val messages = json.optJSONArray("messages") ?: JSONArray()
            val seen = json.optJSONObject("seen") ?: JSONObject()
            val keys = json.optJSONArray("groupKeys") ?: JSONArray()
            val members = json.optJSONObject("memberNames") ?: JSONObject()
            val read = (0 until messages.length()).map { ChatMessage.fromJson(messages.getJSONObject(it)) }
            // A store of a version without groups tried their key messages as messages, and failed: tried again.
            val tried = strings(json.optJSONArray("tried")).toSet()
                .let { all -> if (json.has("groupKeys")) all else all.intersect(read.mapTo(HashSet()) { it.hash }) }
            return ChatStore(
                tried = tried,
                messages = read,
                seen = seen.keys().asSequence().associateWith { key -> seen.getJSONArray(key).let { it.getLong(0) to it.getInt(1) } },
                groupKeys = (0 until keys.length()).map { GroupKey.fromJson(keys.getJSONObject(it)) },
                groupTried = strings(json.optJSONArray("groupTried")).toSet(),
                hiddenChats = strings(json.optJSONArray("hiddenChats")).toSet(),
                groupNames = stringMap(json.optJSONObject("groupNames")),
                memberNames = members.keys().asSequence().associateWith { stringMap(members.getJSONObject(it)) },
            )
        }
    }
}

/** The names shown in the conversation of [key]: those given to its members on this phone first, then [names]. */
fun ChatStore.chatNames(key: String, names: Map<String, String>): Map<String, String> = memberNames[key]?.let { names + it } ?: names

/** [conversation]'s title on this phone: a group's ([groupTitle], its name here first), else its people's names. */
fun ChatStore.titleOf(conversation: Conversation, me: String, names: Map<String, String>): String {
    val shown = chatNames(conversation.key, names)
    return conversation.group?.let { groupTitle(it, me, shown, groupNames[conversation.key]) } ?: othersTitle(conversation.others, shown)
}

/** The messages to try at once (one batch of social_decrypt calls). */
private const val DECRYPT_BATCH = 40

/**
 * [store] with the calls of [calls] not tried yet decrypted by [decrypt] (a batch of social_decrypt answers: null, or
 * the indexes opened and the message) and read as [me] reads them ([acceptMessage], or a group's key message,
 * [acceptGroupKey]); then the one-part calls opened with the groups' keys ([readGroupMessages]).
 */
fun readNewMessages(
    store: ChatStore,
    calls: List<MessageCall>,
    me: String,
    decrypt: (List<MessageCall>) -> List<JSONObject?>,
): ChatStore {
    val todo = calls.filter { it.parts.size > 1 && it.hash !in store.tried }
    if (todo.isEmpty()) return readGroupMessages(store, calls, emptyList())
    var tried = store.tried
    val messages = store.messages.toMutableList()
    val known = messages.mapTo(HashSet()) { it.hash }
    val newKeys = ArrayList<GroupKey>()
    val knownKeys = store.groupKeys.mapTo(HashSet()) { it.hash }
    for (chunk in todo.chunked(DECRYPT_BATCH)) {
        val answers = decrypt(chunk)
        chunk.forEachIndexed { i, call ->
            val answer = answers.getOrNull(i)
            if (answer != null && call.hash !in known && call.hash !in knownKeys) {
                val indexes = answer.optJSONArray("indexes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
                val plain = answer.optString("message")
                acceptMessage(call, plain, indexes, me)?.let { messages += it }
                    ?: acceptGroupKey(call, plain, indexes, me)?.let { newKeys += it }
            }
        }
        tried = tried + chunk.map { it.hash }
    }
    return readGroupMessages(store.copy(tried = tried, messages = messages, groupKeys = store.groupKeys + newKeys), calls, newKeys)
}

/**
 * [store] with the one-part calls of [calls] opened by one of its groups' keys ([acceptGroupMessage]): those not
 * tried yet with every key, and those tried before with the keys just read ([newKeys]) only.
 */
fun readGroupMessages(store: ChatStore, calls: List<MessageCall>, newKeys: List<GroupKey>): ChatStore {
    if (store.groupKeys.isEmpty()) return store
    val known = store.messages.mapTo(HashSet()) { it.hash }
    val single = calls.filter { it.parts.size == 1 && it.hash !in known }
    val (before, todo) = single.partition { it.hash in store.groupTried }
    val found = todo.mapNotNull { call -> store.groupKeys.firstNotNullOfOrNull { acceptGroupMessage(call, it) } } +
        (if (newKeys.isEmpty()) emptyList() else before.mapNotNull { call -> newKeys.firstNotNullOfOrNull { acceptGroupMessage(call, it) } })
    if (found.isEmpty() && todo.isEmpty()) return store
    return store.copy(messages = store.messages + found, groupTried = store.groupTried + todo.map { it.hash })
}

/** The account's messages for the Social tab: read from the scan's calls with the node's key, kept on the phone. */
class SocialChats(private val context: Context) {
    var store by mutableStateOf(ChatStore())
        private set
    private var account: String? = null

    private fun file(address: String) = file(context, address)

    /** Loads [address]'s messages (another account's are not shown). Call off the main thread. */
    fun load(address: String) {
        if (account == address) return
        account = address
        store = runCatching { ChatStore.fromJson(JSONObject(file(address).readText())) }.getOrDefault(ChatStore())
    }

    /**
     * Tries the calls not tried yet with the node's key and saves what changed. Call off the main thread. What was
     * read is merged into the store as it is once the node answered ([withOpened]): a conversation hidden, renamed or
     * read meanwhile stays so.
     */
    fun update(calls: List<MessageCall>, rpc: NodeRpc) {
        val address = account ?: return
        val read = readNewMessages(store, calls, address) { chunk ->
            rpc.batch("social_decrypt", chunk.map { JSONArray().put(JSONObject().put("parts", JSONArray(it.parts))) }).map { it as? JSONObject }
        }
        change { if (account == address) withOpened(it, read) else it }
    }

    /** Marks [conversation] read up to its last message. */
    fun markSeen(conversation: Conversation) {
        val last = conversation.last
        account?.let { ChatNotifications.cancel(context, it, conversation.key) }
        change { it.copy(seen = it.seen + (conversation.key to (last.height to last.index))) }
    }

    /** Hides the conversation of [key] on this phone, or shows it again ([hidden] false). */
    fun setHidden(key: String, hidden: Boolean) {
        if (hidden) account?.let { ChatNotifications.cancel(context, it, key) }
        change { it.copy(hiddenChats = if (hidden) it.hiddenChats + key else it.hiddenChats - key) }
    }

    /** Names the group of [key] [name] on this phone; blank takes the admin's name back. */
    fun setGroupName(key: String, name: String) = change {
        val trimmed = name.trim().take(MAX_GROUP_NAME)
        it.copy(groupNames = if (trimmed.isEmpty()) it.groupNames - key else it.groupNames + (key to trimmed))
    }

    /** Names [member] [name] in the group of [key] on this phone; blank takes their own name back. */
    fun setMemberName(key: String, member: String, name: String) = change {
        val trimmed = name.trim().take(MAX_GROUP_NAME)
        val names = it.memberNames[key].orEmpty().let { m -> if (trimmed.isEmpty()) m - member else m + (member to trimmed) }
        it.copy(memberNames = if (names.isEmpty()) it.memberNames - key else it.memberNames + (key to names))
    }

    // One change of the store at a time (the reads off the main thread, the user's changes on it): each starts from
    // the last one saved, and one file write at a time.
    private val lock = Any()

    private fun change(transform: (ChatStore) -> ChatStore) = synchronized(lock) {
        val address = account ?: return
        val next = transform(store)
        if (next != store) save(address, next)
    }

    private fun save(address: String, next: ChatStore) {
        store = next
        runCatching {
            val f = file(address)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(next.toJson().toString())
            if (!tmp.renameTo(f)) throw IOException("cannot save the messages")
        }
    }

    companion object {
        /** The file of [address]'s messages, also read by the node's service ([ChatNotifications]). */
        fun file(context: Context, address: String) = File(File(context.filesDir, "social").also { it.mkdirs() }, "messages-$address.json")
    }
}
