package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Social settings (the ⚙ of the identity's profile): the people and topics followed, the muted and blocked accounts,
 * the hidden words and posts, the blocked topics, "Pictures in posts", and the lists' export and import.
 */
@Composable
fun SocialSettingsView(model: SocialModel, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t(R.string.social_settings_title), style = MaterialTheme.typography.titleLarge)
        SettingsLink(t(R.string.social_people_i_follow), followedPeople(model.people).size) { model.open(SocialPage.Following(topics = false)) }
        SettingsLink(t(R.string.social_topics_i_follow), model.followedTopics.size) { model.open(SocialPage.Following(topics = true)) }
        HorizontalDivider()
        SettingsLink(t(R.string.social_settings_muted), model.muted.size) { model.open(SocialPage.Muted) }
        SettingsLink(t(R.string.social_settings_blocked), model.blocked.size) { model.open(SocialPage.Blocked) }
        SettingsLink(t(R.string.social_settings_hidden_words), model.hidden.words.size) { model.open(SocialPage.HiddenWords) }
        SettingsLink(t(R.string.social_settings_hidden_posts), model.hidden.posts.size) { model.open(SocialPage.HiddenPosts) }
        SettingsLink(t(R.string.social_settings_blocked_topics), model.hidden.topics.size) { model.open(SocialPage.BlockedTopics) }
        HorizontalDivider()
        PicturesSetting(model.picturesMode, model::changePicturesMode)
        HorizontalDivider()
        ListsBackup(model)
    }
}

/**
 * ⚙ on the account's own profile (B-79): Edit your public profile ([onEdit], the profiles contract), Name on this
 * phone ([onName]) and Social settings.
 */
@Composable
fun OwnProfileMenu(onEdit: (() -> Unit)?, onName: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(40.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = t(R.string.social_profile_and_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onEdit?.let {
                DropdownMenuItem(text = { Text(t(R.string.social_edit_public_profile)) }, onClick = { open = false; it() })
            }
            DropdownMenuItem(text = { Text(t(R.string.social_name_on_phone)) }, onClick = { open = false; onName() })
            DropdownMenuItem(text = { Text(t(R.string.social_settings_title)) }, onClick = { open = false; onSettings() })
        }
    }
}

/** A row opening a list: its title, how many it holds, ›. */
@Composable
private fun SettingsLink(title: String, count: Int, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("$count", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
