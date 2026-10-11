package io.github.truongfelix.idenanode

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

class MainActivity : ComponentActivity() {
    // The taps on the Social notification ([SocialBadge]): each opens the Social tab's Inbox.
    private var showActivity by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra(SocialBadge.EXTRA_SHOW_ACTIVITY, false)) showActivity++
        setContent {
            AppTheme {
                Scaffold { padding ->
                    MainScreen(showActivity, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(SocialBadge.EXTRA_SHOW_ACTIVITY, false)) showActivity++
    }
}

/** The main tabs, which stay at the top of the screen. */
private enum class MainTab(val title: String) { Node("Node"), Social("Social") }

@Composable
private fun MainScreen(showActivity: Int, modifier: Modifier = Modifier) {
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
    LaunchedEffect(showActivity) {
        if (showActivity > 0) {
            tab = MainTab.Social
            social.showActivity()
        }
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
                // One row: the title, the Node | Social switch and the status (a dot with the peers; ⓘ has the
                // details): the node's on the Node tab, the scan's on the Social tab, where the feed slides it away.
                // The peers are the node's last read on both.
                val unread = social.unread.size
                val titleBar: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Idena", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(14.dp))
                            TabSwitch(tab, unread) { tab = it }
                            Spacer(Modifier.weight(1f))
                            val peers = node.view?.peers
                            val health = if (tab == MainTab.Node) node.bar.health else social.status(peers).health
                            val onInfo = { if (tab == MainTab.Node) nodeInfo = true else socialInfo = true }
                            Row(Modifier.clickable(onClick = onInfo).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(health)
                                peers?.let {
                                    Spacer(Modifier.width(6.dp))
                                    Text("$it", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, contentDescription = "Details") }
                        }
                        // The paid action of the Social tab while it runs (sending, waiting for its block).
                        social.sending.sendStatus?.takeIf { tab == MainTab.Social }?.let {
                            Text(
                                it,
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (tab == MainTab.Node) titleBar()
                // Each tab reads the node only while it is shown, but for the title row and the Social notifications.
                when (tab) {
                    MainTab.Node -> NodeScreen(node, social.people, Modifier.weight(1f))
                    MainTab.Social -> SocialScreen(social, ads.ads, titleBar, Modifier.weight(1f))
                }
                if (tab == MainTab.Node) AdBanner(ads.ads)
            }
            SnackbarHost(
                notices,
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (tab == MainTab.Node) AD_BANNER_HEIGHT else SOCIAL_BAR_HEIGHT),
            )
        }
        if (nodeInfo) NodeInfoDialog(node) { nodeInfo = false }
        if (socialInfo) SocialInfoDialog(social, node.view?.peers) { socialInfo = false }
    }
}

/** Node | Social as two pills in the title row; a badge counts the Social notifications not seen. */
@Composable
private fun TabSwitch(tab: MainTab, unread: Int, onSelect: (MainTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).border(1.dp, colors.outlineVariant, shape)) {
        MainTab.entries.forEach {
            val selected = tab == it
            Box(
                Modifier
                    .clip(shape)
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(it) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                val label = @Composable {
                    Text(
                        it.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
                if (it == MainTab.Social && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { label() } else label()
            }
        }
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
    val routerPort = remember { RouterPortModel(context.applicationContext) }
    val mining = remember { MiningActionState() }

    suspend fun refresh() = model.refresh()

    // The router port row: on Wi-Fi when the tab shows, the router found before, else a search by the node
    // process (followed every 15 s); then the router's state every minute.
    LaunchedEffect(state.syncEnabled, state.network) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            routerPort.refresh(state.syncEnabled, search = true)
            while (true) {
                delay(if (routerPort.state is RouterPortState.Searching) 15_000L else 60_000L)
                routerPort.refresh(state.syncEnabled, search = false)
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
    // The Sync switch, and a new account once its backup is checked.
    val setSync: (Boolean) -> Unit = { enabled ->
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The identity, its validation and its invitation first, then the wallet, then the node (its state is also in the
        // title row). The epoch and the flips are current once the fast sync has downloaded the state.
        val ready = state.status?.takeUnless { it.accountPending }
        ProfileCard(state, people) { scope.launch { refresh() } }
        ready?.let { WalletCard(it, people, network) { scope.launch { refresh() } } }
        NodeCard(
            state,
            mining = { ready?.let { MiningSection(it, mining) { scope.launch { refresh() } } } },
            advanced = {
                AdvancedSettings(
                    state,
                    delegation = { DelegationSection(it, mining) },
                    routerPort = { RouterPortRow(routerPort, state.syncEnabled, ready?.let { parseValidationTime(it.nextValidation) }) },
                    onChanged = { model.optionsChanged() },
                    onRestart = { NodeService.restart(context) },
                )
            },
            onSyncChange = setSync,
        )
        val nodeDir = NodeSettings.nodeDir(context).absolutePath
        val importKey: suspend (String, String) -> String = { key, password ->
            withContext(Dispatchers.IO) { Mobile.importKey(nodeDir, NodeSettings.nodeConfig(context), key.trim(), password) }
        }
        KeyCard(
            state,
            newKey = { Mobile.newKey(it) },
            exportKey = { Mobile.exportKey(nodeDir, it) },
            onCreate = { backup, password, done ->
                scope.launch {
                    val error = importKey(backup, password)
                    if (error.isNotEmpty()) {
                        done(false, "The account was not created: $error")
                    } else {
                        setSync(true)
                        done(true, NEW_ACCOUNT_MESSAGE)
                    }
                    refresh()
                }
            },
        ) { key, password, done ->
            scope.launch {
                val error = importKey(key, password)
                if (error.isNotEmpty()) {
                    done(false, error)
                } else {
                    val risk = state.status?.let { restartRisk(Instant.now(), parseValidationTime(it.nextValidation), it.period) }
                    val imported = keyImported(state.syncEnabled, risk)
                    if (imported.restart) NodeService.restart(context)
                    done(true, imported.message)
                }
                refresh()
            }
        }
    }
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
