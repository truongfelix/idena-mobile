package io.github.truongfelix.idenanode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.truongfelix.idenanode.go.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    MainScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

/** The main tabs, which stay at the top of the screen. */
private enum class MainTab(val title: String) { Node("Node"), Social("Social") }

@Composable
private fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Node) }
    // The Social tab's state lives while the app runs, so that its notifications work from the Node tab.
    val scope = rememberCoroutineScope()
    val social = remember { SocialModel(context.applicationContext, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notices = remember { SnackbarHostState() }
    // The node's state, for the Node tab and the status bar of the title row (both tabs).
    val node = remember { NodeModel(context.applicationContext) }
    // The Idena ads: the banner at the bottom of the Node tab, in the Social feed.
    val ads = remember { AdsModel(context.applicationContext) }
    var nodeInfo by remember { mutableStateOf(false) }
    var socialInfo by remember { mutableStateOf(false) }
    LaunchedEffect(social) { social.run(lifecycle) }
    LaunchedEffect(node) { node.run(lifecycle) }
    LaunchedEffect(ads) { ads.run(lifecycle) }
    LaunchedEffect(tab) {
        social.visible = tab == MainTab.Social
        node.nodeTabVisible = tab == MainTab.Node
    }
    // Back on the Social tab, once it has nothing to close, shows the Node tab instead of leaving the app
    // (the Social tab's own Back, composed later, comes first).
    BackHandler(enabled = tab == MainTab.Social) { tab = MainTab.Node }
    LaunchedEffect(social) {
        for (notice in social.notices) {
            val result = notices.showSnackbar(notice, actionLabel = "View", withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                tab = MainTab.Social
                social.showActivity()
            }
        }
    }

    // An app update or a force stop ends the node without switching sync off: start it again. The
    // service ignores the start when the node already runs.
    LaunchedEffect(Unit) {
        if (NodeSettings.isSyncEnabled(context)) NodeService.start(context)
    }

    // The tabs' text a bit larger than Material's defaults (the validation screens keep them).
    MaterialTheme(typography = MaterialTheme.typography.scaled(1.12f)) {
        Box(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // The title and the status, one line high: the node's on the Node tab; on the Social tab the
                // scan's with the node's peers, or the paid action while it runs.
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Idena", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(16.dp))
                    when (tab) {
                        MainTab.Node -> StatusLine(node.bar, Modifier.weight(1f)) { nodeInfo = true }
                        MainTab.Social -> {
                            val action = social.sendStatus
                            StatusLine(
                                social.status,
                                Modifier.weight(1f),
                                text = action ?: socialBarLabel(social.status, social.node?.peers),
                                textColor = if (action != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            ) { socialInfo = true }
                        }
                    }
                }
                val unread = social.unread.size
                TabRow(selectedTabIndex = tab.ordinal) {
                    MainTab.entries.forEach {
                        Tab(selected = tab == it, onClick = { tab = it }, text = {
                            if (it == MainTab.Social && unread > 0) {
                                BadgedBox(badge = { Badge { Text("$unread") } }) { Text(it.title) }
                            } else Text(it.title)
                        })
                    }
                }
                // Each tab reads the node only while it is shown, but for the title row and the Social notifications.
                when (tab) {
                    MainTab.Node -> NodeScreen(node, social.people, Modifier.weight(1f))
                    MainTab.Social -> SocialScreen(social, ads.ads, Modifier.weight(1f))
                }
                if (tab == MainTab.Node) AdBanner(ads.ads)
            }
            SnackbarHost(
                notices,
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (tab == MainTab.Node) AD_BANNER_HEIGHT else SOCIAL_BAR_HEIGHT),
            )
        }
        if (nodeInfo) NodeInfoDialog(node) { nodeInfo = false }
        if (socialInfo) SocialInfoDialog(social) { socialInfo = false }
    }
}

/** Every text style [factor] times larger, font size and line height. */
private fun Typography.scaled(factor: Float): Typography {
    fun TextStyle.scaled() = copy(
        fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
    )
    return Typography(
        displayLarge = displayLarge.scaled(), displayMedium = displayMedium.scaled(), displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(), headlineMedium = headlineMedium.scaled(), headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(), titleMedium = titleMedium.scaled(), titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(), bodyMedium = bodyMedium.scaled(), bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(), labelMedium = labelMedium.scaled(), labelSmall = labelSmall.scaled(),
    )
}

