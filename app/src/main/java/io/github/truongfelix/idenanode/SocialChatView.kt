package io.github.truongfelix.idenanode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A profile's ✉: opens the conversation with an address, tells whether a message can be encrypted for it (true, false
 * when no public key is known, null while not looked up) and looks it up.
 */
class ChatAccess(val open: (String) -> Unit, val canReceive: (String) -> Boolean?, val lookUp: (String) -> Unit)

/** The profile's ✉ ([ChatAccess]); null where conversations cannot open. */
val LocalOpenChat = staticCompositionLocalOf<ChatAccess?> { null }

/** Why no message can be sent to [addresses]: no public key is known for them. */
fun noKeyText(addresses: List<String>, names: Map<String, String>): String =
    tp(R.plurals.socialb_no_key, addresses.size, addresses.joinToString(", ") { displayName(it, names) })

/** The people of a conversation as a title: their names, or short addresses. */
private fun othersTitle(others: List<String>, names: Map<String, String>): String =
    others.joinToString(", ") { displayName(it, names) }

/** One line of a message for the list: its text, or what it is. */
private fun preview(message: ChatMessage): String = when {
    message.text.startsWith("ipfs://") -> t(R.string.socialb_preview_ipfs)
    message.text.isNotEmpty() -> message.text.replace('\n', ' ')
    message.hasMedia -> t(R.string.socialb_preview_picture)
    else -> "…"
}

/**
 * The Messages tab: the conversations, the latest first, with what was not read; Requests (strangers) behind their
 * own line. The search finds conversations and people by name or address: a person found opens a new conversation.
 */
