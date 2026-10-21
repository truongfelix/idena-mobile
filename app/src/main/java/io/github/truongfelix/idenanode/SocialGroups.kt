package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Group chats with a shared key (notes/group-chat-design.md in the workspace), on idena.social's sendMessage call.
 * A key message hands the group's key, name and members to each member: one part per member, encrypted by the node
 * (social_encrypt), the admin's own first. A group message is ONE part, encrypted with the group's key (AES-256-GCM,
 * `iv(12) || ciphertext+tag`), the same size whatever the group's size. The website drops both: it reads only messages
 * of two parts or more, and a key message's hash is the first part's, never its text's.
 */

/** The format's version, in every plain text. */
const val GROUP_FORMAT = 1

/** The most members of a group besides its admin: the node encrypts one message for at most 64 keys. */
const val MAX_GROUP_MEMBERS = 64

/** How long a group message waits for the blocks up to the node's last one to be read: 90 seconds. */
const val GROUP_CATCH_UP_MS = 90_000L

/** A group message reading the newest blocks first, or not sent because they could not be read in time. */
enum class GroupSendStep { Reading, Behind }

/** The longest group name. */
const val MAX_GROUP_NAME = 40

/**
 * The blocks after a member change during which a removed member's message under the older key still counts: one
 * written before they saw the change.
 */
const val GROUP_GRACE_BLOCKS = 30L

private val GROUP_ID = Regex("^[0-9a-f]{32}$")
private val MEMBER = Regex("^0x[0-9a-f]{40}$")

/** A group's conversation key: its admin and id. Never a [conversationKey] (those hold no ':'). */
fun groupConversationKey(admin: String, group: String): String = "g:$admin:$group"

/** Whether [key] is a group's conversation ([groupConversationKey]). */
fun isGroupKey(key: String): Boolean = key.startsWith("g:")

/** A key message as [me] read it: from [admin], key [keyId] of group [group], its name and members (the admin first). */
data class GroupKey(
    val hash: String,
    val height: Long,
    val time: Long,
    val index: Int,
    val admin: String,
    val group: String,
    val keyId: Int,
    /** The AES-256 key, base64. */
    val key: String,
    val name: String,
    val members: List<String>,
) {
    val conversation: String
        get() = groupConversationKey(admin, group)

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("admin", admin).put("group", group)
        .put("keyId", keyId).put("key", key).put("name", name).put("members", JSONArray(members))

    companion object {
        fun fromJson(json: JSONObject): GroupKey {
            val members = json.getJSONArray("members")
            return GroupKey(
                hash = json.getString("hash"),
                height = json.getLong("height"),
                time = json.getLong("time"),
                index = json.getInt("index"),
                admin = json.getString("admin"),
                group = json.getString("group"),
                keyId = json.getInt("keyId"),
                key = json.getString("key"),
                name = json.getString("name"),
                members = (0 until members.length()).map { members.getString(it) },
            )
        }
    }
}

private val random = SecureRandom()

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

/** A new group's id: 16 random bytes, hex. */
fun newGroupId(): String = bytesToHex(randomBytes(16)).removePrefix("0x")

/** A new group key: 32 random bytes, base64. */
fun newGroupKey(): String = Base64.getEncoder().encodeToString(randomBytes(32))

/** The plain text of a key message: the same for every member, each gets it encrypted for their own key. */
fun groupKeyPlain(admin: String, group: String, keyId: Int, key: String, name: String, members: List<String>): String =
    JSONObject().put("idenaGroup", GROUP_FORMAT).put("type", "key").put("admin", admin).put("group", group).put("keyId", keyId)
        .put("key", key).put("name", name).put("members", JSONArray(members)).toString()

/** The plain text of a group message under key [keyId]. */
fun groupMessagePlain(admin: String, group: String, keyId: Int, text: String): String =
    JSONObject().put("idenaGroup", GROUP_FORMAT).put("type", "message").put("admin", admin).put("group", group).put("keyId", keyId)
        .put("text", text).toString()

