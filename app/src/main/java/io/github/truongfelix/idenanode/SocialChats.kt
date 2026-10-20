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
 * each conversation was read, the groups' keys ([GroupKey]) and the one-part calls tried with all [groupKeys].
 */
data class ChatStore(
    val tried: Set<String> = emptySet(),
    val messages: List<ChatMessage> = emptyList(),
    /** By conversation key: the last message read (its block and index). */
    val seen: Map<String, Pair<Long, Int>> = emptyMap(),
    val groupKeys: List<GroupKey> = emptyList(),
    /** The one-part calls tried with every key of [groupKeys]: a new key tries them all again. */
    val groupTried: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject {
        val seenJson = JSONObject()
        seen.forEach { (key, at) -> seenJson.put(key, JSONArray().put(at.first).put(at.second)) }
        return JSONObject().put("tried", JSONArray(tried.sorted())).put("messages", JSONArray(messages.map { it.toJson() })).put("seen", seenJson)
            .put("groupKeys", JSONArray(groupKeys.map { it.toJson() })).put("groupTried", JSONArray(groupTried.sorted()))
    }

    companion object {
        fun fromJson(json: JSONObject): ChatStore {
            val tried = json.optJSONArray("tried") ?: JSONArray()
            val messages = json.optJSONArray("messages") ?: JSONArray()
            val seen = json.optJSONObject("seen") ?: JSONObject()
            val keys = json.optJSONArray("groupKeys") ?: JSONArray()
            val groupTried = json.optJSONArray("groupTried") ?: JSONArray()
            return ChatStore(
                tried = (0 until tried.length()).map { tried.getString(it) }.toSet(),
                messages = (0 until messages.length()).map { ChatMessage.fromJson(messages.getJSONObject(it)) },
                seen = seen.keys().asSequence().associateWith { key -> seen.getJSONArray(key).let { it.getLong(0) to it.getInt(1) } },
                groupKeys = (0 until keys.length()).map { GroupKey.fromJson(keys.getJSONObject(it)) },
                groupTried = (0 until groupTried.length()).map { groupTried.getString(it) }.toSet(),
            )
        }
    }
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
    if (todo.isEmpty()) return readGroupMessages(store, calls)
    var tried = store.tried
    val messages = store.messages.toMutableList()
    val keys = store.groupKeys.toMutableList()
    for (chunk in todo.chunked(DECRYPT_BATCH)) {
        val answers = decrypt(chunk)
        chunk.forEachIndexed { i, call ->
            val answer = answers.getOrNull(i)
            if (answer != null) {
                val indexes = answer.optJSONArray("indexes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
                val plain = answer.optString("message")
                acceptMessage(call, plain, indexes, me)?.let { messages += it }
                    ?: acceptGroupKey(call, plain, indexes, me)?.let { keys += it }
            }
        }
        tried = tried + chunk.map { it.hash }
    }
    // A new key may open one-part calls tried before.
    val groupTried = if (keys.size != store.groupKeys.size) emptySet() else store.groupTried
    return readGroupMessages(store.copy(tried = tried, messages = messages, groupKeys = keys, groupTried = groupTried), calls)
}

/**
 * [store] with the one-part calls of [calls] opened by one of its groups' keys ([acceptGroupMessage]): those not
 * tried yet, or all of them when a key came since ([ChatStore.groupTried]).
 */
fun readGroupMessages(store: ChatStore, calls: List<MessageCall>): ChatStore {
    val single = calls.filter { it.parts.size == 1 }
    val todo = single.filter { it.hash !in store.groupTried }
    if (todo.isEmpty() || store.groupKeys.isEmpty()) return store
    val known = store.messages.mapTo(HashSet()) { it.hash }
    val found = todo.filter { it.hash !in known }.mapNotNull { call -> store.groupKeys.firstNotNullOfOrNull { acceptGroupMessage(call, it) } }
    return store.copy(messages = store.messages + found, groupTried = store.groupTried + todo.map { it.hash })
}

/** The account's messages for the Social tab: read from the scan's calls with the node's key, kept on the phone. */
class SocialChats(private val context: Context) {
    var store by mutableStateOf(ChatStore())
        private set
    private var account: String? = null

    private fun file(address: String) = File(File(context.filesDir, "social").also { it.mkdirs() }, "messages-$address.json")

    /** Loads [address]'s messages (another account's are not shown). Call off the main thread. */
    fun load(address: String) {
        if (account == address) return
        account = address
        store = runCatching { ChatStore.fromJson(JSONObject(file(address).readText())) }.getOrDefault(ChatStore())
    }

    /** Tries the calls not tried yet with the node's key and saves what changed. Call off the main thread. */
    fun update(calls: List<MessageCall>, rpc: NodeRpc) {
        val address = account ?: return
        val next = readNewMessages(store, calls, address) { chunk ->
            rpc.batch("social_decrypt", chunk.map { JSONArray().put(JSONObject().put("parts", JSONArray(it.parts))) }).map { it as? JSONObject }
        }
        if (next != store) save(address, next)
    }

    /** Marks [conversation] read up to its last message. */
    fun markSeen(conversation: Conversation) {
        val address = account ?: return
        val last = conversation.last
        if (store.seen[conversation.key] == (last.height to last.index)) return
        save(address, store.copy(seen = store.seen + (conversation.key to (last.height to last.index))))
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
}
