package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A profile's people (B-79, user 2026-10-17): whom the identity invited and the node still links to it (until each is
// Verified or Human, or killed), and the members it vouches for in the profiles contract; each with its state.

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** The invitees and members of a profile (lowercase addresses), and the identity of each, read together. */
data class ProfileCircle(val invitees: List<String>, val members: List<String>, val identities: Map<String, ProfileIdentity>)

/**
 * The invitees and members of [address], once its [identity] is read (the invitees come with it), and again after
 * each profile call in a block ([reload]); null while they are read.
 */
@Composable
fun rememberProfileCircle(address: String, identity: ProfileIdentity?, reload: Int): Result<ProfileCircle>? {
    val context = LocalContext.current
    var circle by remember(address) { mutableStateOf<Result<ProfileCircle>?>(null) }
    LaunchedEffect(address, identity, reload) {
        if (identity == null) return@LaunchedEffect
        circle = withContext(Dispatchers.IO) {
            runCatching {
                val members = NodeProfilesSource(rpc(context)).members(address).map { it.address }
                val identities = NodeSocialSource(rpc(context)).identities(identity.invitees + members)
                ProfileCircle(identity.invitees, members, identities)
            }
        }
    }
    return circle
}

/** "Newbie · age 2", "Candidate": an invitee's or a member's state as a list shows it. */
fun circleStatus(identity: ProfileIdentity?): String {
    if (identity == null) return "Not validated"
    val age = if (identity.age > 0) " · age ${identity.age}" else ""
    return identity.status + age
}

/** One person of a profile's lists: picture or robot, name, state; a tap opens its profile. */
@Composable
fun CirclePersonRow(address: String, identity: ProfileIdentity?, onProfile: (String) -> Unit) {
    val names = LocalNames.current
    Row(
        Modifier.fillMaxWidth().clickable { onProfile(address) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonAvatar(address, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(displayName(address, names), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(circleStatus(identity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What an empty Invitees or Members tab says. */
fun emptyCircleText(tab: ProfileTab): String = when (tab) {
    ProfileTab.Invitees -> "No invitee on the way: an invitee shows here from its invitation until it is Verified."
    else -> "No vouched member."
}