/**
 * The plain text of a removal notice: sent by the admin right after a change that removes [removed], sealed with the
 * key before it ([keyId]), so that the people removed, who never get the new key, read that they were.
 */
fun groupNoticePlain(admin: String, group: String, keyId: Int, removed: List<String>): String =
    JSONObject().put("idenaGroup", GROUP_FORMAT).put("type", "removed").put("admin", admin).put("group", group).put("keyId", keyId)
        .put("removed", JSONArray(removed)).toString()

/** [plain] encrypted with the group's [key]: `iv(12) || ciphertext+tag`, base64 (one part). */
fun encryptGroupPart(plain: String, key: String): String {
    val iv = randomBytes(12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(key), "AES"), GCMParameterSpec(128, iv))
    return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
}

/** A group part opened with [key]; null when it is not for that key. */
fun decryptGroupPart(part: String, key: String): String? =
    runCatching { Base64.getDecoder().decode(part) }.getOrNull()?.let { decryptIpfsText(it, key) }

/** The hash our group calls carry: the first part's, so that no reader can match it to a plain text. */
fun groupMessageHash(parts: List<String>): String = keccak256Hex(parts.first())

private fun JSONObject.isGroupFormat(type: String) = optInt("idenaGroup") == GROUP_FORMAT && optString("type") == type

/**
 * [call] as a key message for [me]: the plain text the node opened from [indexes] (exactly [me]'s part), from the
 * admin it names, one part per member, the admin first, distinct members [me] among them, a 32-byte key. Null otherwise.
 */
fun acceptGroupKey(call: MessageCall, plain: String, indexes: List<Int>, me: String): GroupKey? {
    if (call.messageHash != groupMessageHash(call.parts)) return null
    val json = runCatching { JSONObject(plain) }.getOrNull() ?: return null
    if (!json.isGroupFormat("key")) return null
    val admin = json.optString("admin")
    val group = json.optString("group")
    val keyId = json.opt("keyId") as? Int ?: return null
    val key = json.optString("key")
    val array = json.optJSONArray("members") ?: return null
    val members = (0 until array.length()).map { array.opt(it) as? String ?: return null }
    if (admin != call.from || !GROUP_ID.matches(group) || keyId < 0) return null
    if (runCatching { Base64.getDecoder().decode(key) }.getOrNull()?.size != 32) return null
    if (members.size != call.parts.size || members.size > MAX_GROUP_MEMBERS + 1 || members.firstOrNull() != admin) return null
    if (members.any { !MEMBER.matches(it) } || members.distinct().size != members.size) return null
    val mine = members.indexOf(me)
    if (mine < 0 || indexes != listOf(mine)) return null
    val name = json.optString("name").trim().take(MAX_GROUP_NAME)
    return GroupKey(call.hash, call.height, call.time, call.index, admin, group, keyId, key, name, members)
}

/**
 * [call] as a group message under [key]: one part that [key] opens, naming its group and key, from one of the key's
 * members, with a text; or the admin's removal notice ([ChatMessage.REMOVED]), naming members of the key other than
 * the admin. Null otherwise.
 */
fun acceptGroupMessage(call: MessageCall, key: GroupKey): ChatMessage? {
    if (call.parts.size != 1 || call.messageHash != groupMessageHash(call.parts)) return null
    val plain = decryptGroupPart(call.parts[0], key.key) ?: return null
    val json = runCatching { JSONObject(plain) }.getOrNull() ?: return null
    val notice = json.isGroupFormat("removed")
    if (!notice && !json.isGroupFormat("message")) return null
    if (json.optString("admin") != key.admin || json.optString("group") != key.group || json.opt("keyId") != key.keyId) return null
    if (call.from !in key.members) return null
    if (notice) {
        if (call.from != key.admin) return null
        val array = json.optJSONArray("removed") ?: return null
        val removed = (0 until array.length()).map { array.opt(it) as? String ?: return null }
        if (removed.isEmpty() || removed.distinct().size != removed.size) return null
        if (removed.any { it == key.admin || it !in key.members }) return null
        return ChatMessage(
            call.hash, call.height, call.time, call.index, call.from, key.members, "", group = key.conversation,
            kind = ChatMessage.REMOVED, removed = removed,
        )
    }
    val text = json.optString("text").ifEmpty { return null }
    return ChatMessage(call.hash, call.height, call.time, call.index, call.from, key.members, text, group = key.conversation)
}

