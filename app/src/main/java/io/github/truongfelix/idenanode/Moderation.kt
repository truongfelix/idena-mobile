package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * Community moderation (B-308): the idena.social moderation contract (github N3CR0M4NC3R-dev/idena.social-mod, MIT)
 * records banned accounts, hidden posts (transaction bans), the moderators vouched for and the reports. It hides
 * nothing itself: the app reads its events and applies the idena.social website's rules (idena.social-ui
 * docs/moderation.md), as the desktop app does (renderer/screens/social/moderation.js). Off by default (the Social
 * setting "Community moderation"): the app then reads nothing from it.
 */

/** Mainnet's contract, deployed 2026-11-22 (tx 0x47a94c52…), and the block its calls start. */
const val MODERATION_CONTRACT = "0x8a44ba305c09375f6a1f553920e49dae4875a5e5"
const val MODERATION_FIRST_BLOCK = 11_605_462L

/** The contract's rules, mirrored to check an action before it is signed: update them with the contract. */
const val MODERATOR_STAKE = 1_000_000
const val MAX_VOUCHES = 5
const val MAX_REASON_BYTES = 280
const val MAX_BAN_EPOCHS = 65_535
const val MAX_TRANSACTION_BATCH = 100

/** A hex text lowercase with its 0x, or "" when it is no hex. */
fun normalHex(value: String?): String {
    val text = value.orEmpty().trim().lowercase()
    val hex = if (text.startsWith("0x")) text else "0x$text"
    return if (Regex("^0x([0-9a-f]{2})*$").matches(hex)) hex else ""
}

