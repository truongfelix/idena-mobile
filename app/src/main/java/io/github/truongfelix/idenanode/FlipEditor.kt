package io.github.truongfelix.idenanode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * A flip picture opened full screen (B-204): drag to move it in its frame, pinch to zoom, a quarter turn, its
 * proportions kept or not, and its brightness (B-200). What the screen shows is drawn with the same map
 * ([frameTransform]) and colour filter as the frame made from it ([editedFrame]).
 */

/** The picture a frame is made from: upright, its longer side at most [ORIGINAL_MAX_SIDE], JPEG. */
fun originalJpeg(bytes: ByteArray): ByteArray {
    val source = decodeUpright(bytes, ORIGINAL_MAX_SIDE) ?: throw IOException(t(R.string.flip_android_cannot_read))
    val out = ByteArrayOutputStream()
    scaled(source, ORIGINAL_MAX_SIDE).compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

/** The paint that draws a picture at [brightness] (a factor on each colour, as the desktop's brightness filter). */
private fun framePaint(brightness: Float): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
    if (brightness != 1f) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
}

/** Draws [source] into [canvas] ([width] x [height]) as [edit] places it, with [paint] ([framePaint] of its brightness). */
private fun drawFramed(canvas: Canvas, source: Bitmap, width: Float, height: Float, edit: FrameEdit, paint: Paint, matrix: Matrix = Matrix()) {
    matrix.setValues(frameTransform(source.width, source.height, width, height, edit).matrixValues())
    canvas.drawBitmap(source, matrix, paint)
}

/** The frame made from [original] with [edit]: [WORK_WIDTH] x [WORK_HEIGHT], JPEG. */
fun editedFrame(original: ByteArray, edit: FrameEdit): ByteArray {
    val source = BitmapFactory.decodeByteArray(original, 0, original.size) ?: throw IOException(t(R.string.flip_android_cannot_read))
    val out = Bitmap.createBitmap(WORK_WIDTH, WORK_HEIGHT, Bitmap.Config.ARGB_8888)
    drawFramed(Canvas(out), source, WORK_WIDTH.toFloat(), WORK_HEIGHT.toFloat(), edit, framePaint(edit.normalized().brightness))
    // As [jpeg] encodes a frame.
    return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/**
 * Picture [number] of the flip, from [original], full screen to place in its frame; [onDone] gets the new [FrameEdit],
 * [onClose] leaves it as it was.
 */
@Composable
fun FrameEditor(number: Int, original: ByteArray, start: FrameEdit, onClose: () -> Unit, onDone: (FrameEdit) -> Unit) {
    val source by produceState<Bitmap?>(null, original) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(original, 0, original.size) }
    }
    var edit by remember { mutableStateOf(start.normalized()) }
    // The preview's paint and matrix, made again only when the brightness changes.
    val paint = remember(edit.brightness) { framePaint(edit.brightness) }
    val matrix = remember { Matrix() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text(t(R.string.common_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Text(t(R.string.flip_editor_title, number), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onDone(edit.normalized()) }) { Text(t(R.string.flip_editor_done)) }
                }
                val bitmap = source
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(WORK_WIDTH.toFloat() / WORK_HEIGHT)
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(bitmap) {
                            if (bitmap == null) return@pointerInput
                            detectTransformGestures { _, pan, zoom, _ ->
                                val e = edit.copy(zoom = edit.zoom * zoom).normalized()
                                // A drag moves the picture with the finger: the pan is the share of the room it has.
                                val (roomX, roomY) = frameRoom(bitmap.width, bitmap.height, size.width.toFloat(), size.height.toFloat(), e)
                                edit = e.copy(
                                    panX = if (roomX > 0f) e.panX + pan.x / roomX else 0f,
                                    panY = if (roomY > 0f) e.panY + pan.y / roomY else 0f,
                                ).normalized()
                            }
                        }
                        .drawBehind {
                            bitmap?.let { b -> drawIntoCanvas { drawFramed(it.nativeCanvas, b, size.width, size.height, edit, paint, matrix) } }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap == null) CircularProgressIndicator()
                }
                Text(t(R.string.flip_editor_help), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edit = edit.copy(turns = edit.turns + 1, panX = 0f, panY = 0f).normalized() }) {
                        Text(t(R.string.flip_editor_turn))
                    }
                    OutlinedButton(onClick = { edit = FrameEdit() }) { Text(t(R.string.flip_editor_reset)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t(R.string.flip_editor_keep_proportions), style = MaterialTheme.typography.titleSmall)
                        Text(
                            t(if (edit.keepProportions) R.string.flip_editor_proportions_kept else R.string.flip_editor_proportions_stretched),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = edit.keepProportions, onCheckedChange = { edit = edit.copy(keepProportions = it).normalized() })
                }
                Text(t(R.string.flip_editor_brightness, (edit.brightness * 100 + 0.5f).toInt()), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = edit.brightness,
                    onValueChange = { edit = edit.copy(brightness = it).normalized() },
                    valueRange = MIN_FRAME_BRIGHTNESS..MAX_FRAME_BRIGHTNESS,
                )
            }
        }
    }
}

/** A flip frame full screen (B-164): zoom and move it; Close or back closes it. */
@Composable
fun FrameViewer(bytes: ByteArray, onClose: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    bitmap?.let { ImageViewer(key = null, bitmap = it.asImageBitmap(), bytes = null, onClose = onClose) }
}
