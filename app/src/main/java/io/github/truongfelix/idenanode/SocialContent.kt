package io.github.truongfelix.idenanode

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/*
 * What a post shows: its text and first media, as idena.social stores them (getMessage and getMedia in
 * idena.social-ui's asyncUtils.ts): in the transaction, or on IPFS as "ipfs://<cid>". IPFS content comes
 * only through the phone's own node ([IpfsStore]); nothing is asked of a public gateway.
 */

/** Where IPFS content stands: being fetched, fetched, gone from IPFS, or the node not answering. */
internal sealed interface Fetched {
    data object Loading : Fetched
    class Loaded(val bytes: ByteArray) : Fetched
    data object Unavailable : Fetched
    class Failed(val reason: String) : Fetched
}

/** Decoded images, kept while the app runs (about 24 MB), so that scrolling back does not decode again. */
internal val images = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** The content of [cid]: from the phone's files, else through the node; and how to try again. */
@Composable
internal fun rememberIpfs(cid: String): Pair<Fetched, () -> Unit> {
    val context = LocalContext.current
    var attempt by remember(cid) { mutableIntStateOf(0) }
    val state by produceState<Fetched>(Fetched.Loading, cid, attempt) {
        value = withContext(Dispatchers.IO) {
            IpfsStore.cached(context, cid)?.let { return@withContext Fetched.Loaded(it) }
            if (IpfsStore.isUnavailable(context, cid)) return@withContext Fetched.Unavailable
            try {
                IpfsStore.fetch(context, cid)?.let { Fetched.Loaded(it) } ?: Fetched.Unavailable
            } catch (e: Exception) {
                Fetched.Failed(e.message ?: "no answer")
            }
        }
    }
    return state to {
        IpfsStore.retry(context, cid)
        attempt++
    }
}

/** What is hidden ([Hiding]) and the identity's address ([me]): a text hidden shows folded, but never [me]'s own. */
class HiddenContent(val hiding: Hiding, val me: String?)

val LocalHiding = compositionLocalOf { HiddenContent(Hiding.None, null) }

/**
 * A post's text and first media; [showMedia] false shows a short mention instead of the media. [selectable] lets a
 * long press select the text to copy it: only where the whole text shows (the thread), since a tap on a preview
 * opens the thread. A text hidden ([LocalHiding]: by the user, or with a hidden word) shows as one line instead, a tap shows it.
 */
@Composable
fun PostContent(
    call: PostCall,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    showMedia: Boolean = true,
    mediaMaxHeight: Dp = 360.dp,
    selectable: Boolean = false,
) {
    val hidden = LocalHiding.current
    var shown by remember(call.hash) { mutableStateOf(false) }
    if (!shown && call.author != hidden.me && call.hash.lowercase() in hidden.hiding.posts) {
        HiddenNote(byUser = true) { shown = true }
        return
    }
    val hides = { text: String -> !shown && call.author != hidden.me && hidden.hiding.words.matches(text) }
    // A text on IPFS is checked once fetched.
    var ipfsHidden by remember(call.hash) { mutableStateOf(false) }
    val cid = ipfsCid(call.message)
    when {
        cid != null -> IpfsText(cid, style, maxLines, selectable, hides, { ipfsHidden = it }) { shown = true }
        call.message.startsWith("ipfs://") -> Note("Text on IPFS with an invalid address.")
        hides(call.message) -> {
            HiddenNote(byUser = false) { shown = true }
            return
        }
        call.message.isNotEmpty() -> PostText(call.message, style, maxLines, selectable)
    }
    if (call.hasMedia && !(ipfsHidden && !shown)) {
        if (showMedia) PostMedia(call, mediaMaxHeight) else Note("🖼 Media")
    }
}

