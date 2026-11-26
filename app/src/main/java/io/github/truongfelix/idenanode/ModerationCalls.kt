package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * The calls of the moderation contract (Moderation.kt), each with one JSON argument and no iDNA sent (the contract
 * never reads the amount), and the checks made before a call is signed: a refused call is still charged, and one a
 * callback refuses is even "successful" (no event). The node's dry run checks the rest (receiptErrors reads the
 * callbacks' refusals). As the desktop app (moderation-calls.js).
 */

/** The most gas a callback of the contract gets (its default), and the least the app gives one (WASM gas). */
const val MODERATION_CALLBACK_GAS = 4_000_000L
const val MODERATION_MIN_CALLBACK_GAS = 1_000_000L

object ModerationCalls {
    private fun call(method: String, arg: JSONObject) = SocialCall(method, arg.toString(), "0", MODERATION_CONTRACT)

    fun report(address: String, reason: String) = call("report", JSONObject().put("address", normalHex(address)).put("reason", reason))
    fun reportPost(hash: String, reason: String) = call("reportTransaction", JSONObject().put("hash", normalHex(hash)).put("reason", reason))

    /** [epochs]: how many epochs the ban lasts, 0 for a permanent one. */
    fun ban(address: String, epochs: Int, reason: String) =
        call("ban", JSONObject().put("address", normalHex(address)).put("epochs", epochs).put("reason", reason))

    fun unban(address: String, reason: String) = call("unban", JSONObject().put("address", normalHex(address)).put("reason", reason))
    fun hidePost(hash: String, reason: String) = call("banTransaction", JSONObject().put("hash", normalHex(hash)).put("reason", reason))
    fun hidePosts(hashes: List<String>, reason: String) =
        call("banTransactions", JSONObject().put("hashes", JSONArray(hashes.map { normalHex(it) })).put("reason", reason))

    fun restorePost(hash: String, reason: String) = call("unbanTransaction", JSONObject().put("hash", normalHex(hash)).put("reason", reason))
    fun vouch(address: String) = call("vouch", JSONObject().put("address", normalHex(address)))
    fun withdrawVouch(address: String) = call("withdrawVouch", JSONObject().put("address", normalHex(address)))
    fun revokeVouches(address: String) = call("revokeVouches", JSONObject().put("address", normalHex(address)))
}

/** A reason's size in UTF-8 bytes, as the contract counts it (at most [MAX_REASON_BYTES]). */
fun reasonBytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/**
 * The key of a moderation call among the actions waiting for their block: its method and target (the address, the
 * post, or the posts), not its reason or gas: a second ban of the same account waits for the first, whatever its reason.
 */
fun moderationKey(call: SocialCall): String {
    val arg = runCatching { JSONObject(call.argument ?: "{}") }.getOrElse { JSONObject() }
    val hashes = arg.optJSONArray("hashes")
    val target = arg.optString("address").ifEmpty { arg.optString("hash") }.ifEmpty {
        if (hashes == null) "" else (0 until hashes.length()).map { hashes.getString(it) }.sorted().joinToString(",")
    }
    return "${call.contract.lowercase()}:${call.method}:${target.lowercase()}"
}

/**
 * [call] with its callbacks' gas sized from a dry run's [receipt]: three times the most one used, between
 * [MODERATION_MIN_CALLBACK_GAS] and [MODERATION_CALLBACK_GAS] (the max fee reserves it, twice for a ban); the same call
 * when it ran no callback.
 */
fun withSizedCallbacks(call: SocialCall, receipt: JSONObject): SocialCall {
    val used = mutableListOf<Long>()
    fun walk(action: JSONObject?) {
        val subs = action?.optJSONArray("subActionResults") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val input = sub.optJSONObject("inputAction")
            if (input?.optInt("actionType") == 1 && input.optString("method").startsWith("_")) used += sub.optLong("gasUsed")
            walk(sub)
        }
    }
    walk(receipt.optJSONObject("actionResult"))
    if (used.isEmpty()) return call
    val gas = (3 * used.max()).coerceIn(MODERATION_MIN_CALLBACK_GAS, MODERATION_CALLBACK_GAS)
    return call.copy(argument = JSONObject(call.argument ?: "{}").put("_gasLimit", gas).toString())
}

