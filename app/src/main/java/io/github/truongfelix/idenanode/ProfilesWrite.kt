package io.github.truongfelix.idenanode

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

// Writing to the profiles contract ([Profiles.kt]): its calls, their callbacks' gas and fees, a vouched member's
// extra amount, and the picture's format. As the desktop (renderer/screens/social/profiles.js, vouching.js, image.js).

const val MAX_BIO = 160

/** The calls of the profiles contract, each with one JSON argument written as the desktop writes it. */
object ProfileCalls {
    fun claimName(name: String) = call("claimName", "{\"name\":${jsonString(name)}}")

    fun dropName() = call("dropName", null)

    fun setProfile(bio: String, picture: String) = call("setProfile", "{\"bio\":${jsonString(bio)},\"picture\":${jsonString(picture)}}")

    private fun call(method: String, argument: String?) = SocialCall(method, argument, "0", PROFILE_CONTRACT)
}

/** The contract's default gas for a callback (WASM gas), and the least the app asks for. */
private const val DEFAULT_CALLBACK_GAS = 10_000_000L
private const val MIN_CALLBACK_GAS = 1_000_000L

/** WASM gas per gas (idena-go vm/costs). */
private val WASM_PER_GAS = BigInteger.valueOf(100)

/** The actions a call's dry run made after it (promises and callbacks), from its receipt's action tree. */
private fun subActions(receipt: JSONObject): List<JSONObject> {
    val subs = receipt.optJSONObject("actionResult")?.optJSONArray("subActionResults") ?: return emptyList()
    return (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }
}

private fun method(action: JSONObject) = action.optJSONObject("inputAction")?.optString("method").orEmpty()

private fun bounded(used: Long) = minOf(DEFAULT_CALLBACK_GAS, maxOf(MIN_CALLBACK_GAS, 3 * used))

/**
 * The gas to ask for the callback of a call from its dry run: three times what the callback that acts used, at least
 * 1,000,000, at most the contract's default; null when the call made no callback. The node reserves the whole
 * callback gas out of the call's gas limit, which the max fee pays: the default would make it about ten times the fee.
 */
fun callbackGas(receipt: JSONObject): Long? = subActions(receipt)
    .filter { it.optJSONObject("inputAction")?.optInt("actionType") == 1 && method(it).startsWith("_") && method(it) != "_keepState" }
    .lastOrNull()?.let { bounded(it.optLong("gasUsed")) }

/** The gas to ask for each _keepState (a state read before the one that acts): three times the most one used, as above. */
fun keepStateGas(receipt: JSONObject): Long? = subActions(receipt)
    .filter { method(it) == "_keepState" }
    .maxOfOrNull { it.optLong("gasUsed") }?.let(::bounded)

/** [call] asking [gas] for its callback ("_gas") and [keepGas] for each _keepState ("_keepGas"), each when not null. */
fun withCallbackGas(call: SocialCall, gas: Long?, keepGas: Long?): SocialCall {
    val argument = call.argument ?: return call
    if (gas == null && keepGas == null) return call
    val extra = listOfNotNull(gas?.let { "\"_gas\":$it" }, keepGas?.let { "\"_keepGas\":$it" }).joinToString(",")
    return call.copy(argument = argument.removeSuffix("}") + (if (argument.trim() == "{}") "" else ",") + extra + "}")
}

/** The gas the dry run's promises and callbacks reserve out of the call's gas limit (rounded up from WASM gas). */
fun reservedGas(receipt: JSONObject): BigInteger {
    val wasm = subActions(receipt).fold(BigInteger.ZERO) { sum, it ->
        sum + (it.optJSONObject("inputAction")?.optString("gasLimit")?.toBigIntegerOrNull() ?: BigInteger.ZERO)
    }
    return (wasm + WASM_PER_GAS - BigInteger.ONE) / WASM_PER_GAS
}

/**
 * The fee of a profile call from its dry run, and its max fee: twice the fee plus what its promises reserve (given
 * back after), as the desktop, rounded up to 6 decimals. With only the fee doubled, the node would run the call out
 * of gas in the block: a callback's gas is reserved out of the gas limit the max fee buys.
 */