/** The app's colors: light or dark as the phone's setting (the themes keep the phone's forced dark off). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
private fun NodeScreen(model: NodeModel, people: Map<String, Person>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // The model reads the node every 3 s while this tab is shown.
    val state = model.state
    var network by remember { mutableStateOf<NetworkRewards?>(null) }

    suspend fun refresh() = model.refresh()

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
        NodeCard(
            state,
            mining = { state.status?.takeUnless { it.accountPending }?.let { MiningSection(it) { scope.launch { refresh() } } } },
        ) { enabled ->
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                NodeService.start(context)
            } else {
                NodeService.stop(context)
            }
            model.syncChanged(enabled)
        }
        // The epoch and the flips are current once the fast sync has downloaded the state. Within a day of
        // the validation, or during it, its card comes first.
        val validation = state.status?.takeUnless { it.accountPending }
        val validationFirst = validation != null &&
            validationIsClose(Instant.now(), parseValidationTime(validation.nextValidation), validation.period)
        if (validationFirst) ValidationCard(validation!!, showCountdown = true)
        AccountCard(state)
        state.status?.takeUnless { it.accountPending }?.let { WalletCard(it, people, network) { scope.launch { refresh() } } }
        state.status?.takeIf { !it.accountPending && it.identityState == "Invite" }?.let { InvitationCard(it) { scope.launch { refresh() } } }
        if (validation != null && !validationFirst) ValidationCard(validation, showCountdown = false)
        if (backgroundIssue(state.ignoringBatteryOptimizations) != null) BackgroundCard(state)
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

/**
 * Sync: the switch, and the progress while syncing in a slot of fixed height, so that the cards under it
 * never move; then [mining] (the identity's mining, once the node has the account). The block, peers,
 * speed and network are in the title row's details (ⓘ).
 */
@Composable
private fun NodeCard(state: ScreenState, mining: @Composable () -> Unit, onSyncChange: (Boolean) -> Unit) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        maxLines = 1,
                    )
                }
                // Without a key the node would create a random one that the app cannot back up.
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = onSyncChange,
                    enabled = state.hasKey || state.syncEnabled,
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (status != null && status.syncing && status.highestBlock > 0) {
                    LinearProgressIndicator(
                        progress = { status.currentBlock.toFloat() / maxOf(status.highestBlock, status.currentBlock).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            mining()
        }
    }
}

/** The account: its avatar (drawn from the address), the address with copy, the identity and its age. */
@Composable
private fun AccountCard(state: ScreenState) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            val address = status?.address ?: state.keyAddress
            if (address == null) {
                Text("No key yet: import one below.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(address, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        status == null -> Text("Switch sync on to read the identity from the node.", style = MaterialTheme.typography.bodyMedium)
                        status.accountPending -> Text("Identity and age: after sync", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            val color = statusPalette()(status.identityState)
                            Field("Identity", identityStatus(status.identityState), color)
                            Field("Age", if (status.age == 1) "1 epoch" else "${status.age} epochs", color)
                        }
                    }
                }
            }
            AddressLine(address)
            if (status != null && !status.accountPending && status.syncing) {
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
/** Shown while Android's battery optimization may stop the node with the screen off. */
@Composable
private fun BackgroundCard(state: ScreenState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The node may stop with the screen off", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            BackgroundSettings(state)
        }
    }
}

/**
 * The next validation, and the flips still to make with a link to the web app to make them; with
 * [showCountdown] (the card comes first, within a day of it), the time left in its title.
 */
