package io.github.truongfelix.idenanode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Cells of an [IDENTICON_SIZE] x [IDENTICON_SIZE] identicon, row by row, mirrored left to right. */
const val IDENTICON_SIZE = 5

/**
 * The pattern avatar of [address], when its robot cannot be drawn: the cells to fill, taken from
 * the address's hex digits and mirrored so that the picture is symmetric, and its hue (0-359).
 */
fun identicon(address: String): Pair<BooleanArray, Int> {
    val hex = address.lowercase().removePrefix("0x").filter { it in "0123456789abcdef" }.ifEmpty { "0" }
    fun digit(i: Int) = hex[i % hex.length].digitToInt(16)
    val cells = BooleanArray(IDENTICON_SIZE * IDENTICON_SIZE)
    val half = (IDENTICON_SIZE + 1) / 2
    for (row in 0 until IDENTICON_SIZE) {
        for (column in 0 until half) {
            val on = digit(row * half + column) % 2 == 0
            cells[row * IDENTICON_SIZE + column] = on
            cells[row * IDENTICON_SIZE + IDENTICON_SIZE - 1 - column] = on
        }
    }
    // The hue from the address's last digits, which the cells do not use.
    val hue = (digit(hex.length - 1) * 16 + digit(hex.length - 2) * 16 * 16 + digit(hex.length - 3)) % 360
    return cells to hue
}

/**
 * The avatar of [address] in a rounded square of [size] (a topic's: a circle, [shape]): its Idena robot ([RobotAvatars], as
 * robohash.idena.io draws it), drawn on the phone; the [identicon] only if the robot cannot be drawn.
 */
@Composable
fun Avatar(address: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(size / 5)) {
    val context = LocalContext.current
    // Null while drawing (a few milliseconds the first time); Failed when a picture is missing.
    val robot by produceState<Any?>(RobotAvatars.cached(address), address) {
        if (value == null) value = withContext(Dispatchers.Default) { RobotAvatars.draw(context, address) } ?: Failed
    }
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        when (val drawn = robot) {
            is ImageBitmap -> Image(drawn, contentDescription = null, Modifier.fillMaxSize())
            Failed -> Identicon(address, size)
            else -> {}
        }
    }
}

private object Failed

/** The pattern avatar of [address] ([identicon]). */
@Composable
private fun Identicon(address: String, size: Dp) {
    val (cells, hue) = remember(address) { identicon(address) }
    val color = Color.hsv(hue.toFloat(), 0.55f, 0.75f)
    Canvas(Modifier.size(size)) {
        // A margin of half a cell around the picture.
        val cell = this.size.width / (IDENTICON_SIZE + 1)
        val margin = cell / 2
        for (i in cells.indices) {
            if (!cells[i]) continue
            drawRect(
                color,
                topLeft = Offset(margin + i % IDENTICON_SIZE * cell, margin + i / IDENTICON_SIZE * cell),
                size = Size(cell, cell),
            )
        }
    }
}

/** Material's "content copy" icon (two sheets), not in the core icon set. */
val CopyIcon: ImageVector = ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).addPath(
    pathData = addPathNodes(
        "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
            "c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
    ),
    fill = SolidColor(Color.Black),
).build()

/** A small round icon button ([size] high, for dense rows): [icon] tinted [tint]. */
@Composable
fun SmallIconButton(icon: ImageVector, description: String, tint: Color, size: Dp = 32.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, Modifier.size(size * 0.6f), tint = tint)
    }
}

/** A small button copying [address] in full (a check once copied). */
@Composable
fun CopyAddressButton(address: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(address) { mutableStateOf(false) }
    SmallIconButton(if (copied) Icons.Filled.Check else CopyIcon, if (copied) "Copied" else "Copy the address", tint) {
        clipboard.setText(AnnotatedString(address))
        copied = true
    }
}

/** [address] in full (on two lines on a phone), in monospace, and the copy button (a check once copied). */
@Composable
fun AddressLine(address: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(address) { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(address, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = color)
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(address))
            copied = true
        }) {
            Icon(if (copied) Icons.Filled.Check else CopyIcon, contentDescription = if (copied) "Copied" else "Copy the address", tint = color)
        }
    }
}
