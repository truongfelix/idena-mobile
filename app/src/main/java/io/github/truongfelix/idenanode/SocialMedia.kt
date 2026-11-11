package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/*
 * A post's moving picture (GIF, animated WebP), video and sound (B-222). A moving picture is decoded by Android's
 * ImageDecoder, at most 1,080 pixels on its longer side; a video or a sound is played by Android's MediaPlayer, which
 * reads the file in the system's media process, only after a tap (nothing plays by itself), with Play / Pause and a
 * bar to move in it.
 */

/** [bytes] as a moving picture at most [maxSide] pixels on its longer side; null when it is still or unreadable. */
internal fun decodeAnimation(bytes: ByteArray, maxSide: Int = 1080): AnimatedImageDrawable? = runCatching {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
        val longer = maxOf(info.size.width, info.size.height)
        if (longer > maxSide) {
            val factor = maxSide.toFloat() / longer
            decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
        }
    } as? AnimatedImageDrawable
}.getOrNull()

/** Whether [bytes] (a GIF or a WebP) moves: more than one frame. */
internal fun isAnimation(bytes: ByteArray): Boolean = decodeAnimation(bytes, 64) != null

/** A decoded moving picture, running while it is on screen. */
@Composable
internal fun AnimationView(drawable: Drawable, modifier: Modifier, description: String) {
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true } },
        update = { it.setImageDrawable(drawable); it.contentDescription = description },
        modifier = modifier,
    )
}

/** [bytes] decoded off the main thread as a moving picture, or [fallback] while it decodes or when it cannot. */
@Composable
internal fun AnimatedImage(key: String, bytes: ByteArray, maxHeight: Dp, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val drawable by produceState<AnimatedImageDrawable?>(null, key) {
        value = withContext(Dispatchers.Default) { decodeAnimation(bytes) }
    }
    val current = drawable
    if (current == null) fallback()
    else AnimationView(
        current,
        modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(8.dp)),
        t(R.string.social_image_description),
    )
}

/** The file holding [bytes] for the player, in the app's cache, named by [key] (an IPFS id or a transaction hash). */
internal fun mediaFile(context: Context, key: String, bytes: ByteArray): File {
    val dir = File(context.cacheDir, "social-media").also { it.mkdirs() }
    val file = File(dir, key.filter { it.isLetterOrDigit() }.take(80).ifEmpty { "media" })
    if (!file.exists() || file.length() != bytes.size.toLong()) {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }
    return file
}

/** [ms] as minutes and seconds ("1:05"). */
internal fun playTime(ms: Int): String {
    val seconds = maxOf(0, ms) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * A video ([video]) or a sound from [file]: Play / Pause, the time, a bar to move in it; a video shows its picture
 * above, at its own shape, at most [maxHeight] high. The player is made on the first tap and freed when it leaves
 * the screen; a file Android cannot play says so.
 */
@Composable
internal fun MediaPlayerView(file: File, video: Boolean, maxHeight: Dp) {
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember(file) { mutableStateOf<Surface?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableIntStateOf(0) }
    var ratio by remember(file) { mutableFloatStateOf(16f / 9f) }
    var failed by remember(file) { mutableStateOf(false) }
    DisposableEffect(file) {
        onDispose {
            player?.release()
            player = null
        }
    }
    // While it plays, the bar follows.
    LaunchedEffect(player, playing) {
        while (playing) {
            player?.let { position = runCatching { it.currentPosition }.getOrDefault(position) }
            delay(250)
        }
    }
    fun start() {
        val made = player ?: runCatching {
            MediaPlayer().apply {
                FileInputStream(file).use { setDataSource(it.fd) }
                surface?.let { setSurface(it) }
                setOnCompletionListener { playing = false; position = 0 }
                setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
                setOnVideoSizeChangedListener { _, width, height -> if (width > 0 && height > 0) ratio = width.toFloat() / height }
                prepare()
                duration = this.duration
            }
        }.getOrElse { failed = true; null } ?: return
        player = made
        made.start()
        playing = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (video) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                Surface(texture).also { surface = it; player?.setSurface(it) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                player?.setSurface(null)
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        if (failed) {
            Text(t(R.string.social_media_cannot_play), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val current = player
                if (playing && current != null) {
                    current.pause()
                    playing = false
                } else start()
            }) { Text(if (playing) t(R.string.social_media_pause) else if (video) t(R.string.social_media_play_video) else t(R.string.social_media_play_audio)) }
            Text(
                if (duration > 0) "${playTime(position)} / ${playTime(duration)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (duration > 0) {
            Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { position = it.toInt() },
                onValueChangeFinished = { player?.seekTo(position) },
                valueRange = 0f..duration.toFloat(),
            )
        }
    }
}

/** The file of a post's video or sound for [MediaPlayerView]: its IPFS file once fetched, or the inline bytes'. */
@Composable
internal fun rememberMediaFile(key: String, bytes: ByteArray): File? {
    val context = LocalContext.current
    val file by produceState<File?>(null, key) {
        value = withContext(Dispatchers.IO) { runCatching { mediaFile(context, key, bytes) }.getOrNull() }
    }
    return file
}
