package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the Router port row of the advanced settings knows. */
sealed interface RouterPortState {
    /** Not on Wi-Fi, or nothing to show (sync off and no opening). */
    data object Hidden : RouterPortState
    /**
     * The node process looks for the router ([RouterSearch]) since [startedMs]; [heard] = the router that answered
     * without a forwarding service, still listened to.
     */
    data class Searching(val startedMs: Long, val heard: String? = null) : RouterPortState
    /** No forwarding service found; [heard] = the router that answered without one. */
    data class NotFound(val heard: String? = null) : RouterPortState
    data class Found(
        val gateway: Gateway,
        val phoneIp: String,
        val mapping: PortMapping?,
        val opening: RouterOpening?,
        /** Peers that connected from outside since the opening's last change; null without an opening. */
        val inbound: Int?,
        /** The port read: the opening's, else the node's. */
        val port: Int = DEFAULT_NODE_PORT,
        /** When another device holds [port]: a node port free on this router ([nextFreePort]), or null. */
        val freePort: Int? = null,
    ) : RouterPortState {
        val owner: MappingOwner get() = mappingOwner(mapping, phoneIp, opening)
    }
    data class Failed(val message: String) : RouterPortState
}

/**
 * Runs the Router port row in the app's process: reads the forwarding of the node's port on a router found
 * before, else asks the node process to look for the router ([RouterSearch]: up to ~16 min, on the Node tab when
 * none was looked for in the last hours, and on request) and follows that search; opens or closes the forwarding
 * at the user's request. The node process keeps the opening afterwards ([RouterPortUpkeep]).
 */
/** How long after a search the Node tab starts another by itself; "Search again" starts one at any time. */
const val SEARCH_AGAIN_MS = 6 * 3_600_000L

class RouterPortModel(private val context: Context) {
    var state by mutableStateOf<RouterPortState>(RouterPortState.Hidden)
        private set

    /** An open or close request is running. */
    var busy by mutableStateOf(false)
        private set

    private var gateway: Gateway? = null

    /**
     * Reads the router's forwarding again; without a router here, follows the node's search, or starts one when
     * [search] allows it and none ran in the last [SEARCH_AGAIN_MS] (a search only while sync is on: the port
     * matters to a running node, and the node process runs the search).
     */
    suspend fun refresh(syncEnabled: Boolean, search: Boolean) {
        if (busy) return
        withContext(Dispatchers.IO) {
            if (!RouterNet.onWifi(context)) {
                gateway = null
                state = RouterPortState.Hidden
                return@withContext
            }
            // The opening's router is usually a known one too: asked once (each one not here costs a timeout).
            val known = gateway ?: RouterNet.knownHere(
                (listOfNotNull(RouterFiles.opening(context)?.gateway) + RouterFiles.known(context)).distinctBy { it.udn },
            )
            if (known != null) {
                gateway = known
                read(known, syncEnabled)
                return@withContext
            }
            val now = System.currentTimeMillis()
            val status = RouterSearch.status(context, now)
            state = when {
                !syncEnabled -> RouterPortState.Hidden
                status?.running == true -> RouterPortState.Searching(status.startedMs, status.heard)
                // A router that stopped answering (another Wi-Fi?) is looked for again.
                (search || state is RouterPortState.Failed) && (status == null || now - status.startedMs > SEARCH_AGAIN_MS) -> {
                    NodeService.searchRouter(context)
                    RouterPortState.Searching(now)
                }
                status != null -> RouterPortState.NotFound(status.heard)
                else -> RouterPortState.Hidden
            }
        }
    }

    /** "Search again": the node process looks for the router now. */
    fun searchAgain() {
        NodeService.searchRouter(context)
        state = RouterPortState.Searching(System.currentTimeMillis())
    }

