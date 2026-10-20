package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * idena.social's private messages (the contract's sendMessage), in its own format so that our apps and the website
 * read each other's (notes/social-messages.md in the workspace). The plain message is a JSON array, encrypted once per
 * participant (the sender first) with the participant's public key; the node encrypts and decrypts them
 * (social_encrypt / social_decrypt), its key never leaving it. On chain the sender, the time, the size and the number
 * of parts are public, the participants are not.
 */

/** A sendMessage call as found in a block: the message's encrypted [parts] (base64, one per participant) and its hash. */
data class MessageCall(
    val hash: String,
    val height: Long,
    val time: Long,
    val index: Int,
    /** The sender, lowercase. */
    val from: String,
    val parts: List<String>,
    /** keccak256 of the plain message, lowercase hex without 0x. */
    val messageHash: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("from", from)
        .put("parts", JSONArray(parts)).put("messageHash", messageHash)

    companion object {
        /** Parts larger than this (base64 characters) are not kept: a message with one is left out. */
        const val MAX_PART = 16_000

        /** The most parts kept (the node decrypts at most 256 at once). */
        const val MAX_PARTS = 256

        fun fromJson(json: JSONObject): MessageCall {
            val parts = json.getJSONArray("parts")
            return MessageCall(
                hash = json.getString("hash"),
                height = json.getLong("height"),
                time = json.getLong("time"),
                index = json.getInt("index"),
                from = json.getString("from"),
                parts = (0 until parts.length()).map { parts.getString(it) },
                messageHash = json.getString("messageHash"),
            )
        }

        /**
         * The message of a sendMessage call from its argument ({"message": [...], "messageHash", "encrypted": true});
         * null when it is not encrypted, has no part, or parts too large or too many. The website reads only those of 2
         * parts or more; one part is a group's message ([acceptGroupMessage]).
         */
        fun parse(hash: String, height: Long, time: Long, index: Int, from: String, argHex: String): MessageCall? {
            val text = runCatching { String(hexToBytes(argHex), Charsets.UTF_8) }.getOrNull() ?: return null
            val args = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (args.opt("encrypted")?.toString() != "true") return null
            val array = args.optJSONArray("message") ?: return null
            if (array.length() < 1 || array.length() > MAX_PARTS) return null
            val parts = (0 until array.length()).map { array.opt(it) as? String ?: return null }
            if (parts.any { it.isEmpty() || it.length > MAX_PART }) return null
            val messageHash = args.optString("messageHash").lowercase().removePrefix("0x")
            if (!HASH.matches(messageHash)) return null
            return MessageCall(hash, height, time, index, from.lowercase(), parts, messageHash)
        }

        private val HASH = Regex("^[0-9a-f]{64}$")
    }
}

/** A message as our apps show it: who sent it, to whom, and its text ("ipfs://<cid>" with [textPassword] when on IPFS). */
data class ChatMessage(
    val hash: String,
    val height: Long,
    val time: Long,
    val index: Int,
    val from: String,
    /** Every participant, lowercase, the sender first. */
    val participants: List<String>,
    val text: String,
    val textPassword: String = "",
    /** Whether it carries a picture or other media (not shown yet). */
    val hasMedia: Boolean = false,
    /** Whether it answers another message (a reply or a ❤️). */
    val isReply: Boolean = false,
    /** The group's conversation ([groupConversationKey]) for a group's line; empty for idena.social's messages. */
    val group: String = "",
    /** What a group's line is: a message (empty), a key message ([KEY]: [text] is the name) or a member leaving ([LEAVE]). */
    val kind: String = "",
) {
    /** The conversation it belongs to: its group, or its participants sorted and distinct. */
    val conversation: String
        get() = group.ifEmpty { conversationKey(participants) }

    fun toJson(): JSONObject = JSONObject()
        .put("hash", hash).put("height", height).put("time", time).put("index", index).put("from", from)
        .put("participants", JSONArray(participants)).put("text", text).put("textPassword", textPassword)
        .put("hasMedia", hasMedia).put("isReply", isReply).put("group", group).put("kind", kind)

    companion object {
        /** A group's key message ([kind]). */
        const val KEY = "key"

        /** A member leaving a group ([kind]). */
        const val LEAVE = "leave"

        fun fromJson(json: JSONObject): ChatMessage {
            val participants = json.getJSONArray("participants")
            return ChatMessage(
                hash = json.getString("hash"),
                height = json.getLong("height"),
                time = json.getLong("time"),
                index = json.getInt("index"),
                from = json.getString("from"),
                participants = (0 until participants.length()).map { participants.getString(it) },
                text = json.optString("text"),
                textPassword = json.optString("textPassword"),
                hasMedia = json.optBoolean("hasMedia"),
                isReply = json.optBoolean("isReply"),
                group = json.optString("group"),
                kind = json.optString("kind"),
            )
        }
    }
}

/** A conversation's key: its participants (lowercase), sorted and distinct, joined by "-". */
fun conversationKey(participants: List<String>): String = participants.map { it.lowercase() }.distinct().sorted().joinToString("-")

private val ADDRESS = Regex("^0x[0-9a-f]{40}$")

/**
 * [call] as [me] (lowercase) reads it, from its decrypted [plain] text and the [indexes] of the parts the node's key
 * opened; null when the website's reader would drop it (asyncUtils.ts:840-945): a hash that does not match, as many
 * participants as parts, the sender first, lowercase addresses, no duplicate in a group, and exactly my parts opened.
 */