@Composable
private fun PostText(text: String, style: TextStyle, maxLines: Int, selectable: Boolean) {
    if (selectable) SelectionContainer { Text(text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
    else Text(text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun IpfsText(
    cid: String,
    style: TextStyle,
    maxLines: Int,
    selectable: Boolean,
    hides: (String) -> Boolean,
    onHidden: (Boolean) -> Unit,
    onShow: () -> Unit,
) {
    val (state, retry) = rememberIpfs(cid)
    when (state) {
        Fetched.Loading -> Note("Loading the text from IPFS through the node…")
        is Fetched.Loaded -> {
            val text = remember(state) { String(state.bytes, Charsets.UTF_8) }
            val hidden = hides(text)
            SideEffect { onHidden(hidden) }
            if (hidden) HiddenNote(byUser = false, onShow) else PostText(text, style, maxLines, selectable)
        }
        Fetched.Unavailable -> Unavailable(retry)
        is Fetched.Failed -> Note("The node did not answer: ${state.reason}")
    }
}

/** The first media: an image (inline or on IPFS); video, audio and SVG are only mentioned. */
@Composable
private fun PostMedia(call: PostCall, maxHeight: Dp) {
    val type = call.mediaType.lowercase()
    val cid = ipfsCid(call.media)
    when {
        call.media.isEmpty() -> Note("🖼 Media too large to keep on the phone.")
        type.startsWith("video/") || type.startsWith("audio/") -> Note("🎞 Video or audio, not shown here.")
        type == "image/svg+xml" -> Note("🖼 SVG image, not shown here.")
        cid != null -> {
            val (state, retry) = rememberIpfs(cid)
            when (state) {
                Fetched.Loading -> Note("Loading the image from IPFS through the node…")
                is Fetched.Loaded -> PostImage(cid, state.bytes, maxHeight)
                Fetched.Unavailable -> Unavailable(retry)
                is Fetched.Failed -> Note("The node did not answer: ${state.reason}")
            }
        }
        call.media.startsWith("ipfs://") -> Note("Media on IPFS with an invalid address.")
        else -> {
            val bytes = remember(call.hash) { runCatching { Base64.getDecoder().decode(call.media) }.getOrNull() }
            if (bytes == null) Note("🖼 Unreadable image.") else PostImage(call.hash, bytes, maxHeight)
        }
    }
}

/** An image decoded off the main thread, at most 1,080 pixels on its longer side; a tap shows it full screen. */
@Composable
private fun PostImage(key: String, bytes: ByteArray, maxHeight: Dp) {
    val decoded by produceState<Pair<Boolean, ImageBitmap?>>(false to images.get(key), key) {
        if (value.second == null) {
            val bitmap = withContext(Dispatchers.Default) { decodeScaled(bytes) }
            bitmap?.let { images.put(key, it) }
            value = true to bitmap
        }
    }
    val bitmap = decoded.second
    var open by remember(key) { mutableStateOf(false) }
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = "Image of the post",
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(8.dp)).clickable { open = true },
            contentScale = ContentScale.Fit,
        )
        decoded.first -> Note("🖼 An image this phone cannot show.")
        else -> Note("Decoding the image…")
    }
    if (open && bitmap != null) ImageViewer(key, bitmap, bytes) { open = false }
}

/**
 * [bytes] as an image at most [maxSide] pixels on its longer side (the phone has little memory), or null
 * when Android cannot read it.
 */
internal fun decodeScaled(bytes: ByteArray, maxSide: Int = 1080): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/**
 * A private message's text: as it is, or on IPFS ("ipfs://<cid>"), fetched through the node and opened with its key
 * ([password], [decryptIpfsText]) when the website stored it encrypted.
 */
@Composable
fun MessageText(text: String, password: String, style: TextStyle) {
    val cid = ipfsCid(text)
    if (cid == null) {
        if (text.startsWith("ipfs://")) Note("Text on IPFS with an invalid address.") else SelectionContainer { Text(text, style = style) }
        return
    }
    val (state, retry) = rememberIpfs(cid)
    when (state) {
        Fetched.Loading -> Note("Loading the text from IPFS through the node…")
        is Fetched.Loaded -> {
            val opened = remember(state, password) { if (password.isEmpty()) String(state.bytes, Charsets.UTF_8) else decryptIpfsText(state.bytes, password) }
            if (opened == null) Note("🔒 This text on IPFS does not open with its key.") else SelectionContainer { Text(opened, style = style) }
        }
        Fetched.Unavailable -> Unavailable(retry)
        is Fetched.Failed -> Note("The node did not answer: ${state.reason}")
    }
}

@Composable
private fun Unavailable(retry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Note("No longer available on IPFS: no node has it.")
        AssistChip(onClick = retry, label = { Text("Retry") })
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The line in place of a text hidden ([byUser]: by the user, else for a hidden word); a tap shows the text. */
@Composable
private fun HiddenNote(byUser: Boolean, onShow: () -> Unit) {
    Text(
        if (byUser) "Hidden by you. Tap to show." else "Hidden: a word of your list. Tap to show.",
        Modifier.clickable(onClick = onShow),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
