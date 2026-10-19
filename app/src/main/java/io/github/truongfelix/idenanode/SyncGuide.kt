package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * The way to Idena Social for a newcomer (B-128), shown where Social has nothing to show yet: the account, the
 * connection to the network, the download of its state, the last blocks, each with its progress, and what may hold
 * a step up, explained in plain words with what the person can do. It explains; it never acts for the person.
 */

/** Where a newcomer is on the way to Idena Social, in order. */
enum class GuideStep { Account, Start, Peers, Network, Blocks, Reading }

/** The row of the guide a step belongs to: account, connecting, downloading, catching up; 4 once all are done. */
val GuideStep.row: Int
    get() = when (this) {
        GuideStep.Account -> 0
        GuideStep.Start, GuideStep.Peers -> 1
        GuideStep.Network -> 2
        GuideStep.Blocks -> 3
        GuideStep.Reading -> 4
    }

/** An explanation the guide adds to its step. */
enum class GuideHint {
    /** No peer yet, after [GUIDE_PEERS_WAIT_MS], on Wi-Fi: a first node may take minutes to accept the phone. */
    PeersTakeTime,

    /** No peer yet, after [GUIDE_PEERS_WAIT_MS], not on Wi-Fi: other nodes often cannot reach a phone on mobile data. */
    WifiHelps,

    /** Waiting for the snapshot: it goes on by itself once a peer offers it. */
    SnapshotWaits,

    /** Waiting for the snapshot for over [GUIDE_SNAPSHOT_LONG_MS]: the peers do not have it yet; more peers help. */
    SnapshotLong,

    /** The node stops while the phone sleeps or Android stops the app, and catches up when it runs again. */
    CatchesUp,

    /** Android may stop the node with the screen off: the settings that keep it running. */
    Background,
}

/** What the guide reads from the node and the phone. */
data class GuideInput(
    val hasKey: Boolean,
    val syncEnabled: Boolean,
    val startError: String?,
    /** The node; null when it does not answer. */
    val node: NodeView?,
    val runningForMs: Long?,
    val chainMoving: Boolean,
    /** Blocks (or headers) per second of the step; null until measured. */
    val speed: Double?,
    val startPhase: StartPhase?,
    val onWifi: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    /** A Xiaomi phone: MIUI stops apps after the screen locks unless Autostart is on. */
    val xiaomi: Boolean,
    /** The phone's clock (seconds), to tell an old head block; null leaves it out. */
    val nowSeconds: Long? = null,
)

/** The guide's step, its progress line (null for none) and its explanations. */
data class SyncGuide(val step: GuideStep, val line: String?, val hints: List<GuideHint>)

/** No peer for this long after a start: the guide explains why it may take a while. */
const val GUIDE_PEERS_WAIT_MS = 2 * 60_000L

/** Waiting for the snapshot this long: the guide says the peers do not have it yet. */
const val GUIDE_SNAPSHOT_LONG_MS = 15 * 60_000L

/** The step [input] is at. */
fun guideStep(input: GuideInput): GuideStep {
    val node = input.node
    return when {
        !input.hasKey -> GuideStep.Account
        input.startError != null || !input.syncEnabled -> GuideStep.Start
        node == null || node.peers == 0 -> GuideStep.Peers
        node.syncing -> if (node.syncStep(input.chainMoving).phase == SyncPhase.Blocks) GuideStep.Blocks else GuideStep.Network
        input.nowSeconds != null && headIsStale(node.headTime, input.nowSeconds) -> GuideStep.Blocks
        else -> GuideStep.Reading
    }
}

