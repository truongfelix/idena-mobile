package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Starting a conversation: the people of the People list, those already written with and every account the app knows
 * a name of (the profiles read: the feed's authors among them), found by name or address as it is typed; a whole
 * address typed, or a name claimed in the profiles contract (with or without its "@", [rememberNameHolder]), opens a
 * conversation with that account, followed or not.
 */

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** Whether [address]'s name or address holds [query] (any case, a leading "@" left out); every address matches a blank query. */
private fun matches(address: String, query: String, names: Map<String, String>): Boolean {
    val q = query.trim().lowercase().removePrefix("@")
    return q.isEmpty() || address.contains(q) || names[address]?.lowercase()?.removePrefix("@")?.contains(q) == true
}

/** The conversations of [conversations] with someone whose name or address holds [query], or a group so named. */
fun findConversations(conversations: List<Conversation>, query: String, names: Map<String, String>): List<Conversation> =
    conversations.filter { c ->
        c.others.any { matches(it, query, names) } || c.group?.key?.name?.lowercase()?.contains(query.trim().lowercase()) == true
    }

/**
 * Who a new conversation can be with, for [query] (lowercase addresses): a whole address typed first, then the holder
 * of the name typed ([holder], from the profiles contract), then the people of the one-to-one conversations (the latest
 * first), then the People list in name order, then, for a query that is not blank, the other accounts [known] names
 * (by lowercase address: the names shown, claimed ones included) whose name or address holds it; never [me], each once.
 */
fun chatCandidates(
    query: String,
    people: Map<String, Person>,
    conversations: List<Conversation>,
    me: String,
    known: Map<String, String> = emptyMap(),
    holder: String? = null,
): List<String> {
    val names = known + peopleNames(people)
    val typed = query.trim().lowercase().takeIf(::isAddress)
    val listed = conversations.filter { !it.isGroup }.map { it.others.first() } + sortedPeople(people).map { it.address }
    val others = if (query.isBlank()) emptyList() else known.keys.sortedBy { known[it]?.lowercase() }
    return (listOfNotNull(typed, holder?.lowercase()) + (listed + others).filter { matches(it, query, names) })
        .distinct().filter { it != me.lowercase() }
}

/**
 * The name [query] holds when it can be one claimed in the profiles contract ("@name" or "name", [PROFILE_NAME]),
 * lowercase without its "@"; else null.
 */
fun claimableName(query: String): String? = query.trim().lowercase().removePrefix("@").takeIf { PROFILE_NAME.matches(it) }

/**
 * The account of the message code [query] may be ([parseMessageCode]), its key kept for it ([SocialModel.addMessageCode]);
 * null for a query that is no code.
 */
@Composable
fun rememberMessageCodeAddress(model: SocialModel, query: String): String? {
    val key = parseMessageCode(query)
    val address = key?.let(::addressOfPublicKey)
    LaunchedEffect(key) { if (key != null && address != null) model.addMessageCode(address, key) }
    return address
}

/**
 * The holder of the name [query] may be ([claimableName]), asked from the node's profiles contract once the typing
 * pauses: null while asked, for a query that is no name, or when nobody holds it.
 */
@Composable
fun rememberNameHolder(query: String): String? {
    val context = LocalContext.current
    val name = claimableName(query)
    var holder by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        if (name == null) return@LaunchedEffect
        delay(300)
        holder = withContext(Dispatchers.IO) { runCatching { NodeProfilesSource(rpc(context)).resolve(name).holder }.getOrNull() }
    }
    return holder
}

/** A rounded search bar, as the feed's ([SearchField]). */
@Composable
fun RoundSearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(200)) },
        modifier = modifier.fillMaxWidth().height(40.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().background(colors.surfaceContainerHighest, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    field()
                }
                if (value.isNotEmpty()) {
                    Icon(Icons.Filled.Close, contentDescription = t(R.string.socialb_clear), Modifier.size(20.dp).clickable { onChange("") }, tint = colors.onSurfaceVariant)
                }
            }
        },
    )
}

/** A person to write to: avatar, name, address; a tap opens the conversation. */
@Composable
fun PersonRow(address: String, names: Map<String, String>, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(address, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            names[address]?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                shortAddress(address),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
