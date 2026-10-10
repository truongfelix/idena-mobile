package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The preview of an ad of the list: its draft on the phone, else its content on IPFS. */
@Composable
fun AdPreviewDialog(ad: MyAd, onClose: () -> Unit) {
    val context = LocalContext.current
    val content by produceState<AdContent?>(null, ad) {
        value = withContext(Dispatchers.IO) {
            val draft = ad.draft
            if (draft != null) {
                val store = adStore(context)
                AdContent(draft.title, draft.desc, draft.url, store.thumb(draft.id), store.media(draft.id))
            } else {
                runCatching { (IpfsStore.cached(context, ad.cid) ?: IpfsStore.fetch(context, ad.cid))?.let(::decodeAd) }.getOrNull()
                    ?: AdContent(ad.title, ad.desc, "", ad.thumb, null)
            }
        }
    }
    val shown = content
    if (shown == null) {
        AlertDialog(onDismissRequest = onClose, confirmButton = {}, text = { CircularProgressIndicator() })
    } else {
        AdContentPreview(shown, onClose)
    }
}

/** How [content] shows: in the banner, then opened (its picture, text and link). */
@Composable
fun AdContentPreview(content: AdContent, onClose: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Preview") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("In the banner:", style = small)
                Surface(
                    Modifier.fillMaxWidth().height(AD_BANNER_HEIGHT).clip(RoundedCornerShape(12.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AdImage(content.thumb, maxSide = 160, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                        BannerText(content.title.ifBlank { "Title" }, content.desc.ifBlank { "Description" }, Modifier.weight(1f))
                        Label("Ad")
                    }
                }
                Text("Opened:", style = small)
                if (content.media != null) {
                    AdImage(content.media, maxSide = 1080, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Fit)
                }
                Text(content.title.ifBlank { "Title" }, style = MaterialTheme.typography.titleMedium)
                if (content.desc.isNotEmpty()) Text(content.desc)
                if (content.url.isNotEmpty()) Text(content.url, fontFamily = FontFamily.Monospace, style = small)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
