package io.github.truongfelix.idenanode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * What the phone has read of the moderation contract (Moderation.kt), kept in files/social/moderation.json: its
 * events in the blocks up to [ModerationCache.high]. Read on from the node: bcn_blocksWithAddress finds the blocks
 * with a call of the contract, bcn_contractCalls (with their events) reads them, as the desktop app
 * (moderation-sync.js).
 */

data class ModerationCache(val high: Long, val events: List<ModerationEvent>) {
    fun toJson(): JSONObject = JSONObject()
        .put("contract", MODERATION_CONTRACT).put("version", VERSION).put("high", high)
        .put("events", JSONArray(events.map { it.toJson() }))

    companion object {
        /** 1: the first. */
        const val VERSION = 1

        fun empty() = ModerationCache(MODERATION_FIRST_BLOCK - 1, emptyList())

        /** The cache as saved, or a new one when there is none, or it is for another contract or version. */
        fun fromJson(json: JSONObject?): ModerationCache {
            if (json == null || json.optString("contract").lowercase() != MODERATION_CONTRACT ||
                json.optInt("version") != VERSION || !json.has("high")) return empty()
            return runCatching {
                val list = json.getJSONArray("events")
                ModerationCache(json.getLong("high"), (0 until list.length()).map { moderationEventFromJson(list.getJSONObject(it)) })
            }.getOrElse { empty() }
        }
    }
}

/** The node's answers the sync needs (NodeModerationSource; a fake in the tests). */
interface ModerationSource {
    fun head(): Long
    fun blocksWithContract(from: Long, to: Long): List<Long>
    fun calls(heights: List<Long>): JSONArray
}

class NodeModerationSource(private val rpc: NodeRpc) : ModerationSource {
    override fun head(): Long = (rpc.call("bcn_lastBlock") as JSONObject).getLong("height")

    override fun blocksWithContract(from: Long, to: Long): List<Long> {
        val params = JSONArray().put(JSONObject().put("address", MODERATION_CONTRACT).put("from", from).put("to", to))
        val heights = rpc.call("bcn_blocksWithAddress", params, readTimeoutMs = 70_000) as? JSONArray ?: return emptyList()
        return (0 until heights.length()).map { heights.getLong(it) }
    }

    override fun calls(heights: List<Long>): JSONArray {
        val params = JSONArray().put(
            JSONObject().put("contract", MODERATION_CONTRACT).put("heights", JSONArray(heights)).put("events", true),
        )
        return rpc.call("bcn_contractCalls", params, readTimeoutMs = 70_000) as? JSONArray ?: JSONArray()
    }
}

/** The most blocks one bcn_blocksWithAddress call checks (the node refuses 100,000). */
const val MODERATION_RANGE = 50_000L

/**
 * [cache] read on through the node's last block; [onRange] gets the cache after each range read, to save it. An
 * error stops at the last range read whole, never skipping a block; once [stopped] it asks the node nothing more.
 */
fun syncModeration(
    cache: ModerationCache,
    source: ModerationSource,
    onRange: (ModerationCache) -> Unit = {},
    stopped: () -> Boolean = { false },
): ModerationCache {
    val head = source.head()
    var current = cache
    while (current.high < head) {
        if (stopped()) return current
        val from = maxOf(1L, current.high + 1)
        val to = minOf(head, from + MODERATION_RANGE - 1)
        val heights = source.blocksWithContract(from, to)
        val found = mutableListOf<ModerationEvent>()
        for (chunk in heights.chunked(BLOCKS_PER_CALL)) {
            if (stopped()) return current
            found += callEvents(source.calls(chunk))
        }
        current = current.copy(high = to, events = current.events + found)
        onRange(current)
    }
    return current
}

/** The cache file and the setting, in files/social/ beside the scan's. */
object ModerationStore {
    private fun dir(context: Context) = File(context.filesDir, "social").apply { mkdirs() }
    private fun cacheFile(context: Context) = File(dir(context), "moderation.json")
    private fun onFile(context: Context) = File(dir(context), "community_moderation")

    fun load(context: Context): ModerationCache =
        ModerationCache.fromJson(runCatching { JSONObject(cacheFile(context).readText()) }.getOrNull())

    fun save(context: Context, cache: ModerationCache) {
        val tmp = File(dir(context), "moderation.json.tmp")
        tmp.writeText(cache.toJson().toString())
        tmp.renameTo(cacheFile(context))
    }

    /** Whether Community moderation is on: off unless turned on (the file's presence). */
    fun isOn(context: Context): Boolean = onFile(context).exists()

    fun setOn(context: Context, on: Boolean) {
        if (on) onFile(context).writeText("on") else onFile(context).delete()
    }
}
