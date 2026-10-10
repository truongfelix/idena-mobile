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
            val (mapping, phoneIp) = RouterNet.mapping(gateway)
            val opening = RouterFiles.opening(context)?.takeIf { it.gateway.udn == gateway.udn }
            val now = Instant.now()
            val found = RouterPortState.Found(
                gateway, phoneIp, mapping, opening,
                inbound = opening?.let { inboundPeersSince(RouterFiles.nodeLogTail(context), Instant.ofEpochMilli(it.changedAtMs), now) },
            )
            if (!syncEnabled && found.owner == MappingOwner.None) RouterPortState.Hidden else found
        } catch (e: Exception) {
            // The router stopped answering: maybe the phone moved to another network.
            this.gateway = null
            RouterPortState.Failed(e.message ?: e.toString())
        }
    }

    /** Opens the node's port for the phone until [end], replacing a forwarding the phone has. */
    suspend fun open(found: RouterPortState.Found, end: Instant) = act(found) {
        val opening = RouterOpening(found.gateway, found.phoneIp, end.toEpochMilli(), System.currentTimeMillis())
        val replace = found.owner == MappingOwner.Phone || found.owner == MappingOwner.PhoneBefore
        RouterNet.open(found.gateway, found.phoneIp, opening.leaseSeconds(opening.changedAtMs), replace)
        RouterFiles.setOpening(context, opening)
    }

    /** Closes the node's port at once. */
    suspend fun close(found: RouterPortState.Found) = act(found) {
        RouterNet.delete(found.gateway)
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
    val answered = heard?.let { " $it answered, without port opening (UPnP)." }.orEmpty()
    val soFar = if (minutes > 0) " ($minutes min so far)." else "."
    return "Looking for the router of this Wi-Fi…$answered Some routers announce themselves only every 15 minutes$soFar"
}

/** What the row says when the search found no router to open the port on. */
fun routerNotFoundText(heard: String?): String =
    heard?.let { "$it offers no port opening (UPnP): it may be turned off in the router's settings" }
        ?: "No router with UPnP heard on this Wi-Fi"

/** What the row says about a found router, and which buttons it offers. */
data class RouterPortView(val text: String, val canOpen: Boolean, val canClose: Boolean)

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
            val until = end?.let { "until ${formatOpeningEnd(it, zone)}" } ?: "with no end"
            val incoming = found.inbound?.let { " · $it ${if (it == 1) "peer" else "peers"} connected from outside since" }.orEmpty()
            RouterPortView("Open on $name $until$incoming", canOpen = false, canClose = true)
        }
        MappingOwner.PhoneBefore ->
            RouterPortView("Open on $name for the phone's former address; the node moves it to the new one", canOpen = false, canClose = true)
        MappingOwner.Other ->
            RouterPortView("Port $NODE_PORT on $name goes to another device (${found.mapping!!.client})", canOpen = false, canClose = false)
        MappingOwner.None -> RouterPortView("Closed on $name", canOpen = true, canClose = false)
    }
}

const val ROUTER_PORT_TITLE = "Router port"

val ROUTER_PORT_INFO =
    "Opens the node's port ($NODE_PORT) on this Wi-Fi's router (UPnP), so that outside Idena nodes can connect to " +
        "the phone. The router keeps it open until the end you choose, even if the phone leaves this Wi-Fi.\n\n" +
        "Open a port only if you know what it means: the node can then be reached from the internet."

/**
 * The Router port row of the advanced settings, on Wi-Fi: the search, a found router's state, and the Open and
 * Close buttons. [validation] gives the "until the validation" choice its date.
 */
@Composable
fun RouterPortRow(model: RouterPortModel, syncEnabled: Boolean, validation: Instant?) {
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
                contentDescription = "About $ROUTER_PORT_TITLE",
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
                TextButton(onClick = { model.searchAgain() }) { Text("Search again") }
            }
            is RouterPortState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Router: ${state.message}", style = small, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { model.refresh(syncEnabled, search = true) } }) { Text("Try again") }
            }
            is RouterPortState.Found -> {
                val view = routerPortView(state, Instant.now())
                Text(view.text, style = small, color = muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (view.canOpen) TextButton(enabled = !model.busy, onClick = { choose = true }) { Text("Open…") }
                    if (view.canClose) TextButton(enabled = !model.busy, onClick = { confirmClose = true }) { Text("Close") }
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
                        title = { Text("Close the router port?") },
                        text = {
                            Text(
                                "Outside nodes can no longer connect to the phone. The peers connected at this moment " +
                                    "drop within a minute; the node keeps looking for peers itself.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmClose = false; scope.launch { model.close(state) } }) { Text("Close the port") }
                        },
                        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(ROUTER_PORT_TITLE) },
            text = { Text(ROUTER_PORT_INFO) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
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
        title = { Text("Open the node's port on $routerName?") },
        text = {
            Column {
                Text(
                    "For how long? The router keeps it until then, also if the phone leaves this Wi-Fi.",
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
                                end?.let { "until ${formatOpeningEnd(it)}" } ?: "the validation date is not known yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen.end(Instant.now(), validation)?.let(onOpen) }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
