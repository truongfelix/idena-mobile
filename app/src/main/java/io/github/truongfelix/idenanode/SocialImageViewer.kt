package io.github.truongfelix.idenanode

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/*
 * A post's picture full screen: pinch to zoom, drag to move, a double tap back to the whole picture; Save puts
 * the post's own file, unchanged, in the phone's gallery (Pictures/Idena).
 */

private const val MAX_ZOOM = 6f

/** The kind of image file [bytes] holds, from its first bytes: its MIME type and file extension, or null. */
internal fun imageFileType(bytes: ByteArray): Pair<String, String>? {
    fun at(offset: Int, vararg values: Int) =
        bytes.size >= offset + values.size && values.indices.all { bytes[offset + it].toInt() and 0xff == values[it] }
    return when {
        at(0, 0xff, 0xd8, 0xff) -> "image/jpeg" to "jpg"
        at(0, 0x89, 'P'.code, 'N'.code, 'G'.code, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png" to "png"
        at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif" to "gif"
        at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) -> "image/webp" to "webp"
        at(0, 'B'.code, 'M'.code) -> "image/bmp" to "bmp"
        else -> null
    }
}

/** The gallery's file name for the picture known by [key] (its IPFS id or its post's transaction hash). */
internal fun savedImageName(key: String, extension: String): String =
    "idena-${key.removePrefix("0x").filter { it.isLetterOrDigit() }.take(16)}.$extension"

/** Saves [bytes] in Pictures/Idena; returns the file's name. Throws when the file is not an image or cannot be written. */
fun saveImageToGallery(context: Context, key: String, bytes: ByteArray): String {
    val (mime, extension) = imageFileType(bytes) ?: throw IOException("this file is not a picture the gallery takes")
    val name = savedImageName(key, extension)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Idena")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: throw IOException("the gallery refused the file")
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("the gallery file cannot be opened")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    return name
}

/** [bitmap] full screen over the app, from the post's file [bytes] (saved as they are) known by [key]. */
@Composable
fun ImageViewer(key: String, bitmap: ImageBitmap, bytes: ByteArray, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var note by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap,
                contentDescription = "Image of the post, full screen",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            // Moving only makes sense zoomed in; back at 1 the picture is centred again.
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                note?.let {
                    Text(
                        it,
                        Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { saveImageToGallery(context, key, bytes) } }
                            note = saved.fold({ "Saved in the gallery: Pictures/Idena/$it" }, { "Not saved: ${it.message}" })
                            saving = false
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Save to the gallery") }
            }
        }
    }
}
