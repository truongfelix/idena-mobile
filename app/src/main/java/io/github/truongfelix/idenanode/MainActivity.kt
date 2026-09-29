package io.github.truongfelix.idenanode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.truongfelix.idenanode.go.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    NodeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

/** The app's colors: light or dark as the phone's setting (the themes keep MIUI's forced dark off). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

/** What the screen shows, refreshed every few seconds while it is visible. */
private data class ScreenState(
    val syncEnabled: Boolean = false,
    val hasKey: Boolean = false,
    val keyAddress: String? = null,
    val status: NodeStatus? = null,
    val startError: String? = null,
    val network: String = "",
    val ignoringBatteryOptimizations: Boolean = true,
    /** Blocks per second over the last minute while syncing; null until measured. */
    val blocksPerSecond: Double? = null,
)

@Composable
private fun NodeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ScreenState()) }
    var network by remember { mutableStateOf<NetworkRewards?>(null) }
    val syncRate = remember { SyncRate() }

    suspend fun refresh() {
        val next = withContext(Dispatchers.IO) { readState(context) }
        state = next.copy(blocksPerSecond = next.status?.takeIf { it.syncing }?.let { syncRate.update(it.currentBlock) })
    }

    LaunchedEffect(Unit) {
        // An app update or a force stop ends the node without switching sync off: start it again. The
        // service ignores the start when the node already runs.
        if (NodeSettings.isSyncEnabled(context)) NodeService.start(context)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(3_000)
            }
        }
    }

    // The APY's network data reads every identity: every 10 minutes, once the node is synced.
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (state.status?.let { !it.syncing && !it.accountPending } == true) {
                    withContext(Dispatchers.IO) {
                        runCatching { NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).networkRewards() }.getOrNull()
                    }?.let { network = it }
                    delay(10 * 60_000L)
                } else {
                    delay(30_000L)
                }
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Idena Node", style = MaterialTheme.typography.headlineSmall)

        NodeCard(state) { enabled ->
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                NodeService.start(context)
            } else {
                NodeService.stop(context)
            }
            state = state.copy(syncEnabled = enabled, startError = null)
        }
        AccountCard(state, network)
        // The epoch and the flips are current once the fast sync has downloaded the state.
        state.status?.takeUnless { it.accountPending }?.let { ValidationCard(it) }
        state.status?.takeUnless { it.accountPending }?.let { MiningCard(it) { scope.launch { refresh() } } }
        if (!state.ignoringBatteryOptimizations) BackgroundCard()
        TextButton(onClick = { context.startActivity(Intent(context, CeremonyActivity::class.java)) }) {
            Text("Practice validation")
        }
        KeyCard(state) { key, password, done ->
            scope.launch {
                val error = withContext(Dispatchers.IO) {
                    Mobile.importKey(
                        NodeSettings.nodeDir(context).absolutePath,
                        NodeSettings.nodeConfig(context),
                        key.trim(),
                        password,
                    )
                }
                done(error)
                refresh()
            }
        }
    }
}

/** The sync speed over the last minute, from the blocks the screen reads every few seconds. */
private class SyncRate {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Adds the current block; returns blocks per second, or null until 10 s are measured. */
    fun update(block: Long): Double? {
        val now = SystemClock.elapsedRealtime()
        // A lower block means the node started over: the old samples no longer apply.
        if (samples.isNotEmpty() && block < samples.last().second) samples.clear()
        samples.addLast(now to block)
        while (samples.size > 2 && now - samples[1].first >= 60_000) samples.removeFirst()
        val (firstTime, firstBlock) = samples.first()
        val seconds = (now - firstTime) / 1000.0
        return if (seconds >= 10) (block - firstBlock) / seconds else null
    }
}

/** "about 1 h 30 min", "about 12 min", "under a minute". */
private fun duration(seconds: Double): String {
    val minutes = (seconds / 60).roundToLong()
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "about $minutes min"
        else -> "about ${minutes / 60} h ${"%02d".format(minutes % 60)} min"
    }
}

