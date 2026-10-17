package io.github.truongfelix.idenanode

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class MainActivity : AppActivity() {
    // The taps on the Social notification ([SocialBadge]): each opens the Social tab's Notifications.
    private var showActivity by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra(SocialBadge.EXTRA_SHOW_ACTIVITY, false)) showActivity++
        if (savedInstanceState == null && intent.getBooleanExtra(ReleaseCheck.EXTRA_SHOW_RELEASE, false)) ReleaseUi.shown = true
        // The setting before the first frame: the question never shows to someone who answered it.
        ReleaseUi.on = ReleaseCheck.setting(this)
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
        if (intent.getBooleanExtra(ReleaseCheck.EXTRA_SHOW_RELEASE, false)) ReleaseUi.shown = true
    }
}

@Composable
private fun MainScreen(showActivity: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Social is the app; the node (identity, validation, wallet, settings) is a panel from the right side, open at
    // the start while the phone has no key yet (B-101).
    val panel = rememberDrawerState(if (NodeSettings.hasKey(context)) DrawerValue.Closed else DrawerValue.Open)
    // The Social state lives while the app runs, so that its notifications work while the panel is open.
    val scope = rememberCoroutineScope()
    val social = remember { SocialModel(context.applicationContext, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notices = remember { SnackbarHostState() }
    // The node's state, for the panel and the status in the title row.
    val node = remember { NodeModel(context.applicationContext) }
    // The Idena ads, in the Social feed.
    val ads = remember { AdsModel(context.applicationContext) }
    var nodeInfo by remember { mutableStateOf(false) }
    var socialInfo by remember { mutableStateOf(false) }
    val panelOpen = panel.targetValue == DrawerValue.Open
    LaunchedEffect(social) { social.run(lifecycle) }
    LaunchedEffect(node) { node.run(lifecycle) }
    LaunchedEffect(ads) { ads.run(lifecycle) }
    LaunchedEffect(panelOpen) {
        social.visible = !panelOpen
        node.nodeTabVisible = panelOpen
    }
    LaunchedEffect(showActivity) {
        if (showActivity > 0) {
            panel.close()
            social.showActivity()
        }
    }
    // Back closes the panel first (the Social screen's own Back, composed later, comes after it).
    BackHandler(enabled = panelOpen) { scope.launch { panel.close() } }
    LaunchedEffect(social) {
        for (notice in social.notices) {
            val result = notices.showSnackbar(notice, actionLabel = t(R.string.node_notice_view), withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                panel.close()
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
        // A drawer opens from the start side: the right side is the start of a right-to-left layout, and both the
        // panel and the screen are laid out left to right again inside it.
        val direction = LocalLayoutDirection.current
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ModalNavigationDrawer(
                drawerState = panel,
                // Swipes on the screen stay the feed's; the panel opens from the title row's node button.
                gesturesEnabled = panelOpen,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        ModalDrawerSheet(Modifier.fillMaxWidth(0.9f)) {
                            NodePanel(node, social, onInfo = { nodeInfo = true }, onClose = { scope.launch { panel.close() } })
                        }
                    }
                },
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(modifier.fillMaxSize()) {
                        // One row: the title, the node button (its state, the peers; opens the panel) and ⓘ (the
                        // Social scan's details). The feed slides it away.
                        val titleBar: @Composable () -> Unit = {
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Idena", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.weight(1f))
                                    NodeButton(node.bar.health, node.view?.peers, node.flipsMissing > 0) { scope.launch { panel.open() } }
                                    IconButton(onClick = { socialInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = t(R.string.common_details)) }
                                }
                                // Everything sent and waiting for its block, one line (B-81).
                                InFlightLine(social.sending.inFlight)
                                social.sending.sendStatus?.let {
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
                        SocialScreen(social, ads.ads, titleBar, Modifier.fillMaxSize())
                        SnackbarHost(notices, Modifier.align(Alignment.BottomCenter).padding(bottom = SOCIAL_BAR_HEIGHT))
                    }
                }
            }
        }
        if (nodeInfo) NodeInfoDialog(node) { nodeInfo = false }
        if (socialInfo) SocialInfoDialog(social, node.view?.peers) { socialInfo = false }
        ReleaseDialogs(remember { NodeSettings.hasKey(context) })
    }
}

/** The node's button in the title row: its state as a dot, the peers, and the panel's mark, with a badge while [task]. */
@Composable
private fun NodeButton(health: Health, peers: Int?, task: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(onClickLabel = t(R.string.node_tab_node), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(health)
        peers?.let {
            Spacer(Modifier.width(6.dp))
            Text("$it", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(6.dp))
        val mark = @Composable { Icon(Icons.Filled.Menu, contentDescription = t(R.string.node_tab_node), Modifier.size(18.dp), tint = colors.onSurfaceVariant) }
        if (task) BadgedBox(badge = { Badge() }) { mark() } else mark()
    }
}

/** The panel from the right: its title, then what the Node tab held. */
@Composable
private fun NodePanel(node: NodeModel, social: SocialModel, onInfo: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t(R.string.node_tab_node), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            StatusDot(node.bar.health)
            node.view?.peers?.let {
                Spacer(Modifier.width(6.dp))
                Text("$it", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, contentDescription = t(R.string.common_details)) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = t(R.string.common_close)) }
        }
        NodeScreen(node, social.people, social.names, Modifier.weight(1f))
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
private fun NodeScreen(model: NodeModel, people: Map<String, Person>, names: Map<String, String>, modifier: Modifier = Modifier) {
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
        ready?.let { WalletCard(it, people, names, network) { scope.launch { refresh() } } }
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
        // The node key is kept encrypted (NodeKeys); the node reads it at its next start.
        val importKey: suspend (String, String) -> String = { key, password ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val opened = Mobile.openKey(key, password)
                    try {
                        NodeKeys.files(context).write(opened)
                    } finally {
                        opened.fill(0)
                    }
                }.exceptionOrNull()?.let { it.message ?: "$it" } ?: ""
            }
        }
        KeyCard(
            state,
            newKey = { Mobile.newKey(it) },
            exportKey = { password ->
                val key = NodeKeys.files(context).read() ?: throw IOException(t(R.string.node_no_key))
                try {
                    Mobile.exportKey(key, password)
                } finally {
                    key.fill(0)
                }
            },
            onCreate = { backup, password, done ->
                scope.launch {
                    val error = importKey(backup, password)
                    if (error.isNotEmpty()) {
                        done(false, t(R.string.node_account_not_created, error))
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
