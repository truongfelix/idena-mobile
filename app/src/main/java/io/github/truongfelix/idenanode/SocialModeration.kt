package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Community moderation for the Social tab (B-308): the setting (off unless turned on), the contract read on from the
 * node at most once a minute by the scan loop ([SocialModel.run]) while it is on and the node ready, and the state its
 * events leave. Off, nothing is loaded or read and nothing is moderated.
 */
class SocialModeration(private val context: Context, private val scope: CoroutineScope) {
    var on by mutableStateOf(ModerationStore.isOn(context))
        private set

    // The cache read from the phone (null until loaded, or while off).
    private var cache by mutableStateOf<ModerationCache?>(null)

    /** The current epoch (dna_epoch), read at each sync; null until known (every ban then counts, as the website). */
    var epoch by mutableStateOf<Long?>(null)
        private set

    /** The account's stake (iDNA), read at each sync: a staker is a moderator. */
    var myStake by mutableStateOf(0.0)
        private set

    /** The last sync's error, shown in the setting. */
    var error by mutableStateOf<String?>(null)
        private set

    // Whether a sync of this run ended (well or not): the first read holds the feed only without decisions saved.
    private var synced by mutableStateOf(false)

    // Raised at each switch: a sync started before it neither marks the read done nor keeps its cache.
    private var generation = 0
    private var lastSync = 0L

    /** The state the events leave; nothing while off. */
    val state: ModerationState by derivedStateOf { cache?.takeIf { on }?.let { replay(it.events) } ?: ModerationState.None }

    /** Every event read, in chain order (the Moderation page's log reverses it). */
    val events: List<ModerationEvent>
        get() = cache?.takeIf { on }?.events.orEmpty()

    /** The block through which the decisions were read; null before the first. */
    val high: Long?
        get() = cache?.high?.takeIf { on && it >= MODERATION_FIRST_BLOCK }

    /**
     * Whether the feed waits: on, and no decisions known yet (the saved ones not loaded, or none saved and the first
     * read of this run not ended), so that nothing about to be hidden is shown, or its pictures fetched.
     */
    val loading: Boolean
        get() = on && (cache == null || cache!!.high < MODERATION_FIRST_BLOCK && !synced)

    init {
        if (on) load()
    }

    private fun load() {
        scope.launch { cache = withContext(Dispatchers.IO) { runCatching { ModerationStore.load(context) }.getOrDefault(ModerationCache.empty()) } }
    }

    fun change(value: Boolean) {
        on = value
        generation++
        scope.launch(Dispatchers.IO) { runCatching { ModerationStore.setOn(context, value) } }
        if (value) {
            lastSync = 0
            load()
        } else {
            cache = null
            synced = false
            error = null
        }
    }

    /** Reads the contract again at the next step (an action of the account in a block). */
    fun refresh() {
        lastSync = 0
    }

    /** Reads the contract on, at most once a minute (or at once after [refresh]); called by the scan loop, the node ready. */
    suspend fun syncIfDue(rpc: NodeRpc, me: String?) {
        val start = cache
        if (!on || start == null || System.currentTimeMillis() - lastSync < SYNC_EVERY_MS) return
        lastSync = System.currentTimeMillis()
        val started = generation
        try {
            val next = withContext(Dispatchers.IO) {
                epoch = (rpc.call("dna_epoch") as JSONObject).optLong("epoch").takeIf { it > 0 }
                if (me != null) myStake = (rpc.call("dna_identity", org.json.JSONArray().put(me)) as? JSONObject)
                    ?.optString("stake")?.toDoubleOrNull() ?: 0.0
                syncModeration(start, NodeModerationSource(rpc), { range ->
                    runCatching { ModerationStore.save(context, range) }
                }, stopped = { !on })
            }
            if (on && started == generation) cache = next
            if (started == generation) error = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (started == generation) error = e.message ?: e.toString()
        } finally {
            if (started == generation) synced = true
        }
    }

    private companion object {
        const val SYNC_EVERY_MS = 60_000L
    }
}
