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

/** What the phone keeps of an account's messages: the calls tried, the messages for it, and how far each conversation was read. */
data class ChatStore(
    val tried: Set<String> = emptySet(),
    val messages: List<ChatMessage> = emptyList(),
    /** By conversation key: the last message read (its block and index). */
    val seen: Map<String, Pair<Long, Int>> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val seenJson = JSONObject()
        seen.forEach { (key, at) -> seenJson.put(key, JSONArray().put(at.first).put(at.second)) }
        return JSONObject().put("tried", JSONArray(tried.sorted())).put("messages", JSONArray(messages.map { it.toJson() })).put("seen", seenJson)
    }

    companion object {
        fun fromJson(json: JSONObject): ChatStore {
            val tried = json.optJSONArray("tried") ?: JSONArray()
            val messages = json.optJSONArray("messages") ?: JSONArray()
            val seen = json.optJSONObject("seen") ?: JSONObject()
            return ChatStore(
                tried = (0 until tried.length()).map { tried.getString(it) }.toSet(),
                messages = (0 until messages.length()).map { ChatMessage.fromJson(messages.getJSONObject(it)) },
                seen = seen.keys().asSequence().associateWith { key -> seen.getJSONArray(key).let { it.getLong(0) to it.getInt(1) } },
            )
        }
    }
}

/** The messages to try at once (one batch of social_decrypt calls). */
private const val DECRYPT_BATCH = 40

/**
 * [store] with the calls of [calls] not tried yet decrypted by [decrypt] (a batch of social_decrypt answers: null, or
 * the indexes opened and the message) and read as [me] reads them ([acceptMessage]).
 */
fun readNewMessages(
    store: ChatStore,
    calls: List<MessageCall>,
    me: String,
    decrypt: (List<MessageCall>) -> List<JSONObject?>,
): ChatStore {
    val todo = calls.filter { it.hash !in store.tried }
    if (todo.isEmpty()) return store
    var tried = store.tried
    val messages = store.messages.toMutableList()
    for (chunk in todo.chunked(DECRYPT_BATCH)) {
        val answers = decrypt(chunk)
        chunk.forEachIndexed { i, call ->
            val answer = answers.getOrNull(i)
            if (answer != null) {
                val indexes = answer.optJSONArray("indexes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
                acceptMessage(call, answer.optString("message"), indexes, me)?.let { messages += it }
            }
        }
        tried = tried + chunk.map { it.hash }
    }
    return store.copy(tried = tried, messages = messages)
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