/** A key message as a line of its conversation: who changed the group, and its members and name then. */
fun GroupKey.asChatMessage(): ChatMessage =
    ChatMessage(hash, height, time, index, admin, members, name, group = conversation, kind = ChatMessage.KEY)

/** A group as it stands after its last key message. */
data class GroupState(val key: GroupKey) {
    val members: List<String>
        get() = key.members
}

/** Orders events of a group by block, then place in the block. */
private val chainOrder = compareBy<ChatMessage>({ it.height }, { it.index })

/**
 * The lines of one group (its admin's: the conversation holds it), in chain order: its key messages ([keys], each a
 * line) and the messages read under them ([messages]), without a message from a member removed more than
 * [GROUP_GRACE_BLOCKS] blocks before it. A key message counts only with a key id not below the last one. Returns the
 * lines and the group's state.
 */
fun groupLines(keys: List<GroupKey>, messages: List<ChatMessage>): Pair<List<ChatMessage>, GroupState?> {
    val keyLines = keys.associateBy { it.hash }
    val events = (keys.map { it.asChatMessage() } + messages).distinctBy { it.hash }.sortedWith(chainOrder)
    var state: GroupState? = null
    val removedAt = HashMap<String, Long>()
    val lines = ArrayList<ChatMessage>()
    for (event in events) {
        val key = keyLines[event.hash]
        if (key != null) {
            val current = state?.key
            if (current != null && key.keyId < current.keyId) continue
            current?.members?.filter { it !in key.members }?.forEach { removedAt[it] = key.height }
            key.members.forEach { removedAt.remove(it) }
            state = GroupState(key)
            lines += event
            continue
        }
        val now = state
        if (now != null && event.from !in now.key.members) {
            val removed = removedAt[event.from] ?: continue
            if (event.height > removed + GROUP_GRACE_BLOCKS) continue
        }
        lines += event
    }
    return lines to state
}

/**
 * The groups of [keys] with their [messages] ([groupLines]), as conversations for [me]: the others are its members
 * ([me] left out). One waits in Requests until [me] wrote in it or its admin is followed or marked to message, as a
 * stranger's conversation. A removal notice shows only to the people it names (the others read the change in its key
 * message), and one naming [me] after the last key [me] holds marks the group [Conversation.removedMe].
 */
fun groupConversations(messages: List<ChatMessage>, keys: List<GroupKey>, me: String, people: Map<String, Person>): List<Conversation> {
    val byGroup = messages.groupBy { it.group }
    return keys.groupBy { it.conversation }.mapNotNull { (key, groupKeys) ->
        val (all, state) = groupLines(groupKeys, byGroup[key].orEmpty())
        val lines = all.filter { it.kind != ChatMessage.REMOVED || me in it.removed }
        if (state == null || lines.isEmpty()) return@mapNotNull null
        // Every key [me] holds names [me]: one after the notice means added again.
        val removedMe = lines.lastOrNull { it.kind.isNotEmpty() }?.kind == ChatMessage.REMOVED
        val others = state.members.filter { it != me }.ifEmpty { listOf(me) }
        val admin = state.key.admin
        val known = admin == me || people[admin]?.let { it.following || it.message } == true
        Conversation(
            key, others, lines, isRequest = !known && lines.none { it.from == me && it.kind.isEmpty() }, group = state, removedMe = removedMe,
        )
    }
}