private fun bytesOf(hex: String?): ByteArray? {
    if (hex == null) return null
    val clean = normalHex(hex)
    if (clean.isEmpty()) return null
    val digits = clean.substring(2)
    return ByteArray(digits.length / 2) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private fun readAddress(hex: String?): String? = normalHex(hex).takeIf { Regex("^0x[0-9a-f]{40}$").matches(it) }

private fun readHash(hex: String?): String? = normalHex(hex).takeIf { Regex("^0x[0-9a-f]{64}$").matches(it) }

/** A little-endian unsigned number of at most [size] bytes ("0x" is 0), or null. */
private fun readUint(hex: String?, size: Int): Long? {
    val bytes = bytesOf(hex) ?: return null
    if (bytes.size > size) return null
    var value = 0L
    for (i in bytes.indices.reversed()) value = value * 256 + (bytes[i].toInt() and 0xff)
    return value
}

private fun readText(hex: String?): String? = bytesOf(hex)?.toString(Charsets.UTF_8)

/** Where an event sits: its call (hash, height, index, block time in seconds) and its place among the call's events. */
data class EventOrder(val hash: String, val height: Long, val index: Int, val time: Long, val eventIndex: Int)

/** One event of the contract, its fields as the contract writes them (addresses and hashes lowercase). */
data class ModerationEvent(
    val name: String,
    val order: EventOrder,
    val address: String = "",
    val hash: String = "",
    val moderator: String = "",
    val reporter: String = "",
    val voucher: String = "",
    val staker: String = "",
    val epoch: Long = 0,
    val permanent: Boolean = false,
    val endEpoch: Long = 0,
    val count: Long = 0,
    val reason: String = "",
)

/** One event as the node gives it (its name and its args as 0x hex); null for one the app does not know or cannot read. */
fun decodeEvent(name: String, args: List<String?>, order: EventOrder): ModerationEvent? {
    fun arg(i: Int) = args.getOrNull(i)
    return when (name) {
        "ban" -> {
            val address = readAddress(arg(0)) ?: return null
            val moderator = readAddress(arg(1)) ?: return null
            val epoch = readUint(arg(2), 2) ?: return null
            val permanent = readUint(arg(3), 1) ?: return null
            val endEpoch = readUint(arg(4), 4) ?: return null
            val reason = readText(arg(5)) ?: return null
            ModerationEvent(name, order, address = address, moderator = moderator, epoch = epoch,
                permanent = permanent != 0L, endEpoch = endEpoch, reason = reason)
        }
        "unban" -> ModerationEvent(name, order, address = readAddress(arg(0)) ?: return null,
            moderator = readAddress(arg(1)) ?: return null, reason = readText(arg(2)) ?: return null)
        "banTransaction" -> ModerationEvent(name, order, hash = readHash(arg(0)) ?: return null,
            moderator = readAddress(arg(1)) ?: return null, epoch = readUint(arg(2), 2) ?: return null,
            reason = readText(arg(3)) ?: return null)
        "unbanTransaction" -> ModerationEvent(name, order, hash = readHash(arg(0)) ?: return null,
            moderator = readAddress(arg(1)) ?: return null, reason = readText(arg(2)) ?: return null)
        "vouch", "vouchWithdrawn" -> ModerationEvent(name, order, address = readAddress(arg(0)) ?: return null,
            voucher = readAddress(arg(1)) ?: return null, count = readUint(arg(2), 4) ?: return null)
        "vouchesRevoked" -> ModerationEvent(name, order, address = readAddress(arg(0)) ?: return null,
            staker = readAddress(arg(1)) ?: return null, count = readUint(arg(2), 4) ?: return null)
        "report" -> ModerationEvent(name, order, address = readAddress(arg(0)) ?: return null,
            reporter = readAddress(arg(1)) ?: return null, reason = readText(arg(2)) ?: return null)
        "reportTransaction" -> ModerationEvent(name, order, hash = readHash(arg(0)) ?: return null,
            reporter = readAddress(arg(1)) ?: return null, reason = readText(arg(2)) ?: return null)
        else -> null
    }
}

/** Thrown when the node gives calls without their events: an older node, never read as "no decisions". */
class NoEventsException : Exception("The node does not return the events of contract calls")

/**
 * The contract's events in the calls of bcn_contractCalls asked with `events: true`, in chain order: only those of
 * accepted calls and emitted by the contract itself. A call without its `events` list throws [NoEventsException].
 */
fun callEvents(calls: JSONArray, contract: String = MODERATION_CONTRACT): List<ModerationEvent> {
    val events = mutableListOf<ModerationEvent>()
    for (i in 0 until calls.length()) {
        val call = calls.getJSONObject(i)
        val list = call.optJSONArray("events") ?: throw NoEventsException()
        if (!call.optBoolean("success")) continue
        for (e in 0 until list.length()) {
            val event = list.getJSONObject(e)
            if (normalHex(event.optString("contract")) != contract.lowercase()) continue
            val argsJson = event.optJSONArray("args") ?: JSONArray()
            val args = (0 until argsJson.length()).map { argsJson.optString(it, null) }
            val order = EventOrder(normalHex(call.optString("hash")), call.optLong("height"), call.optInt("index"),
                call.optLong("timestamp"), e)
            decodeEvent(event.optString("event"), args, order)?.let { events += it }
        }
    }
    return events.sortedWith(compareBy({ it.order.height }, { it.order.index }, { it.order.eventIndex }))
}

/** Where an uninterrupted ban started: its call's time, height and index. */
data class BanStart(val time: Long, val height: Long, val index: Int)

/** An account's ban: in effect while [permanent], or before [endEpoch]. */
data class Ban(
    val moderator: String,
    val epoch: Long,
    val permanent: Boolean,
    val endEpoch: Long,
    val reason: String,
    val bannedAt: Long,
    val since: BanStart,
)

/** A post hidden one by one. */
data class HiddenPost(val moderator: String, val epoch: Long, val reason: String, val hiddenAt: Long)

/** A report of an account ([kind] "address") or a post ("post"). */
data class Report(val kind: String, val target: String, val reporter: String, val reason: String, val time: Long)

/** What the events leave: the bans, the posts hidden, who vouches for each vouched moderator, every report. */
data class ModerationState(
    val bans: Map<String, Ban> = emptyMap(),
    val hidden: Map<String, HiddenPost> = emptyMap(),
    val vouchers: Map<String, Set<String>> = emptyMap(),
    val reports: List<Report> = emptyList(),
) {
    val isEmpty: Boolean
        get() = bans.isEmpty() && hidden.isEmpty()

    companion object {
        val None = ModerationState()
    }
}

/**
 * The state the events (in chain order) leave. A ban that replaces one still in effect keeps where it started
 * ([Ban.since]); one after an unban or an end starts again.
 */
fun replay(events: List<ModerationEvent>): ModerationState {
    val bans = mutableMapOf<String, Ban>()
    val hidden = mutableMapOf<String, HiddenPost>()
    val vouchers = mutableMapOf<String, Set<String>>()
    val reports = mutableListOf<Report>()
    for (e in events) {
        when (e.name) {
            "ban" -> {
                val previous = bans[e.address]
                val goesOn = previous != null && (previous.permanent || e.epoch < previous.endEpoch)
                bans[e.address] = Ban(e.moderator, e.epoch, e.permanent, e.endEpoch, e.reason, e.order.time,
                    if (goesOn) previous!!.since else BanStart(e.order.time, e.order.height, e.order.index))
            }
            "unban" -> bans.remove(e.address)
            "banTransaction" -> hidden[e.hash] = HiddenPost(e.moderator, e.epoch, e.reason, e.order.time)
            "unbanTransaction" -> hidden.remove(e.hash)
            "vouch" -> vouchers[e.address] = vouchers[e.address].orEmpty() + e.voucher
            "vouchWithdrawn" -> {
                val left = vouchers[e.address].orEmpty() - e.voucher
                if (left.isEmpty()) vouchers.remove(e.address) else vouchers[e.address] = left
            }
            "vouchesRevoked" -> vouchers.remove(e.address)
            "report" -> reports += Report("address", e.address, e.reporter, e.reason, e.order.time)
            "reportTransaction" -> reports += Report("post", e.hash, e.reporter, e.reason, e.order.time)
        }
    }
    return ModerationState(bans, hidden, vouchers, reports)
}

/** Whether [ban] is in effect in [epoch]; while the epoch is not known (null), every ban counts, as the website. */
fun banActive(ban: Ban?, epoch: Long?): Boolean = ban != null && (ban.permanent || epoch == null || epoch < ban.endEpoch)

/** The ban in effect on [address], or null. */
fun ModerationState.activeBan(address: String?, epoch: Long?): Ban? =
    bans[normalHex(address)]?.takeIf { banActive(it, epoch) }

/** Who vouches for [address]: empty when it is no vouched moderator. */
fun ModerationState.vouchersOf(address: String?): Set<String> = vouchers[normalHex(address)].orEmpty()

/** An event as kept on the phone (the cache: ModerationSync.kt). */
fun ModerationEvent.toJson(): JSONObject = JSONObject()
    .put("name", name).put("hash", order.hash).put("height", order.height).put("index", order.index)
    .put("time", order.time).put("eventIndex", order.eventIndex).put("address", address).put("target", hash)
    .put("moderator", moderator).put("reporter", reporter).put("voucher", voucher).put("staker", staker)
    .put("epoch", epoch).put("permanent", permanent).put("endEpoch", endEpoch).put("count", count).put("reason", reason)

fun moderationEventFromJson(json: JSONObject): ModerationEvent = ModerationEvent(
    name = json.getString("name"),
    order = EventOrder(json.getString("hash"), json.getLong("height"), json.getInt("index"), json.getLong("time"),
        json.getInt("eventIndex")),
    address = json.optString("address"), hash = json.optString("target"), moderator = json.optString("moderator"),
    reporter = json.optString("reporter"), voucher = json.optString("voucher"), staker = json.optString("staker"),
    epoch = json.optLong("epoch"), permanent = json.optBoolean("permanent"), endEpoch = json.optLong("endEpoch"),
    count = json.optLong("count"), reason = json.optString("reason"),
)