@Composable
private fun ValidationCard(status: NodeStatus, showCountdown: Boolean) {
    val context = LocalContext.current
    val start = parseValidationTime(status.nextValidation)
    val date = start?.let {
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
        "${format.format(it)} (${timeUntil(Instant.now(), it)})"
    } ?: status.nextValidation
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when {
                periodText(status.period) != null -> "Validation · now"
                showCountdown && start != null -> "Next validation · ${countdown(Instant.now(), start)}"
                else -> "Next validation"
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
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
            if (status.requiredFlips > 0 || status.availableFlips > 0) {
                val quota = FlipQuota(status.identityState, status.requiredFlips, status.availableFlips, status.madeFlips)
                val extra = if (quota.available > quota.required) " (up to ${quota.available})" else ""
                Field("Flips", "${quota.made} of ${quota.required} made$extra" + if (quota.missing == 0) " ✓" else "")
                if (quota.missing > 0) {
                    Text(
                        "Make ${quota.missing} more flip${if (quota.missing > 1) "s" else ""} before the flip lottery, 5 minutes " +
                            "before the validation; without them the identity becomes ${quota.missingOutcome}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (quota.canMake && status.period == "None") {
                    Button(
                        onClick = { context.startActivity(Intent(context, FlipActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Make a flip") }
                }
            }
        }
    }
}

/** Waits until the node has the transaction in a block, asking every 10 seconds. */
internal suspend fun waitUntilMined(context: Context, hash: String) {
    while (!withContext(Dispatchers.IO) {
            runCatching { NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).isMined(hash) }.getOrDefault(false)
        }
    ) {
        delay(10_000)
    }
}

/**
 * An invitation sent to the identity's address, waiting to be accepted: the activation transaction
 * makes the identity a candidate for the next validation. Only outside the ceremony.
 */
@Composable
private fun InvitationCard(status: NodeStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var pendingTx by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun accept() {
        message = null
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).acceptInvitation() }
            }.getOrElse {
                message = "The node refused: ${it.message}"
                return@launch
            }
            pendingTx = hash
            waitUntilMined(context, hash)
            pendingTx = null
            message = "Invitation accepted: the identity is a candidate for the next validation."
            onChanged()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Invitation", style = MaterialTheme.typography.titleMedium)
            Text("You are invited to Idena. Accept the invitation to take part in the next validation as a candidate.")
            pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                !canAcceptInvitation(status.period, status.identityState) ->
                    Text("The invitation can be accepted once the validation is over.", style = MaterialTheme.typography.bodySmall)
                pendingTx == null -> Button(onClick = { confirming = true }) { Text("Accept the invitation") }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Accept the invitation") },
            text = { Text("The node sends the activation transaction of your address. Your identity becomes a candidate: take part in the next validation to become a Newbie.") },
            confirmButton = { TextButton(onClick = { confirming = false; accept() }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
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
 * Mining, in the Sync card: with this phone's node, delegated to a pool, or none. Offers the changes the node would accept
 * now (see [MiningActions]) and follows the transaction until it is in a block. The node applies online
 * and delegation changes every 50 blocks (about 17 minutes).
 */
@Composable
private fun MiningSection(status: NodeStatus, onChanged: () -> Unit) {
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
            waitUntilMined(context, hash)
            pendingTx = null
            message = "${change.title}: done. The node applies it within 50 blocks (about 17 minutes)."
            onChanged()
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mining", style = MaterialTheme.typography.titleMedium)
        run {
            Field("Status", status.miningStatus)
            // Mining on this phone: the switch sends the change (after its dialog); delegation below.
            val miningHere = status.online && status.delegatee == null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mine on this phone", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = miningHere,
                    onCheckedChange = { on -> change = if (on) MiningChange.Start else MiningChange.Stop },
                    enabled = pendingTx == null && (actions.canStartMining || actions.canStopMining),
                )
            }
            if (status.period == "None" && status.delegatee == null && !status.isPool && status.identityState !in validatedStates) {
                Text("Mining needs a validated identity (Newbie, Verified or Human).", style = MaterialTheme.typography.bodySmall)
            }
            if (status.penaltySeconds > 0) {
                Field("Penalty", "${duration(status.penaltySeconds.toDouble())} left")
            }
            pendingTx?.let { Text("Transaction sent, waiting for a block (${it.take(10)}…)", style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                status.period != "None" -> Text("Mining changes wait until the validation is over.", style = MaterialTheme.typography.bodySmall)
                pendingTx == null -> {
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
internal fun Field(name: String, value: String, color: Color = Color.Unspecified) {
    Row {
        Text(name, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** "57%": a share (stake kept, APY) as a percentage, rounded down so it never shows more than it is. */
internal fun percent(share: Double): String = "${(share * 100 + 1e-9).toLong()}%"

/** Formats an iDNA amount from the node (a decimal string) with at most 4 decimals. */
internal fun idna(amount: String): String = try {
    BigDecimal(amount).setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + " iDNA"
} catch (e: NumberFormatException) {
    amount
}
