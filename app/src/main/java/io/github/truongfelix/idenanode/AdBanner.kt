package io.github.truongfelix.idenanode

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The banner's height: the screen keeps the room for it. */
val AD_BANNER_HEIGHT = 56.dp

/** How long each item of the banner shows. */
private const val ROTATION_MS = 10_000L

/** What the banner rotates: the ads, then the developers' note. */
private sealed interface BannerItem {
    class Ad(val ad: ChainAd) : BannerItem
    data object DevNote : BannerItem
}

/**
 * The banner at the bottom of both tabs: the approved Idena ads for this identity in turn, and the
 * developers' note. A tap opens the item.
 */
@Composable
fun AdBanner(ads: List<ChainAd>, modifier: Modifier = Modifier) {
    val items = remember(ads) { ads.map { BannerItem.Ad(it) } + BannerItem.DevNote }
    var index by remember(items) { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<BannerItem?>(null) }
    LaunchedEffect(items) {
        while (items.size > 1) {
            delay(ROTATION_MS)
            index = (index + 1) % items.size
        }
    }
    Surface(modifier.fillMaxWidth().height(AD_BANNER_HEIGHT), color = MaterialTheme.colorScheme.surfaceVariant) {
        Crossfade(items[index % items.size], label = "ad") { item ->
            Row(
                Modifier.fillMaxWidth().clickable { open = item }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item) {
                    is BannerItem.Ad -> {
                        AdImage(item.ad.content.thumb, maxSide = 160, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                        BannerText(item.ad.content.title, item.ad.content.desc, Modifier.weight(1f))
                        Label("Ad")
                    }
                    BannerItem.DevNote -> {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        BannerText(DEV_NOTE_TITLE, DEV_NOTE_LINE, Modifier.weight(1f))
                        Label("Note")
                    }
                }
            }
        }
    }
    when (val item = open) {
        is BannerItem.Ad -> AdDialog(item.ad) { open = null }
        BannerItem.DevNote -> DevNoteDialog { open = null }
        null -> {}
    }
}

@Composable
private fun BannerText(title: String, line: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

/** An ad's image, decoded off the main thread at most [maxSide] pixels; a plain box while missing. */
@Composable
private fun AdImage(bytes: ByteArray?, maxSide: Int, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val image by produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let { withContext(Dispatchers.Default) { decodeScaled(it, maxSide) } }
    }
    val decoded = image
    if (decoded != null) {
        Image(decoded, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** An ad: its image, text and link, who paid for it; the link opens in the browser only on a tap. */
@Composable
private fun AdDialog(ad: ChainAd, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = adLink(ad.content.url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ad.content.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ad.content.media != null) {
                    AdImage(
                        ad.content.media, maxSide = 1080,
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (ad.content.desc.isNotEmpty()) Text(ad.content.desc)
                link?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "An Idena ad: ${ad.author.take(8)}…${ad.author.takeLast(4)} burnt ${idna(ad.burnt)} over the last day " +
                        "to show it, and the oracles approved it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (link != null) TextButton(onClick = { openInBrowser(context, link); onDismiss() }) { Text("Open the link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

const val DEV_NOTE_TITLE = "A note from the devs"
const val DEV_NOTE_LINE = "You run an optimised, more secure Idena node. Tap to read."

/** The code behind the app, as GitHub repositories and what each one is. */
private val REPOSITORIES = listOf(
    "ubiubi18/idena-go" to "the node",
    "truongfelix/idena-mobile" to "this app",
    "N3CR0M4NC3R-dev/idena.social" to "the social network",
)

/** The developers' note: what this node is, where its code is, and the vote asked for. */
@Composable
private fun DevNoteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DEV_NOTE_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "You are using an optimised, more secure version of the Idena node. The official idena-go is no " +
                        "longer maintained: this one is.",
                )
                Text("Like the project? Read the code:")
                REPOSITORIES.forEach { (repository, what) ->
                    Column(Modifier.fillMaxWidth().clickable { openInBrowser(context, "https://github.com/$repository") }) {
                        Text(repository, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Want to help Idena? Vote for this version at the next hard fork.", fontWeight = FontWeight.Bold)
                Text(
                    "Your node casts the vote while it mines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Opens [url] in the browser; nothing happens if the phone has no browser. */
private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