private fun readState(context: Context): ScreenState {
    val hasKey = NodeSettings.hasKey(context)
    val status = try {
        NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).status()
    } catch (e: Exception) {
        null
    }
    val errorFile = NodeSettings.startErrorFile(context)
    return ScreenState(
        syncEnabled = NodeSettings.isSyncEnabled(context),
        hasKey = hasKey,
        // Reading the key needs the node library; only when the node does not answer.
        keyAddress = if (status == null && hasKey) {
            Mobile.address(NodeSettings.nodeDir(context).absolutePath).ifEmpty { null }
        } else {
            null
        },
        status = status,
        startError = if (errorFile.exists()) errorFile.readText() else null,
        network = networkName(context),
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )
}

private fun networkName(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }
}

@Composable
private fun NodeCard(state: ScreenState, onSyncChange: (Boolean) -> Unit) {
    val status = state.status
    val format = NumberFormat.getIntegerInstance()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.startError != null -> "Stopped: the node could not start"
                            status != null && status.syncing -> "Running, syncing"
                            status != null -> "Running, synced"
                            state.syncEnabled -> "Starting…"
                            !state.hasKey -> "Stopped: import a key first"
                            else -> "Stopped"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Without a key the node would create a random one that the app cannot back up.
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = onSyncChange,
                    enabled = state.hasKey || state.syncEnabled,
                )
            }
            state.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Field("Network", state.network)
            if (status != null) {
                Field("Peers", status.peers.toString())
                val highest = maxOf(status.highestBlock, status.currentBlock)
                Field(
                    "Block",
                    if (status.syncing && status.highestBlock > 0) {
                        "${format.format(status.currentBlock)} / ${format.format(highest)}"
                    } else {
                        format.format(status.currentBlock)
                    },
                )
                val speed = state.blocksPerSecond
                if (status.syncing && status.highestBlock > 0 && speed != null && speed > 0) {
                    Field("Speed", "${format.format(speed.roundToLong())} blocks/s")
                    Field("Time left", duration((status.highestBlock - status.currentBlock).coerceAtLeast(0) / speed))
                }
                if (status.syncing && status.highestBlock > 0) {
                    LinearProgressIndicator(
                        progress = { status.currentBlock.toFloat() / highest.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (status.wrongTime) {
                    Text("The phone's clock is wrong: fix the time for the node to work.",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AccountCard(state: ScreenState, network: NetworkRewards?) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            val address = status?.address ?: state.keyAddress
            if (address == null) {
                Text("No key yet: import one below.")
                return@Column
            }
            SelectionContainer {
                Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (status == null) {
                Text("Switch sync on to read the account from the node.")
                return@Column
            }
            if (status.accountPending) {
                Text("Age, stake, balance: after sync")
                return@Column
            }
            Field("Identity", "${status.identityState}, age ${status.age}")
            Field("Mining", status.miningStatus)
            Field("Stake", idna(status.stake))
            val stake = status.stake.toDoubleOrNull() ?: 0.0
            val locked = status.lockedStake.toDoubleOrNull() ?: 0.0
            if (locked > 0) Field("Locked", idna(status.lockedStake))
            val miss = stakeProtection(status.identityState, status.age, stake, locked, Outcome.Miss)
            val fail = stakeProtection(status.identityState, status.age, stake, locked, Outcome.Fail)
            if (miss != null && fail != null) {
                Field("Protection", "${percent(miss)} if missed, ${percent(fail)} if failed")
            }
            network?.let { apy(stake, it) }?.let {
                Field("APY", "${percent(it.yearly)} (staking + mining)")
                Text(
                    "If the identity validates and mines itself; flip and invitation rewards are not counted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Field("Balance", idna(status.balance))
            if (status.syncing) {
                Text(
                    "Values are those of the synced block; they are current once the sync is done.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun KeyCard(state: ScreenState, onImport: (String, String, (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (state.hasKey && !open) {
        TextButton(onClick = { open = true; message = null }) { Text("Import another key") }
        message?.let { Text(it) }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key", style = MaterialTheme.typography.titleMedium)
            if (!open) {
                Text("Import the private key exported from the Idena app (Settings, Export my private key).")
                OutlinedButton(onClick = { open = true; message = null }) { Text("Import a key") }
                Text(
                    "No Idena account yet? Create one on app.idena.io, export its private key there, then " +
                        "import it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { openWebApp(context) }) { Text("Open app.idena.io") }
            } else {
                if (state.hasKey) {
                    Text("The current key is kept in a backup file.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Do not run the same key on two nodes at the same time, for example here and on a desktop " +
                        "node: both would act for the identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Exported key") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    Button(
                        enabled = !busy && key.isNotBlank() && password.isNotEmpty(),
                        onClick = {
                            busy = true
                            onImport(key, password) { error ->
                                busy = false
                                if (error.isEmpty()) {
                                    key = ""
                                    password = ""
                                    open = false
                                    message = if (state.status != null) {
                                        "Key imported. Switch sync off and on to use it."
                                    } else {
                                        "Key imported."
                                    }
                                } else {
                                    message = error
                                }
                            }
                        },
                    ) { Text("Import") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { open = false; key = ""; password = "" }) { Text("Cancel") }
                }
            }
            message?.let { Text(it) }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Running in the background", style = MaterialTheme.typography.titleMedium)
            Text("Android may pause the node when the screen is off. Allow the app to run in the background:")
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow background running") }
        }
    }
}

/** The next validation, and the flips still to make with a link to the web app to make them. */
@Composable
private fun ValidationCard(status: NodeStatus) {
    val context = LocalContext.current
    val start = parseValidationTime(status.nextValidation)
    val date = start?.let {
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
        "${format.format(it)} (${timeUntil(Instant.now(), it)})"
    } ?: status.nextValidation
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Next validation", style = MaterialTheme.typography.titleMedium)
            val answersSent = NodeSettings.answersSentFile(context, status.epoch, short = false).exists()
            Field(
                "My task",
                currentTask(status.period, status.identityState, status.requiredFlips, status.availableFlips, status.madeFlips, answersSent),
            )
            Field("Date", date)
            periodText(status.period)?.let { Field("Now", it) }
            if (status.period in listOf("FlipLottery", "ShortSession", "LongSession")) {
                Button(
                    onClick = { context.startActivity(Intent(context, ValidationActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open the validation") }
            }
            if (status.requiredFlips > 0) {
                val left = status.requiredFlips - status.madeFlips
                Field("Flips", "${status.madeFlips} of ${status.requiredFlips} made" + if (left <= 0) " ✓" else "")
                if (left > 0) {
                    Text(
                        "Make $left more flip${if (left > 1) "s" else ""} before the validation: on app.idena.io, " +
                            "sign in with your private key, rent a shared node (Settings, Node), then create " +
                            "the flips.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { openWebApp(context) }) { Text("Create flips on app.idena.io") }
                }
            }
        }
    }
}

/** A mining change the validator confirms: the dialog text and the node call. */
private enum class MiningChange(val title: String, val text: String) {
    Start(
        "Mine on this phone",
        "Your identity mines with this phone's node, which must then keep running. If it stays offline for " +
            "about an hour, other nodes can switch your identity offline with a mining penalty.",
    ),
    Stop("Stop mining", "Your identity stops mining with this phone's node."),
    Delegate(
        "Delegate to a pool",
        "Your identity mines through the pool's node, and the pool receives your mining and validation " +
            "rewards. You can lose your stake and all these rewards to the pool. The delegation can be stopped " +
            "from the next epoch.",
    ),
    Undelegate("Stop delegation", "Your identity stops mining through the pool; the rewards come to your address again."),
}

/**
 * Mining: with this phone's node, delegated to a pool, or none. Offers the changes the node would accept
 * now (see [MiningActions]) and follows the transaction until it is in a block. The node applies online
 * and delegation changes every 50 blocks (about 17 minutes).
 */
@Composable
private fun MiningCard(status: NodeStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = MiningActions.of(
        status.period, status.identityState, status.online, status.isPool, status.delegatee != null,
        status.delegationEpoch, status.epoch, status.penaltySeconds,
    )
    var change by remember { mutableStateOf<MiningChange?>(null) }
    var pool by remember { mutableStateOf("") }
    var pendingTx by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun send(change: MiningChange) {
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
                    when (change) {
                        MiningChange.Start -> rpc.startMining()
                        MiningChange.Stop -> rpc.stopMining()
                        MiningChange.Delegate -> rpc.delegate(pool.trim())
                        MiningChange.Undelegate -> rpc.undelegate()
                    }
                }
            }
            val hash = result.getOrElse {
                message = "The node refused: ${it.message}"
                return@launch
            }
            pendingTx = hash
            while (!withContext(Dispatchers.IO) { runCatching { NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).isMined(hash) }.getOrDefault(false) }) {
                delay(10_000)
            }
            pendingTx = null
            message = "${change.title}: done. The node applies it within 50 blocks (about 17 minutes)."
            onChanged()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mining", style = MaterialTheme.typography.titleMedium)
            Field("Status", status.miningStatus)
            if (status.penaltySeconds > 0) {
                Field("Penalty", "${duration(status.penaltySeconds.toDouble())} left")
            }
            pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                status.period != "None" -> Text("Mining changes wait until the validation is over.", style = MaterialTheme.typography.bodySmall)
                pendingTx == null -> {
                    if (actions.canStartMining) OutlinedButton(onClick = { change = MiningChange.Start }) { Text(MiningChange.Start.title) }
                    if (actions.canStopMining) OutlinedButton(onClick = { change = MiningChange.Stop }) { Text(MiningChange.Stop.title) }
                    if (actions.canDelegate) OutlinedButton(onClick = { change = MiningChange.Delegate }) { Text(MiningChange.Delegate.title) }
                    if (actions.canUndelegate) OutlinedButton(onClick = { change = MiningChange.Undelegate }) { Text(MiningChange.Undelegate.title) }
                    if (status.delegatee != null && !actions.canUndelegate) {
                        Text("The delegation can be stopped from the next epoch.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (status.delegatee == null && !actions.canDelegate && status.penaltySeconds > 0) {
                        Text("No delegation while a mining penalty runs.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    change?.let { selected ->
        val poolValid = Regex("^0x[0-9a-fA-F]{40}$").matches(pool.trim())
        AlertDialog(
            onDismissRequest = { change = null },
            title = { Text(selected.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.text)
                    if (selected == MiningChange.Delegate) {
                        OutlinedTextField(value = pool, onValueChange = { pool = it }, label = { Text("Pool address (0x…)") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { change = null; send(selected) },
                    enabled = selected != MiningChange.Delegate || poolValid,
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { change = null }) { Text("Cancel") } },
        )
    }
}

/** Opens the Idena web app in the browser; nothing happens if the phone has no browser. */
private fun openWebApp(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.idena.io"))) }
}

@Composable
private fun Field(name: String, value: String) {
    Row {
        Text(name, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "57%": a share (stake kept, APY) as a percentage, rounded down so it never shows more than it is. */
private fun percent(share: Double): String = "${(share * 100 + 1e-9).toLong()}%"

/** Formats an iDNA amount from the node (a decimal string) with at most 4 decimals. */
private fun idna(amount: String): String = try {
    BigDecimal(amount).setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + " iDNA"
} catch (e: NumberFormatException) {
    amount
}
