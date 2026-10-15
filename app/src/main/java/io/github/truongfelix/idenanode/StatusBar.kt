package io.github.truongfelix.idenanode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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

/** What each dot color means, one row per color. */
@Composable
fun DotLegend(vararg rows: Pair<Health, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((health, text) in rows) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp)) { StatusDot(health, size = 10) }
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                    val step = node.syncStep(model.chainMoving)
                    val chain = node.chainBlock ?: node.currentBlock
                    val syncing = node.syncing && node.highestBlock > 0
                    Field("Block", if (syncing) "${format.format(chain)} / ${format.format(step.total)}" else format.format(chain))
                    // A fast sync's headers above the chain.
                    if (syncing && node.currentBlock > chain) Field("Headers", "${format.format(node.currentBlock)} / ${format.format(step.total)}")
                    node.headTime?.let { Field("Last block", timeAgo(it, System.currentTimeMillis() / 1000).let { ago -> if (ago == "now") ago else "$ago ago" }) }
                    val speed = model.stepSpeed(step)
                    if (syncing && step.phase != SyncPhase.Snapshot && speed != null && speed > 0) {
                        Field("Speed", "${format.format(Math.round(speed))} ${if (step.phase == SyncPhase.Headers) "headers" else "blocks"}/s")
                        Field("Time left", duration(step.remaining / speed))
                    }
                    if (node.wrongTime) Text("The phone's clock is wrong: fix the time for the node to work.", color = MaterialTheme.colorScheme.error)
                } else if (state.syncEnabled) {
                    Text("The node does not answer yet.")
                }
                state.runningForMs?.takeIf { state.syncEnabled }?.let { Field("Started", "${duration(it / 1000.0).removePrefix("about ")} ago") }
                state.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                DotLegend(
                    Health.Good to "Synced with peers",
                    Health.Warn to "Starting, looking for peers (often several minutes after a start), syncing, or a background setting to fix",
                    Health.Bad to "No peers after ${PEER_GRACE_MS / 60_000} min, no answer, or a wrong clock",
                    Health.Off to "Sync off",
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
 * then the autostart and battery pages that some phones add (they may refuse to restart a stopped node).
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
        Text(
            "Some phones also close apps in the background: allow this app to start automatically and to run " +
                "without battery restrictions, and lock it in the recent apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openFirstOrAppSettings(context, autostartPages()) }) { Text("Autostart") }
            OutlinedButton(onClick = { openFirstOrAppSettings(context, batteryPages(context)) }) { Text("Battery saver") }
        }
    }
}

/** The autostart pages phone makers add to Android, tried in turn. */
private fun autostartPages(): List<Intent> = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
).map { (pkg, activity) -> Intent().setComponent(ComponentName(pkg, activity)) }

/** The per-app battery pages phone makers add to Android, tried in turn. */
private fun batteryPages(context: Context): List<Intent> = listOf(
    Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString()),
)

/**
 * Opens the first of [pages] the phone has, else the app's page in Android's settings (where Android 12
 * and later have the app's battery use). The makers' pages move between versions: each is tried.
 */
private fun openFirstOrAppSettings(context: Context, pages: List<Intent>) {
    for (page in pages) {
        try {
            context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Not on this phone, or not open to other apps: try the next one.
        }
    }
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