    private fun read(gateway: Gateway, syncEnabled: Boolean) {
        state = try {
            val opening = RouterFiles.opening(context)?.takeIf { it.gateway.udn == gateway.udn }
            val port = opening?.port ?: NodeSettings.routerPort(context)
            val (mapping, phoneIp) = RouterNet.mapping(gateway, port)
            val now = Instant.now()
            val found = RouterPortState.Found(
                gateway, phoneIp, mapping, opening,
                inbound = opening?.let { inboundPeersSince(RouterFiles.nodeLogTail(context), Instant.ofEpochMilli(it.changedAtMs), now) },
                port = port,
            )
            when {
                !syncEnabled && found.owner == MappingOwner.None -> RouterPortState.Hidden
                found.owner == MappingOwner.Other ->
                    found.copy(freePort = nextFreePort(port) { mappingOwner(RouterNet.mapping(gateway, it).first, phoneIp, opening) })
                else -> found
            }
        } catch (e: Exception) {
            // The router stopped answering: maybe the phone moved to another network.
            this.gateway = null
            RouterPortState.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Opens the node's port for the phone until [end], replacing a forwarding the phone has; the app's opening of
     * a port the node left is closed first.
     */
    suspend fun open(found: RouterPortState.Found, end: Instant) = act(found) {
        val port = NodeSettings.routerPort(context)
        val (mapping, phoneIp) = if (port == found.port) found.mapping to found.phoneIp else RouterNet.mapping(found.gateway, port)
        val owner = mappingOwner(mapping, phoneIp, found.opening)
        check(owner != MappingOwner.Other) { t(R.string.router_other_device, port, found.gateway.name, mapping!!.client) }
        found.opening?.takeIf { it.port != port }?.let { before ->
            val old = RouterNet.mapping(found.gateway, before.port).first
            if (mappingOwner(old, phoneIp, before) != MappingOwner.Other) RouterNet.delete(found.gateway, before.port)
        }
        val opening = RouterOpening(found.gateway, phoneIp, end.toEpochMilli(), System.currentTimeMillis(), port = port)
        val replace = owner == MappingOwner.Phone || owner == MappingOwner.PhoneBefore
        RouterNet.open(found.gateway, port, phoneIp, opening.leaseSeconds(opening.changedAtMs), replace)
        RouterFiles.setOpening(context, opening)
    }

    /** Closes the node's port at once. */
    suspend fun close(found: RouterPortState.Found) = act(found) {
        RouterNet.delete(found.gateway, found.port)
        RouterFiles.setOpening(context, null)
    }

    private suspend fun act(found: RouterPortState.Found, request: () -> Unit) {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val error = runCatching(request).exceptionOrNull()
                read(found.gateway, syncEnabled = true)
                if (error != null) state = RouterPortState.Failed(error.message ?: error.toString())
            }
        } finally {
            busy = false
        }
    }
}

/** What the row says while the node looks for the router, [minutes] after the search started. */
fun routerSearchingText(heard: String?, minutes: Long): String {
    val answered = heard?.let { t(R.string.router_answered_without_upnp, it) }
    val soFar = if (minutes > 0) t(R.string.router_announce_minutes, minutes) else t(R.string.router_announce)
    return listOfNotNull(t(R.string.router_searching), answered, soFar).joinToString(" ")
}

/** What the row says when the search found no router to open the port on. */
fun routerNotFoundText(heard: String?): String =
    heard?.let { t(R.string.router_no_upnp_on, it) }
        ?: t(R.string.router_none_heard)

/** What the row says about a found router, and which buttons it offers ([usePort]: "Use port", or null). */
data class RouterPortView(val text: String, val canOpen: Boolean, val canClose: Boolean, val usePort: Int? = null)

