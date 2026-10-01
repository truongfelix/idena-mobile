package io.github.truongfelix.idenanode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/** The color of each status: green good, orange working or to fix, red not working, grey off. */
@Composable
fun Health.color(): Color = when (this) {
    Health.Good -> Color(0xFF2E9D4A)
    Health.Warn -> Color(0xFFF08C00)
    Health.Bad -> Color(0xFFD93025)
    Health.Off -> MaterialTheme.colorScheme.outline
}

/** A status dot of [health]. */
@Composable
fun StatusDot(health: Health, size: Int = 12) {
    Box(Modifier.size(size.dp).background(health.color(), CircleShape))
}

/**
 * A one-line status: the dot of [state], [text] (its label unless given) cut to one line so that the bar
 * never changes height, and ⓘ for the details ([onInfo], also a tap on the line).
 */
@Composable
fun StatusLine(
    state: BarState,
    modifier: Modifier = Modifier,
    text: String = state.label,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onInfo: () -> Unit,
) {
    Row(modifier.clickable(onClick = onInfo), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state.health)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, contentDescription = "Details") }
    }
}

/** The details behind the node's status bar: sync, network, blocks, peers, speed, start, and the background. */
@Composable
fun NodeInfoDialog(model: NodeModel, onDismiss: () -> Unit) {
    val bar = model.bar
    val state = model.state
    val node = model.view
    val format = NumberFormat.getIntegerInstance()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(bar.health, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(bar.label)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Sync", if (state.syncEnabled) "on" else "off")
                Field("Network", state.network)
                if (node != null) {
                    Field("Peers", node.peers.toString())
                    Field(
                        "Block",
                        if (node.syncing && node.highestBlock > 0) "${format.format(node.currentBlock)} / ${format.format(node.highestBlock)}"
                        else format.format(node.currentBlock),
                    )
                    val speed = state.blocksPerSecond
                    if (node.syncing && node.highestBlock > 0 && speed != null && speed > 0) {
                        Field("Speed", "${format.format(Math.round(speed))} blocks/s")
                        Field("Time left", duration((node.highestBlock - node.currentBlock).coerceAtLeast(0) / speed))
                    }
                    if (node.wrongTime) Text("The phone's clock is wrong: fix the time for the node to work.", color = MaterialTheme.colorScheme.error)
                } else if (state.syncEnabled) {
                    Text("The node does not answer yet.")
                }
                state.runningForMs?.takeIf { state.syncEnabled }?.let { Field("Started", "${duration(it / 1000.0).removePrefix("about ")} ago") }
                state.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Green: synced with peers. Orange: starting, looking for peers (often several minutes after a start), " +
                        "syncing, or a background setting to fix. Red: no peers after ${PEER_GRACE_MS / 60_000} min, no " +
                        "answer, or a wrong clock. Grey: sync off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                BackgroundSettings(state)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * What lets the node run with the screen off, and the buttons to fix it: Android's battery optimization,
 * and on MIUI its Autostart (it refuses to restart a killed service without it) and battery saver.
 */
@Composable
fun BackgroundSettings(state: ScreenState) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Running with the screen off", style = MaterialTheme.typography.titleSmall)
        Field("Battery optimization", if (state.ignoringBatteryOptimizations) "off ✓" else "on: Android may pause the node")
        if (!state.ignoringBatteryOptimizations) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow background running") }
        }
        if (isMiui(state)) {
            Field(
                "MIUI Autostart",
                when (state.autostart) {
                    true -> "on ✓"
                    false -> "off: MIUI will not restart the node"
                    null -> "unknown"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openMiuiAutostart(context) }) { Text("Autostart") }
                OutlinedButton(onClick = { openMiuiBatterySaver(context) }) { Text("Battery saver") }
            }
            Text(
                "In MIUI's battery saver choose \"No restrictions\", and lock the app in the recent apps (pull it down) " +
                    "so that the memory cleanup at screen lock leaves it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Whether the phone runs MIUI: it tells its Autostart, or it is a Xiaomi. */
fun isMiui(state: ScreenState): Boolean = state.autostart != null || Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

private fun openMiuiAutostart(context: Context) = openOrAppSettings(
    context,
    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
)

private fun openMiuiBatterySaver(context: Context) = openOrAppSettings(
    context,
    Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString()),
)

/** Opens [intent], else the app's page in Android's settings (MIUI's pages move between versions). */
private fun openOrAppSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