fun acceptMessage(call: MessageCall, plain: String, indexes: List<Int>, me: String): ChatMessage? {
    if (keccak256Hex(plain) != call.messageHash) return null
    val array = runCatching { JSONArray(plain) }.getOrNull() ?: return null
    val people = array.optJSONArray(0) ?: return null
    val participants = (0 until people.length()).map { people.opt(it) as? String ?: return null }
    if (participants.size != call.parts.size || participants.any { !ADDRESS.matches(it) }) return null
    if (participants[0] != call.from) return null
    if (participants.size > 2 && participants.distinct().size != participants.size) return null
    val mine = participants.indices.filter { participants[it] == me }
    if (mine.isEmpty() || indexes.sorted() != mine) return null
    val media = array.optJSONArray(5)
    return ChatMessage(
        call.hash, call.height, call.time, call.index, call.from, participants,
        text = array.opt(2) as? String ?: "",
        textPassword = array.opt(3) as? String ?: "",
        hasMedia = media != null && media.length() > 0,
        isReply = (array.opt(4) as? String).orEmpty().isNotEmpty(),
    )
}

/**
 * The plain message our apps send: idena.social's JSON array, as its JSON.stringify writes it: [sender, ...recipients],
 * no channel, the text, no text password, not a reply, no media, no tags.
 */
fun plainMessage(sender: String, recipients: List<String>, text: String): String = buildString {
    append("[[")
    (listOf(sender) + recipients).forEachIndexed { i, address ->
        if (i > 0) append(',')
        append(jsonString(address.lowercase()))
    }
    append("],\"\",").append(jsonString(text)).append(",\"\",\"\",[],[],\"\",[]]")
}

/** The argument of a sendMessage call: the encrypted [parts] in participant order, and the plain message's hash. */
fun sendMessageArgument(parts: List<String>, messageHash: String): String = buildString {
    append("{\"message\":[")
    parts.forEachIndexed { i, part ->
        if (i > 0) append(',')
        append(jsonString(part))
    }
    append("],\"messageHash\":").append(jsonString(messageHash)).append(",\"encrypted\":true}")
}

/** A text the website stored on IPFS: `iv(12) || ciphertext+tag` (AES-256-GCM), its key in base64. Null when it does not open. */
fun decryptIpfsText(bytes: ByteArray, keyBase64: String): String? = runCatching {
    val key = Base64.getDecoder().decode(keyBase64)
    if (key.size != 32 || bytes.size < 12 + 16) return null
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
    String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
}.getOrNull()

/**
 * A conversation: the others in it, its messages (oldest first), and whether it waits in Requests; a group with a
 * shared key has its [group] (its name, admin and members), its key messages and leaves among the messages.
 */
data class Conversation(
    val key: String,
    val others: List<String>,
    val messages: List<ChatMessage>,
    val isRequest: Boolean,
    val group: GroupState? = null,
) {
    val last: ChatMessage
        get() = messages.last()

    val isGroup: Boolean
        get() = others.size > 1 || group != null
}

/**
 * The conversations of [messages] for [me], the latest first. One waits in Requests until [me] wrote in it or
 * someone in it is followed or marked to message ([Person.message]): a stranger's first message does not show among
 * the conversations.
 */
fun conversations(
    messages: List<ChatMessage>,
    me: String,
    people: Map<String, Person>,
    groupKeys: List<GroupKey> = emptyList(),
): List<Conversation> =
    (messages.filter { it.group.isEmpty() }.groupBy { it.conversation }
        .map { (key, list) ->
            val sorted = list.sortedWith(compareBy({ it.height }, { it.index }))
            val others = key.split('-').filter { it != me }.ifEmpty { listOf(me) }
            val known = others.any { people[it]?.let { p -> p.following || p.message } == true }
            Conversation(key, others, sorted, isRequest = !known && sorted.none { it.from == me })
        } + groupConversations(messages.filter { it.group.isNotEmpty() }, groupKeys, me, people))
        .sortedWith(compareByDescending<Conversation> { it.last.height }.thenByDescending { it.last.index })

/** The messages of [conversation] from others after [seen] (a height and index), the unread ones. */
fun unreadCount(conversation: Conversation, me: String, seen: Pair<Long, Int>?): Int =
    conversation.messages.count { it.from != me && it.kind.isEmpty() && (seen == null || it.height > seen.first || it.height == seen.first && it.index > seen.second) }

/** A public key as the node writes it ("0x" optional), in lowercase hex without 0x; null when it is not one (33, 64 or 65 bytes). */
fun publicKeyHex(text: String): String? {
    val hex = text.removePrefix("0x").lowercase()
    return hex.takeIf { it.length in setOf(66, 128, 130) && it.all { c -> c in "0123456789abcdef" } }
}

/** The blocks asked again for a key at most (the newest calls of the address first). */
const val KEY_LOOKUP_BLOCKS = 3

/**
 * The public key to encrypt a message for [address] (lowercase), or null when none is known: the one of its identity
 * ([identityKey]: an address that activated an invitation), else one the scan kept from its calls ([scannedKeys]), else
 * one read again from a block where it called the contract ([callHeights], newest first; [keysAt] asks the node for a
 * block's calls and their keys). An address that never signed anything has none: no message can be encrypted for it.
 */
fun findPublicKey(
    address: String,
    identityKey: (String) -> String,
    scannedKeys: Map<String, String>,
    callHeights: List<Long>,
    keysAt: (Long) -> Map<String, String>,
): String? {
    publicKeyHex(identityKey(address))?.let { return it }
    scannedKeys[address]?.let(::publicKeyHex)?.let { return it }
    for (height in callHeights.take(KEY_LOOKUP_BLOCKS)) {
        keysAt(height)[address]?.let(::publicKeyHex)?.let { return it }
    }
    return null
}