@Composable
fun MessagesList(model: SocialModel, modifier: Modifier) {
    val me = model.myAddress.orEmpty()
    val all = model.conversations
    var requests by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val waiting = all.count { it.isRequest }
    val searching = query.isNotBlank()
    val shown = if (searching) findConversations(all, query, model.names) else all.filter { it.isRequest == requests }
    // Searching, the people with no one-to-one conversation shown come after the conversations.
    val people = if (!searching) emptyList() else {
        val withChat = shown.filter { !it.isGroup }.map { it.others.first() }.toSet()
        chatCandidates(query, model.people, all, me).filter { it !in withChat }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (requests) t(R.string.socialb_requests) else t(R.string.socialb_messages), style = MaterialTheme.typography.titleLarge)
        RoundSearchField(query, { query = it }, t(R.string.socialb_search_name_or_address))
        LazyColumn(Modifier.weight(1f), state = model.messagesState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (!searching) item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (requests) TextButton(onClick = { requests = false }) { Text(t(R.string.socialb_back_to_conversations)) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { model.open(SocialPage.GroupEdit()) }) { Text(t(R.string.group_new)) }
                        if (waiting > 0) TextButton(onClick = { requests = true }) { Text(t(R.string.socialb_requests_count, waiting)) }
                    }
                    if (requests) Text(
                        t(R.string.socialb_requests_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (shown.isEmpty() && people.isEmpty()) item(key = "none") {
                Text(
                    when {
                        searching -> t(R.string.socialb_no_one_by_name)
                        requests -> t(R.string.socialb_no_request)
                        model.cache?.messagesPending == true -> t(R.string.socialb_no_conversation_pending)
                        else -> t(R.string.socialb_no_conversation)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(shown, key = { it.key }) { conversation ->
                val unread = unreadCount(conversation, me, model.chats.store.seen[conversation.key])
                ConversationRow(conversation, model.groupTitle(conversation), me, model.chatNames(conversation.key), unread) {
                    model.open(SocialPage.Chat(conversation.key))
                }
            }
            items(people, key = { "person:$it" }) { address -> PersonRow(address, model.names) { model.openChat(listOf(address)) } }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, title: String, me: String, names: Map<String, String>, unread: Int, onOpen: () -> Unit) {
    val group = conversation.group
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            if (group != null) GroupAvatar(44.dp) else PersonAvatar(conversation.others.first(), 44.dp)
            if (group == null && conversation.isGroup) {
                Text(
                    "+${conversation.others.size - 1}",
                    Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (group != null) title else othersTitle(conversation.others, names),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (unread > 0) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (conversation.last.kind.isEmpty()) preview(conversation.last)
                else groupEventText(conversation.last, previousKeyLine(conversation, conversation.last), me, names).lines().first(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(ago(conversation.last.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (unread > 0) {
                Text(
                    "$unread",
                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * The conversation of [key]: who it is with (ⓘ: what the public sees), its messages, oldest first, the account's on
 * the right; Accept for a request; and the box to answer, sent to every other participant after the fee is shown.
 * A conversation with no message yet opens with the keyboard.
 */
@Composable
fun ChatView(model: SocialModel, key: String, listState: LazyListState, canSend: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val me = model.myAddress.orEmpty()
    val conversation = model.conversations.firstOrNull { it.key == key }
    val group = conversation?.group
    val names = model.chatNames(key)
    val others = conversation?.others ?: key.split('-').filter { it != me }.ifEmpty { listOf(me) }
    val messages = conversation?.messages.orEmpty()
    // The text is the model's: it stays while the conversation is closed, another tab shown or the node panel open.
    val draft = model.chatDrafts[key].orEmpty()
    val setDraft = { text: String -> model.chatDrafts[key] = text }
    // The text stays until the node took the message (the fee's dialog can be cancelled): sent, it waits for its block.
    // A group's messages need no member's key: they are encrypted with the group's.
    LaunchedEffect(key) { if (!isGroupKey(key)) model.lookUpKeys(others) }
    val noKey = if (isGroupKey(key)) emptyList() else others.filter { model.messageKeys[it] == "" }
    val looking = !isGroupKey(key) && others.any { it !in model.messageKeys }
    // A group left on this phone. A member removed cannot tell: the newer key never reaches them.
    val outOfGroup = isGroupKey(key) && group == null
    LaunchedEffect(conversation?.last?.hash) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size)
        conversation?.let { model.chats.markSeen(it) }
    }
    var privacy by remember { mutableStateOf(false) }
    // Each key message's line reads against the one before it: who was added or removed.
    val previousKeys = remember(messages) {
        var last: ChatMessage? = null
        messages.associate { m -> m.hash to last.also { if (m.kind == ChatMessage.KEY) last = m } }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(key) { if (messages.isEmpty()) runCatching { focus.requestFocus() } }
    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t(R.string.common_back)) }
            Row(
                Modifier.weight(1f).clickable(enabled = group != null || others.size == 1) {
                    if (group != null) privacy = true else model.openProfile(others.first())
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group != null) GroupAvatar(36.dp) else PersonAvatar(others.first(), 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (conversation != null && group != null) model.groupTitle(conversation) else othersTitle(others, model.names),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { privacy = true }) { Icon(Icons.Outlined.Info, contentDescription = t(R.string.socialb_who_sees_what)) }
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (conversation?.isRequest == true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t(R.string.socialb_not_in_conversations), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { model.acceptChat(conversation) }) { Text(t(R.string.socialb_accept)) }
                        }
                    }
                    if (messages.isEmpty()) Text(t(R.string.socialb_no_message_yet), style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(messages, key = { it.hash }) { message ->
                if (message.kind.isNotEmpty()) GroupEventLine(groupEventText(message, previousKeys[message.hash], me, names))
                else Bubble(message, mine = message.from == me, showSender = group != null || others.size > 1, names = names)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            when {
                outOfGroup -> Text(t(R.string.group_not_member), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                noKey.isNotEmpty() -> Text(noKeyText(noKey, model.names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                looking -> Text(t(R.string.socialb_looking_up_keys), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!outOfGroup) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { setDraft(it.take(MAX_MESSAGE)) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(t(R.string.socialb_message_placeholder)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                // Sending does not accept a request: once the message is in a block, the conversation holds one of the
                // account's and leaves Requests ([conversations]); a send that fails leaves it there.
                FilledIconButton(
                    onClick = { if (conversation?.group != null) model.sendGroupMessage(conversation, draft.trim()) else model.sendMessage(others, draft.trim(), key) },
                    enabled = canSend && draft.isNotBlank() && noKey.isEmpty() && !looking,
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = t(R.string.common_send)) }
            }
        }
    }
    if (privacy && group != null) {
        GroupInfoDialog(
            title = conversation?.let(model::groupTitle).orEmpty(),
            group = group,
            me = me,
            names = names,
            onRenameGroup = { model.chats.setGroupName(key, it) },
            onRenameMember = { member, name -> model.chats.setMemberName(key, member, name) },
            onEdit = { model.open(SocialPage.GroupEdit(key)) },
            onLeave = { conversation?.let(model::leaveGroup) },
            onDismiss = { privacy = false },
        )
    } else if (privacy) {
        AlertDialog(
            onDismissRequest = { privacy = false },
            text = { Text(t(R.string.socialb_privacy_note)) },
            confirmButton = { TextButton(onClick = { privacy = false }) { Text(t(R.string.common_ok)) } },
        )
    }
}

/** The most characters of a message written here (the fee grows with the text). */
private const val MAX_MESSAGE = 2000

@Composable
private fun Bubble(message: ChatMessage, mine: Boolean, showSender: Boolean, names: Map<String, String>) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) colors.primaryContainer else colors.surfaceContainerHigh, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showSender && !mine) Text(displayName(message.from, names), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            if (message.isReply) Text(t(R.string.socialb_reply_note), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (message.text.isNotEmpty()) MessageText(message.text, message.textPassword, MaterialTheme.typography.bodyLarge)
            if (message.hasMedia) Text(t(R.string.socialb_picture_not_shown), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(ago(message.time), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