/** The report [reporter] last made on [target] (an address or a post's hash), or null: once is enough. */
fun ModerationState.ownReport(reporter: String?, target: String): Report? {
    val from = normalHex(reporter)
    val key = normalHex(target)
    return reports.lastOrNull { it.reporter == from && it.target == key }
}

/** Why an action cannot be sent: the text shown (ModerationViews.kt). */
enum class ModerationRefusal { NoAccount, Banned, Self, AlreadyBanned, NoReason, TooLong, NotModerator, NotStaker,
    TargetStaker, TargetVouched, Epochs, Batch, VouchLimit }

/** Why [me] may not report [address] (an account; null for a post) with [reason], or null. */
fun ModerationState.reportRefusal(epoch: Long?, me: String?, address: String?, reason: String): ModerationRefusal? = when {
    me.isNullOrEmpty() -> ModerationRefusal.NoAccount
    activeBan(me, epoch) != null -> ModerationRefusal.Banned
    address != null && normalHex(address) == normalHex(me) -> ModerationRefusal.Self
    address != null && activeBan(address, epoch) != null -> ModerationRefusal.AlreadyBanned
    reason.isBlank() -> ModerationRefusal.NoReason
    reasonBytes(reason) > MAX_REASON_BYTES -> ModerationRefusal.TooLong
    else -> null
}

/** A moderator's role: from stake (at least [MODERATOR_STAKE] iDNA) or a staker's vouch; none while banned. */
enum class ModeratorRole { Staker, Vouched }

fun ModerationState.moderatorRole(epoch: Long?, address: String?, stake: Double?): ModeratorRole? = when {
    address.isNullOrEmpty() || activeBan(address, epoch) != null -> null
    (stake ?: 0.0) >= MODERATOR_STAKE -> ModeratorRole.Staker
    vouchersOf(address).isNotEmpty() -> ModeratorRole.Vouched
    else -> null
}

/**
 * Why [me] (with [role]) may not act as a moderator on [address] (null for a post), or null: a ban needs a target that
 * is no moderator ([targetRole]), an epoch count within [MAX_BAN_EPOCHS] (a negative one: not a number), a batch within
 * [MAX_TRANSACTION_BATCH].
 */
fun ModerationState.moderatorRefusal(
    epoch: Long?,
    me: String?,
    role: ModeratorRole?,
    address: String? = null,
    targetRole: ModeratorRole? = null,
    epochs: Int = 0,
    reason: String = "",
    count: Int = 1,
): ModerationRefusal? = when {
    me.isNullOrEmpty() -> ModerationRefusal.NoAccount
    role == null -> ModerationRefusal.NotModerator
    activeBan(me, epoch) != null -> ModerationRefusal.Banned
    address != null && normalHex(address) == normalHex(me) -> ModerationRefusal.Self
    targetRole == ModeratorRole.Staker -> ModerationRefusal.TargetStaker
    targetRole == ModeratorRole.Vouched -> ModerationRefusal.TargetVouched
    epochs < 0 || epochs > MAX_BAN_EPOCHS -> ModerationRefusal.Epochs
    count < 1 || count > MAX_TRANSACTION_BATCH -> ModerationRefusal.Batch
    reasonBytes(reason) > MAX_REASON_BYTES -> ModerationRefusal.TooLong
    else -> null
}

/** The addresses [voucher] vouches for (at most [MAX_VOUCHES] in effect). */
fun ModerationState.vouchesGiven(voucher: String?): List<String> {
    val key = normalHex(voucher)
    return vouchers.filterValues { key in it }.keys.toList()
}
