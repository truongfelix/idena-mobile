package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor

/** The feed's search bar, a rounded bar: texts of the posts read, contact names, or a pasted address; [trailing] at its end. */
@Composable
fun SearchField(model: SocialModel, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = model.searchQuery,
        onValueChange = {
            model.searchQuery = it.take(200)
            // The results show in the feed.
            if (model.view != SocialView.Feed) model.view = SocialView.Feed
        },
        modifier = Modifier.fillMaxWidth().height(40.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().background(colors.surfaceContainerHighest, RoundedCornerShape(20.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (model.searchQuery.isEmpty()) Text("Search, or r/ for the topics", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    field()
                }
                if (model.searchQuery.isNotEmpty()) {
                    TextButton(onClick = { model.searchQuery = "" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
                }
                trailing()
            }
        },
    )
}

fun hitKey(hit: SearchHit): String = when (hit) {
    is SearchHit.Address -> "address:${hit.address}"
    is SearchHit.Named -> "person:${hit.person.address}"
    is SearchHit.Text -> "text:${hit.node.id}"
}

/**
 * One search result: a profile to open (an address, a person), or a post, reply or comment drawn as in the
 * feed (flat, a thin line above unless [first]), opening its thread at it.
 */
@Composable
fun HitCard(
    hit: SearchHit,
    first: Boolean,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    onProfile: (String) -> Unit,
    onOpen: (threadId: Long, focusId: Long?) -> Unit,
    onLike: (PostTarget) -> Unit,
    onTip: (SocialPost) -> Unit,
    onWho: (SocialPost) -> Unit,
) {
    when (hit) {
        is SearchHit.Address -> Card(Modifier.fillMaxWidth().clickable { onProfile(hit.address) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(hit.address, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Open the profile of ${displayName(hit.address, LocalNames.current)}  ›", style = MaterialTheme.typography.titleSmall)
                    Text(shortAddress(hit.address), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        is SearchHit.Named -> PersonRow(hit.person) { onProfile(hit.person.address) }
        is SearchHit.Text -> Column {
            if (!first) HorizontalDivider()
            PostCard(
                post = hit.node,
                states = states,
                myAddress = myAddress,
                canAct = canAct,
                onOpen = { onOpen(hit.threadId, hit.node.id.takeIf { it != hit.threadId }) },
                onProfile = onProfile,
                onLike = { onLike(hit.target) },
                onTip = { onTip(hit.node) },
                onWho = { onWho(hit.node) },
            )
        }
    }
}
