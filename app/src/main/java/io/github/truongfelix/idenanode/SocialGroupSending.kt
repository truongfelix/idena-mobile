package io.github.truongfelix.idenanode

import java.io.IOException

/*
 * Sending to a group with a shared key ([GroupKey], notes/group-chat-design.md in the workspace): the key messages of
 * its admin (a new group, a member or name change), and the messages and leaves of its members. Each is one
 * sendMessage call, shown with its fee before it is sent, as a private message.
 */

/**
 * A key message: the group [group] (a new one when null) named [name], its [members] besides the account (lowercase)
 * with their public [keys] in the same order, and a new key. The account is its admin.
 */
fun SocialSending.prepareGroupKey(group: GroupKey?, name: String, members: List<String>, keys: List<String>, onSent: () -> Unit = {}) =
    prepareWith(t(if (group == null) R.string.group_what_create else R.string.group_what_change)) { posting, from ->
        val me = from.lowercase()
        val others = members.filter { it != me }.distinct()
        if (others.isEmpty() || others.size > MAX_GROUP_MEMBERS) throw IOException(t(R.string.group_error_size, MAX_GROUP_MEMBERS))
        if (keys.size != others.size) throw IOException(t(R.string.social_error_no_key))
        if (group != null && group.admin != me) throw IOException(t(R.string.group_error_not_admin))
        val plain = groupKeyPlain(
            me, group?.group ?: newGroupId(), (group?.keyId ?: -1) + 1, newGroupKey(), name.trim().take(MAX_GROUP_NAME), listOf(me) + others,
        )
        val parts = posting.encrypt(keys, plain)
        if (parts.size != others.size + 1) throw IOException(t(R.string.social_error_parts, parts.size, others.size + 1))
        val call = SocialPosting.message(sendMessageArgument(parts, groupMessageHash(parts)))
        val what = t(if (group == null) R.string.group_what_create else R.string.group_what_change)
        PendingPost(what, call, from, posting.estimate(from, call), tp(R.plurals.group_key_detail, others.size, others.size), onSent = onSent)
    }

/** A message [text] to the group of [key] (its current key), or leaving it ([leave]: no text). */
fun SocialSending.prepareGroupMessage(key: GroupKey, text: String, leave: Boolean = false, onSent: () -> Unit = {}) =
    prepareWith(t(if (leave) R.string.group_what_leave else R.string.social_what_message)) { posting, from ->
        val plain = groupMessagePlain(key.admin, key.group, key.keyId, if (leave) "leave" else "message", if (leave) "" else text)
        val parts = listOf(encryptGroupPart(plain, key.key))
        val call = SocialPosting.message(sendMessageArgument(parts, groupMessageHash(parts)))
        val what = t(if (leave) R.string.group_what_leave else R.string.social_what_message)
        PendingPost(what, call, from, posting.estimate(from, call), t(R.string.group_message_detail, key.name), onSent = onSent)
    }
