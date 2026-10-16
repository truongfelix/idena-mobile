package io.github.truongfelix.idenanode

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Starting a conversation: the people of the People list and those already written with, found by name or address
 * as it is typed; a whole address typed opens a conversation with it.
 */

/** Whether [address]'s name or address holds [query] (any case); every address matches a blank query. */
private fun matches(address: String, query: String, names: Map<String, String>): Boolean {
    val q = query.trim().lowercase()
    return q.isEmpty() || address.contains(q) || names[address]?.lowercase()?.contains(q) == true
}

/** The conversations of [conversations] with someone whose name or address holds [query]. */
fun findConversations(conversations: List<Conversation>, query: String, names: Map<String, String>): List<Conversation> =
    conversations.filter { c -> c.others.any { matches(it, query, names) } }

/**
 * Who a new conversation can be with, for [query] (lowercase addresses): a whole address typed first, then the
 * people of the one-to-one conversations (the latest first), then the People list in name order; never [me], each once.
 */
fun chatCandidates(query: String, people: Map<String, Person>, conversations: List<Conversation>, me: String): List<String> {
    val names = peopleNames(people)
    val typed = query.trim().lowercase().takeIf(::isAddress)
    val known = conversations.filter { !it.isGroup }.map { it.others.first() } + sortedPeople(people).map { it.address }
    return (listOfNotNull(typed) + known.filter { matches(it, query, names) }).distinct().filter { it != me.lowercase() }
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
                    Icon(Icons.Filled.Close, contentDescription = "Clear", Modifier.size(20.dp).clickable { onChange("") }, tint = colors.onSurfaceVariant)
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
