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
    "${addresses.joinToString(", ") { displayName(it, names) }} cannot receive private messages: no public key is known " +
        "for ${if (addresses.size == 1) "this address" else "these addresses"} (never an Idena identity, and no " +
        "transaction it signed on idena.social), so nothing can be encrypted for it."

/** What the public sees of a message, said above every conversation. */
private const val PRIVACY_NOTE =
    "Only the people in the conversation can read the text. Who writes, when, how long the message is and how many " +
        "people it goes to are public, on the chain for good; who gets it is not."

/** The people of a conversation as a title: their names, or short addresses. */
private fun othersTitle(others: List<String>, names: Map<String, String>): String =
    others.joinToString(", ") { displayName(it, names) }

/** One line of a message for the list: its text, or what it is. */
private fun preview(message: ChatMessage): String = when {
    message.text.startsWith("ipfs://") -> "Text on IPFS"
    message.text.isNotEmpty() -> message.text.replace('\n', ' ')
    message.hasMedia -> "🖼 Picture"
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
        Text(if (requests) "Requests" else "Messages", style = MaterialTheme.typography.titleLarge)
        RoundSearchField(query, { query = it }, "Search a name or address")
        LazyColumn(Modifier.weight(1f), state = model.messagesState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (!searching) item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (requests) TextButton(onClick = { requests = false }) { Text("‹ Conversations") }
                    else if (waiting > 0) TextButton(onClick = { requests = true }) { Text("Requests ($waiting) ›") }
                    if (requests) Text(
                        "Messages from people you do not follow and never wrote to. Answer or accept to move them to your conversations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (shown.isEmpty() && people.isEmpty()) item(key = "none") {
                Text(
                    when {
                        searching -> "No one by that name: paste their address."
                        requests -> "No request."
                        model.cache?.messagesPending == true -> "No conversation yet. The messages of the blocks read before are still being read."
                        else -> "No conversation yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(shown, key = { it.key }) { conversation ->
                val unread = unreadCount(conversation, me, model.chats.store.seen[conversation.key])
                ConversationRow(conversation, model.names, unread) { model.open(SocialPage.Chat(conversation.key)) }
            }
            items(people, key = { "person:$it" }) { address -> PersonRow(address, model.names) { model.openChat(listOf(address)) } }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, names: Map<String, String>, unread: Int, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            PersonAvatar(conversation.others.first(), 44.dp)
            if (conversation.isGroup) {
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
                othersTitle(conversation.others, names),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (unread > 0) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview(conversation.last),
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
    val others = conversation?.others ?: key.split('-').filter { it != me }.ifEmpty { listOf(me) }
    val messages = conversation?.messages.orEmpty()
    var draft by rememberSaveable(key) { mutableStateOf("") }
    // The text stays until the message is sent (the fee's dialog can be cancelled).
    LaunchedEffect(model.sending.sendStatus) {
        if (model.sending.sendStatus?.startsWith("Message sent") == true) draft = ""
    }
    LaunchedEffect(key) { model.lookUpKeys(others) }
    val noKey = others.filter { model.messageKeys[it] == "" }
    val looking = others.any { it !in model.messageKeys }
    LaunchedEffect(conversation?.last?.hash) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size)
        conversation?.let { model.chats.markSeen(it) }
    }
    var privacy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(key) { if (messages.isEmpty()) runCatching { focus.requestFocus() } }
    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Row(
                Modifier.weight(1f).clickable(enabled = others.size == 1) { model.openProfile(others.first()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonAvatar(others.first(), 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(othersTitle(others, model.names), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { privacy = true }) { Icon(Icons.Outlined.Info, contentDescription = "Who sees what") }
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
                            Text("Not in your conversations yet.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { model.acceptChat(conversation) }) { Text("Accept") }
                        }
                    }
                    if (messages.isEmpty()) Text("No message yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(messages, key = { it.hash }) { message -> Bubble(message, mine = message.from == me, showSender = others.size > 1, names = model.names) }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            when {
                noKey.isNotEmpty() -> Text(noKeyText(noKey, model.names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                looking -> Text("Looking up the keys to encrypt for…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_MESSAGE) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                // Sending does not accept a request: once the message is in a block, the conversation holds one of the
                // account's and leaves Requests ([conversations]); a send that fails leaves it there.
                FilledIconButton(
                    onClick = { model.sendMessage(others, draft.trim()) },
                    enabled = canSend && draft.isNotBlank() && noKey.isEmpty() && !looking,
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
    if (privacy) {
        AlertDialog(
            onDismissRequest = { privacy = false },
            text = { Text(PRIVACY_NOTE) },
            confirmButton = { TextButton(onClick = { privacy = false }) { Text("OK") } },
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
            if (message.isReply) Text("↩ An answer to another message", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (message.text.isNotEmpty()) MessageText(message.text, message.textPassword, MaterialTheme.typography.bodyLarge)
            if (message.hasMedia) Text("🖼 A picture, not shown here yet", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(ago(message.time), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