fun profileFee(receipt: JSONObject): PostFee {
    val gasCost = BigDecimal(receipt.optString("gasCost", "0"))
    val fee = BigDecimal(receipt.optString("txFee", "0")) + gasCost
    val gasUsed = receipt.optLong("gasUsed")
    val reserved = if (gasUsed > 0) gasCost * BigDecimal(reservedGas(receipt)) / BigDecimal(gasUsed) else BigDecimal.ZERO
    return PostFee(fee, ((fee + reserved) * BigDecimal(2)).setScale(6, RoundingMode.UP))
}

/** The refusals of a receipt: the call's and those of its promises and callbacks, each once, the call's first. */
fun receiptErrors(receipt: JSONObject): List<String> {
    val errors = mutableListOf<String>()
    fun walk(action: JSONObject?) {
        if (action == null) return
        if (action.has("success") && !action.optBoolean("success") && action.optString("error").isNotEmpty()) errors += action.optString("error")
        val subs = action.optJSONArray("subActionResults") ?: return
        for (i in 0 until subs.length()) walk(subs.optJSONObject(i))
    }
    walk(receipt.optJSONObject("actionResult"))
    if (!receipt.optBoolean("success") && receipt.optString("error").isNotEmpty()) errors.add(0, receipt.optString("error"))
    return errors.distinct()
}

/** The identity state codes (idena-go core/state) of the accounts without an identity, as the contract keeps them. */
private val VOUCHABLE_CODES = setOf("0", "1", "2", "5")

/**
 * Whether the contract reads the extra amount from a call of an account it lists with a voucher: unless it read in
 * [epoch] that the account has an identity ([kept], its "i:" entry "<epoch>:<state>"). A member who validated stays
 * listed until a call of its own, which pays it too; a call without the amount fails in the VM.
 */
fun readsMemberExtra(kept: String?, epoch: Int): Boolean {
    if (kept == null) return true
    val parts = kept.split(":")
    return parts[0].toIntOrNull() != epoch || parts.getOrNull(1) in VOUCHABLE_CODES
}

/** The gas a vouched member's call adds, burned by the contract (VOUCHED_EXTRA_GAS). */
private val VOUCHED_EXTRA_GAS = BigInteger.valueOf(25_000)

/**
 * What a vouched member sends with a call, in iDNA: 25,000 gas at the network's fee per gas ([feePerGas], in 10^-18
 * iDNA), plus a tenth, rounded up to 6 decimals; the contract asks for it at the minimum fee.
 */
fun memberExtraAmount(feePerGas: BigInteger): String {
    val wei = feePerGas * VOUCHED_EXTRA_GAS * BigInteger.valueOf(11) / BigInteger.TEN
    return BigDecimal(wei).movePointLeft(18).setScale(6, RoundingMode.UP).stripTrailingZeros().toPlainString()
}

/** A vouched member's extra amount for a call ([amount], iDNA), and whether the account no longer acts as a member. */
data class MemberExtra(val amount: String, val lapsed: Boolean)

/** A profile picture: square, this many pixels a side, WebP, at most [MAX_PICTURE_BYTES] (ruling Q-48). */
const val PICTURE_SIDE = 256
const val MAX_PICTURE_BYTES = 32_000

/** The centered square of a [width] x [height] image: x, y and its side. */
fun squareCrop(width: Int, height: Int): Triple<Int, Int, Int> {
    val side = minOf(width, height)
    return Triple((width - side) / 2, (height - side) / 2, side)
}

/**
 * Whether [bytes] may show as a profile picture (Q-48): a still WebP (RIFF/WEBP, and in an extended VP8X header no
 * animation flag) of at most [MAX_PICTURE_BYTES]. The contract keeps only the reference: a direct call may point to
 * any file.
 */
fun isShowablePicture(bytes: ByteArray): Boolean {
    if (bytes.size > MAX_PICTURE_BYTES || bytes.size < 21) return false
    fun ascii(from: Int, to: Int) = String(bytes, from, to - from, Charsets.US_ASCII)
    if (ascii(0, 4) != "RIFF" || ascii(8, 12) != "WEBP") return false
    return !(ascii(12, 16) == "VP8X" && bytes[20].toInt() and 0x02 != 0)
}