/** The guide for [input], its step reached [stepForMs] ago. */
fun syncGuide(input: GuideInput, stepForMs: Long): SyncGuide {
    val step = guideStep(input)
    val node = input.node
    val sync = node?.takeIf { it.syncing && it.highestBlock > 0 }?.syncStep(input.chainMoving)
    val line = when (step) {
        GuideStep.Account, GuideStep.Reading -> null
        GuideStep.Start -> input.startError?.let { t(R.string.guide_could_not_start, it) } ?: t(R.string.guide_sync_off)
        GuideStep.Peers -> when {
            node == null -> input.startPhase?.let { startLabel(it, input.runningForMs) } ?: StartPhase.Starting.label
            else -> input.runningForMs?.let { t(R.string.status_looking_for_peers_minutes, it / 60_000) } ?: t(R.string.status_looking_for_peers)
        }
        GuideStep.Network, GuideStep.Blocks -> sync?.let { syncLabel(it, input.speed) } ?: t(R.string.status_behind)
    }
    val hints = buildList {
        val waited = (input.runningForMs ?: 0) >= GUIDE_PEERS_WAIT_MS
        if (step == GuideStep.Peers && node != null && waited) add(if (input.onWifi) GuideHint.PeersTakeTime else GuideHint.WifiHelps)
        if (step == GuideStep.Network && sync?.phase == SyncPhase.Snapshot) {
            add(GuideHint.SnapshotWaits)
            if (stepForMs >= GUIDE_SNAPSHOT_LONG_MS) add(GuideHint.SnapshotLong)
        }
        if (step == GuideStep.Blocks) add(GuideHint.CatchesUp)
        val running = step == GuideStep.Peers || step == GuideStep.Network || step == GuideStep.Blocks
        if (running && (!input.ignoringBatteryOptimizations || input.xiaomi)) add(GuideHint.Background)
    }
    return SyncGuide(step, line, hints)
}

/** The text of [hint]; [stepMinutes]: how long the step has lasted. */
fun GuideHint.text(stepMinutes: Long): String = when (this) {
    GuideHint.PeersTakeTime -> t(R.string.guide_peers_take_time)
    GuideHint.WifiHelps -> t(R.string.guide_wifi_helps)
    GuideHint.SnapshotWaits -> t(R.string.guide_snapshot_waits)
    GuideHint.SnapshotLong -> t(R.string.guide_snapshot_long, stepMinutes)
    GuideHint.CatchesUp -> t(R.string.guide_catches_up)
    GuideHint.Background -> t(R.string.guide_background)
}

/**
 * The guide as a card: its four rows (done ✓, the current one with its progress, the next ones), the explanations
 * of the current step, and the way to act: the node panel for the account and the Sync switch, the background
 * settings ([BackgroundSettings]) when Android may stop the node.
 */
@Composable
fun SyncGuideCard(guide: SyncGuide, state: ScreenState, stepMinutes: Long, onOpenPanel: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t(R.string.guide_title), style = MaterialTheme.typography.titleMedium)
        Text(t(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium, color = muted)
        val rows = listOf(R.string.guide_row_account, R.string.guide_row_connect, R.string.guide_row_download, R.string.guide_row_blocks)
        rows.forEachIndexed { i, row ->
            val current = i == guide.step.row
            Row {
                Text(
                    when {
                        i < guide.step.row -> "✓"
                        current -> "●"
                        else -> "○"
                    },
                    Modifier.width(24.dp),
                    color = if (i < guide.step.row) Health.Good.color() else if (current) Health.Warn.color() else muted,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t(row), style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, color = if (i > guide.step.row) muted else MaterialTheme.colorScheme.onSurface)
                    if (current) guide.line?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (current && i == 2) Text(t(R.string.guide_download_why), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        when (guide.step) {
            GuideStep.Account -> {
                Text(t(R.string.guide_account), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenPanel) { Text(t(R.string.guide_open_panel)) }
            }
            GuideStep.Start -> {
                Text(t(R.string.guide_start), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenPanel) { Text(t(R.string.guide_open_panel)) }
            }
            GuideStep.Reading -> Text(t(R.string.guide_reading), style = MaterialTheme.typography.bodyMedium)
            else -> Unit
        }
        guide.hints.filter { it != GuideHint.Background }.forEach { Text(it.text(stepMinutes), style = MaterialTheme.typography.bodyMedium) }
        if (GuideHint.Background in guide.hints) {
            Text(GuideHint.Background.text(stepMinutes), style = MaterialTheme.typography.bodyMedium)
            BackgroundSettings(state)
        }
    }
}
