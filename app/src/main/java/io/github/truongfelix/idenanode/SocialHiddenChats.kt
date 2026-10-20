package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The conversations hidden on this phone (Social settings), one-to-one or groups, with the messages come since they
 * were read; Show again puts one back among the conversations.
 */
@Composable
fun HiddenChatsView(model: SocialModel, modifier: Modifier = Modifier) {
    val me = model.myAddress.orEmpty()
    val hidden = model.hiddenConversations
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text(t(R.string.chat_hidden_title), style = MaterialTheme.typography.titleLarge) }
        item { Text(t(R.string.chat_hidden_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (hidden.isEmpty()) item { Text(t(R.string.chat_none_hidden), style = MaterialTheme.typography.bodyMedium) }
        items(hidden, key = { it.key }) { conversation ->
            val unread = model.chats.store.unreadCount(conversation, me)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (conversation.group != null) GroupAvatar(36.dp) else PersonAvatar(conversation.others.first(), 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.chatTitle(conversation),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unread > 0) {
                        Text(tp(R.plurals.chat_hidden_unread, unread, unread), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedButton(onClick = { model.chats.setHidden(conversation.key, false) }) { Text(t(R.string.chat_show_again)) }
            }
        }
    }
}
