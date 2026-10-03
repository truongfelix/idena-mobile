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
import androidx.compose.material3.HorizontalDivider
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
 * The banner at the bottom of the Node tab: the approved Idena ads for this identity in turn, and the
 * developers' note. A tap opens the item.
 */
@Composable
fun AdBanner(ads: List<ChainAd>, modifier: Modifier = Modifier) {
    val items = remember(ads) { bannerItems(ads) }
    var index by remember(items) { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<BannerItem?>(null) }
    LaunchedEffect(items) {
        while (items.size > 1) {
            delay(ROTATION_MS)
            index = (index + 1) % items.size
        }
    }
    Surface(modifier.fillMaxWidth().height(AD_BANNER_HEIGHT), color = MaterialTheme.colorScheme.surfaceVariant) {
        Crossfade(items[index % items.size], label = "ad") { item -> BannerRow(item) { open = item } }
    }
    ItemDialog(open) { open = null }
}

/**
 * The ad shown in the Social feed after each [POSTS_PER_AD] posts: the ads and the developers' note in
 * turn, [number] being the ad's place in the feed (0 the first). A tap opens it.
 */
@Composable
fun FeedAd(ads: List<ChainAd>, number: Int, modifier: Modifier = Modifier) {
    val items = remember(ads) { bannerItems(ads) }
    val item = items[number % items.size]
    var open by remember { mutableStateOf(false) }
    Surface(
        modifier.fillMaxWidth().height(AD_BANNER_HEIGHT).clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) { BannerRow(item) { open = true } }
    if (open) ItemDialog(item) { open = false }
}

private fun bannerItems(ads: List<ChainAd>): List<BannerItem> = ads.map { BannerItem.Ad(it) } + BannerItem.DevNote

/** One item of the banner on one line: its image, title and line, and what it is. */
@Composable
private fun BannerRow(item: BannerItem, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp),
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
                // The ads' place: anyone can publish an ad here.
                Label("Ads")
            }
        }
    }
}

@Composable
private fun ItemDialog(item: BannerItem?, onDismiss: () -> Unit) {
    when (item) {
        is BannerItem.Ad -> AdDialog(item.ad, onDismiss)
        BannerItem.DevNote -> DevNoteDialog(onDismiss)
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

/** The package this app belongs to, as GitHub repositories and what each one is. */
private val PACKAGE = listOf(
    "truongfelix/idena-go" to "the node: optimised, more secure",
    "truongfelix/idena-desktop" to "the desktop app",
    "truongfelix/idena-mobile" to "this app",
)

/** The original Idena code, which the package builds on. */
private val LEGACY = "idena-network" to "the original Idena code (legacy, no longer maintained)"

/** Contributors, direct and indirect, and what of theirs the package builds on. */
private val CONTRIBUTORS = listOf(
    "ubiubi18" to "the idena-go and idena-desktop forks this package started from, and reviews of its pull requests",
    "N3CR0M4NC3R-dev/idena.social" to "idena.social, the social network of the Social tab",
)

/**
 * The developers' note: a community package, not the original team's; who makes it and its repositories,
 * the original code, the contributors, a personal statement on mining and reported flips, and the avatars' credits.
 */
@Composable
private fun DevNoteDialog(onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DEV_NOTE_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Community work: this package is not made by the original Idena team.",
                    fontWeight = FontWeight.Bold,
                )
                Text("This app is part of the Idena community package by truongfelix, on the same chain as the original:")
                PACKAGE.forEach { (path, what) -> GitHubLink(path, what) }
                Text("Built on the original Idena code:", style = MaterialTheme.typography.bodyMedium)
                GitHubLink(LEGACY.first, LEGACY.second)
                Text("With thanks for their direct and indirect contributions:", style = MaterialTheme.typography.bodyMedium)
                CONTRIBUTORS.forEach { (path, what) -> GitHubLink(path, what) }
                HorizontalDivider()
                Text(
                    "Today, an identity that cannot solve flips is penalised in mining, while an identity whose flips " +
                        "are reported is not. We believe this is unfair, and unstable for the future of the network.",
                )
                Text(
                    "If you think an identity that keeps making flips that get reported should be penalised, its mining " +
                        "rate reduced in proportion to its reported flips out of its total flips over the last 10 epochs, " +
                        "please speak out on Telegram, Discord and idena.social.",
                    fontWeight = FontWeight.Bold,
                )
                COMMUNITY.forEach { (name, url) -> Link(name, url) }
                HorizontalDivider()
                Text(
                    "Robot avatars: RoboHash (MIT), robots by Zikri Kader (CC-BY 4.0), drawn on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Where the community talks (idena.social is the Social tab). */
private val COMMUNITY = listOf(
    "Telegram" to "https://t.me/IdenaAnnouncements",
    "Discord" to "https://discord.com/invite/idena-community-634481767352369162",
)

/** [name] as a link to [url], with the address under it. */
@Composable
private fun Link(name: String, url: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { openInBrowser(context, url) }) {
        Text(name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A GitHub account or repository ([path]) as a link, with [what] it is under it. */
@Composable
private fun GitHubLink(path: String, what: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { openInBrowser(context, "https://github.com/$path") }) {
        Text(path, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Opens [url] in the browser; nothing happens if the phone has no browser. */
private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
