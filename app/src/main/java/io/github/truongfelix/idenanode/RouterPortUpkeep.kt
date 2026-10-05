package io.github.truongfelix.idenanode

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * Keeps the opening the app made ([RouterOpening]) while the node runs, every few minutes in the node process
 * ([upkeepAction]): closes it at the user's end, makes it again for the phone's new address or after the router
 * lost it, renews it near the end of a router lease shorter than the user's choice, and makes it again once when
 * no peer connected from outside after a change. Nothing when the phone is on another network: the router's
 * lease ends the opening there.
 */
object RouterPortUpkeep {
    fun run(context: Context) {
        val opening = RouterFiles.opening(context) ?: return
        if (!RouterNet.onWifi(context)) return
        val gateway = RouterNet.gatewayAt(opening.gateway.location, opening.gateway.udn) ?: return
        val (mapping, phoneIp) = RouterNet.mapping(gateway)
        val now = System.currentTimeMillis()
        val inbound = inboundPeersSince(RouterFiles.nodeLogTail(context), Instant.ofEpochMilli(opening.changedAtMs), Instant.ofEpochMilli(now))
        when (upkeepAction(opening, mapping, phoneIp, now, validationRuns(context), inbound)) {
            UpkeepAction.Nothing -> Unit
            UpkeepAction.Close -> {
                RouterNet.delete(gateway)
                RouterFiles.setOpening(context, null)
            }
            UpkeepAction.Forget -> RouterFiles.setOpening(context, null)
            UpkeepAction.Open -> {
                RouterNet.open(gateway, phoneIp, opening.leaseSeconds(now), replace = mapping != null)
                RouterFiles.setOpening(context, opening.copy(gateway = gateway, client = phoneIp, changedAtMs = now, retried = false))
            }
            UpkeepAction.Renew -> {
                RouterNet.open(gateway, phoneIp, opening.leaseSeconds(now), replace = true)
                RouterFiles.setOpening(context, opening.copy(gateway = gateway, changedAtMs = now))
            }
            UpkeepAction.Retry -> {
                RouterNet.open(gateway, phoneIp, opening.leaseSeconds(now), replace = true)
                RouterFiles.setOpening(context, opening.copy(gateway = gateway, changedAtMs = now, retried = true))
            }
        }
    }

    /** Whether a validation runs (its period is not None); false when the node does not answer. */
    private fun validationRuns(context: Context): Boolean = runCatching {
        val epoch = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).call("dna_epoch") as JSONObject
        epoch.optString("currentPeriod", "None") != "None"
    }.getOrDefault(false)
}

/**
 * The router search, run by the node process (it lives on while the app is in the background): some routers
 * announce themselves only every ~15 minutes and answer no search. Its state is a file for the screen.
 */
object RouterSearch {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun statusFile(context: Context) = java.io.File(NodeSettings.nodeDir(context), "router_search.json")

    /** The last search: when it started, whether it still runs (not after its longest time), what it found. */
    fun status(context: Context, nowMs: Long = System.currentTimeMillis()): RouterSearchStatus? = runCatching {
        val json = JSONObject(statusFile(context).readText())
        val started = json.getLong("startedMs")
        RouterSearchStatus(
            startedMs = started,
            // A search whose process ended mid-way stays "running" in the file: not after its longest time.
            running = json.optBoolean("running") && nowMs - started < RouterNet.LISTEN_MS + 60_000,
            foundUdn = json.optString("found").ifEmpty { null },
        )
    }.getOrNull()

    /** Looks for the router of this Wi-Fi and remembers it; does nothing while a search runs. */
    fun run(context: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            val started = System.currentTimeMillis()
            write(context, JSONObject().put("startedMs", started).put("running", true))
            val gateway = if (RouterNet.onWifi(context)) {
                runCatching { RouterNet.find(context, RouterFiles.known(context)) }
                    .onFailure { android.util.Log.w("IdenaNode", "Router search failed: $it") }.getOrNull()
            } else {
                null
            }
            gateway?.let { RouterFiles.remember(context, it) }
            write(context, JSONObject().put("startedMs", started).put("running", false).put("found", gateway?.udn ?: ""))
        } finally {
            running.set(false)
        }
    }

    private fun write(context: Context, json: JSONObject) {
        val file = statusFile(context)
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }
}

/** The last router search ([RouterSearch]). */
data class RouterSearchStatus(val startedMs: Long, val running: Boolean, val foundUdn: String?)