/** An opening's end as the app shows dates: the phone's format and time zone. */
fun formatOpeningEnd(end: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(end)

fun routerPortView(found: RouterPortState.Found, now: Instant, zone: ZoneId = ZoneId.systemDefault()): RouterPortView {
    val name = found.gateway.name
    return when (found.owner) {
        MappingOwner.Phone -> {
            val mapping = found.mapping!!
            val end = found.opening?.takeIf { it.client == found.phoneIp }?.let { Instant.ofEpochMilli(it.endMs) }
                ?: mapping.leaseSeconds.takeIf { it > 0 }?.let { now.plusSeconds(it) }
            val until = end?.let { t(R.string.router_until, formatOpeningEnd(it, zone)) } ?: t(R.string.router_no_end)
            val text = found.inbound?.let { tp(R.plurals.router_open_inbound, it, name, until, it) } ?: t(R.string.router_open_on, name, until)
            RouterPortView(text, canOpen = false, canClose = true)
        }
        MappingOwner.PhoneBefore ->
            RouterPortView(t(R.string.router_open_former_address, name), canOpen = false, canClose = true)
        MappingOwner.Other -> {
            val taken = t(R.string.router_other_device, found.port, name, found.mapping!!.client)
            val free = found.freePort?.let { t(R.string.router_port_free, it) }
            RouterPortView(listOfNotNull(taken, free).joinToString(". "), canOpen = false, canClose = false, usePort = found.freePort)
        }
        MappingOwner.None -> RouterPortView(t(R.string.router_closed_on, name), canOpen = true, canClose = false)
    }
}

val ROUTER_PORT_TITLE: String get() = t(R.string.router_port_title)

/** The Router port row's info, for the node's [port]. */
fun routerPortInfo(port: Int): String = t(R.string.router_port_info, port)

/**
 * The Router port row of the advanced settings, on Wi-Fi: the search, a found router's state, and the Open and
 * Close buttons; when another device holds the port, "Use port" for a free one ([onUsePort]: the node port setting,
 * taken at the next start). [validation] gives the "until the validation" choice its date.
 */
@Composable
fun RouterPortRow(model: RouterPortModel, syncEnabled: Boolean, validation: Instant?, onUsePort: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var showInfo by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val state = model.state
    if (state == RouterPortState.Hidden) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ROUTER_PORT_TITLE, style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.Outlined.Info,
                contentDescription = t(R.string.node_about_setting, ROUTER_PORT_TITLE),
                Modifier.clickable { showInfo = true }.padding(6.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val small = MaterialTheme.typography.bodySmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (state) {
            RouterPortState.Hidden -> Unit
            is RouterPortState.Searching -> {
                val minutes = (System.currentTimeMillis() - state.startedMs) / 60_000
                Text(routerSearchingText(state.heard, minutes), style = small, color = muted)
            }
            is RouterPortState.NotFound -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(routerNotFoundText(state.heard), style = small, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.searchAgain() }) { Text(t(R.string.router_search_again)) }
            }
            is RouterPortState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(R.string.router_failed, state.message), style = small, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { model.refresh(syncEnabled, search = true) } }) { Text(t(R.string.common_try_again)) }
            }
            is RouterPortState.Found -> {
                val view = routerPortView(state, Instant.now())
                Text(view.text, style = small, color = muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    view.usePort?.let { port -> TextButton(onClick = { onUsePort(port) }) { Text(t(R.string.router_use_port, port)) } }
                    if (view.canOpen) TextButton(enabled = !model.busy, onClick = { choose = true }) { Text(t(R.string.router_open_ellipsis)) }
                    if (view.canClose) TextButton(enabled = !model.busy, onClick = { confirmClose = true }) { Text(t(R.string.common_close)) }
                }
                if (choose) {
                    OpenDialog(state.gateway.name, validation, onDismiss = { choose = false }) { end ->
                        choose = false
                        scope.launch { model.open(state, end) }
                    }
                }
                if (confirmClose) {
                    AlertDialog(
                        onDismissRequest = { confirmClose = false },
                        title = { Text(t(R.string.router_close_question)) },
                        text = {
                            Text(t(R.string.router_close_text))
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmClose = false; scope.launch { model.close(state) } }) { Text(t(R.string.router_close_port)) }
                        },
                        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(t(R.string.common_cancel)) } },
                    )
                }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(ROUTER_PORT_TITLE) },
            text = { Text(routerPortInfo((state as? RouterPortState.Found)?.port ?: DEFAULT_NODE_PORT)) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(t(R.string.common_ok)) } },
        )
    }
}

/** How long to open the port for: the durations, "until the validation + 2 h" with its date when known. */
@Composable
private fun OpenDialog(routerName: String, validation: Instant?, onDismiss: () -> Unit, onOpen: (Instant) -> Unit) {
    val now = Instant.now()
    var chosen by remember { mutableStateOf(PortDuration.H6) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.router_open_question, routerName)) },
        text = {
            Column {
                Text(
                    t(R.string.router_how_long),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PortDuration.entries.forEach { duration ->
                    val end = duration.end(now, validation)
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = duration == chosen, enabled = end != null) { chosen = duration },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = duration == chosen, onClick = null, enabled = end != null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(duration.label)
                            Text(
                                end?.let { t(R.string.router_until, formatOpeningEnd(it)) } ?: t(R.string.router_validation_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen.end(Instant.now(), validation)?.let(onOpen) }) { Text(t(R.string.router_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}
