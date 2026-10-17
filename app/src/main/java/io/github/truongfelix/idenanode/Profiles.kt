package io.github.truongfelix.idenanode

import org.json.JSONArray

// The profiles contract (idena-profile, github.com/truongfelix/idena-profile): one unique name, a short bio and a
// picture per identity in good standing, Trust (a public vouch counted per person and per topic) and vouched members.
// Read with contract_readMap and contract_iterateMap, every key and value a string. The desktop reads it the same
// way (renderer/screens/social/profiles.js).

/** Mainnet's contract: the build of idena-profile b49127a, deployed 2026-10-16. */
const val PROFILE_CONTRACT = "0xd558483330925f169bbe692d8ea15b2faa83c17f"

/** A name: lower case letters, digits and "_", 3 to 20 characters (the contract's rule). */
val PROFILE_NAME = Regex("^[a-z0-9_]{3,20}$")

/** The states that may hold a name, write a profile and vouch. */
private val VALIDATED = setOf("Newbie", "Verified", "Human")

/** The states whose Trust counts: the validated ones and the two that come back at the next validation. */
private val TRUSTING = VALIDATED + setOf("Zombie", "Suspended")

/** The states without an identity: such an account may be vouched for. */
private val VOUCHABLE = setOf("Undefined", "Invite", "Candidate", "Killed")

fun isValidated(state: String?) = state in VALIDATED

fun isTrusting(state: String?) = state in TRUSTING

/** An unknown or empty state is Undefined (dna_identity's answer for an address without an identity). */
fun isVouchable(state: String?) = (state?.ifEmpty { null } ?: "Undefined") in VOUCHABLE

/**
 * Whether an address is in good standing, as the contract decides it: a validated identity, or an account without
 * an identity vouched for by a validated one. Out of good standing, the apps hide its name, bio, picture and Trusts.
 */
fun inStanding(state: String?, voucher: String?, voucherState: String?) =
    isValidated(state) || (voucher != null && isVouchable(state) && isValidated(voucherState))

/** The maps an address's profile is read from: name, bio, picture, Trusts by identities and by members, voucher. */
internal val PROFILE_MAPS = listOf("a:", "b:", "p:", "k:", "w:", "v:")

/**
 * What the contract holds for [address] (null when not set), with its identity state and its voucher's, read
 * together. [trusted] and [trustedVouched] are the contract's raw counts; [TrustCount] counts as the apps show it.
 */
data class ContractProfile(
    val address: String,
    val name: String?,
    val bio: String?,
    val picture: String?,
    val trusted: Int,
    val trustedVouched: Int,
    val voucher: String?,
    val state: String?,
    val voucherState: String?,
) {
    val inStanding get() = inStanding(state, voucher, voucherState)

    /** The claimed name to show ("@name"), only in good standing. */
    val shownName get() = name?.takeIf { inStanding }?.let { "@$it" }

    /** The picture's IPFS id to show, only in good standing. */
    val shownPicture get() = picture?.takeIf { inStanding }?.let { ipfsCid(it) }

    /** The bio to show, only in good standing. */
    val shownBio get() = bio?.takeIf { inStanding && it.isNotBlank() }

    /** The voucher to show ("vouched by"): only while the account has no identity of its own. */
    val shownVoucher get() = voucher?.takeIf { isVouchable(state) }
}

/** [text] as the hex of its UTF-8 bytes, as contract_readMap takes a key. */
fun utf8Hex(text: String) = "0x" + text.toByteArray().joinToString("") { "%02x".format(it) }

/** contract_readMap's parameters for [map] and [key] in [contract], as strings. */
fun readMapParams(contract: String, map: String, key: String): JSONArray =
    JSONArray().put(contract).put(map).put(utf8Hex(key)).put("string")

/**
 * A contract_readMap answer as a value: null for a key the contract never wrote ("data is nil"); any other error
 * throws, never read as "nothing".
 */
fun readMapValue(answer: Result<Any?>): String? = answer.fold(
    { it?.toString() },
    { if (it.message?.contains("data is nil", ignoreCase = true) == true) null else throw it },
)

/** The profile of [address] from its [values] (one per [PROFILE_MAPS] entry) and the [states] read with it. */
fun contractProfile(address: String, values: List<String?>, states: Map<String, String>): ContractProfile {
    require(values.size == PROFILE_MAPS.size) { "one value per profile map" }
    val voucher = values[5]?.lowercase()
    return ContractProfile(
        address = address,
        name = values[0],
        bio = values[1],
        picture = values[2],
        trusted = values[3]?.toIntOrNull() ?: 0,
        trustedVouched = values[4]?.toIntOrNull() ?: 0,
        voucher = voucher,
        state = states[address],
        voucherState = voucher?.let { states[it] },
    )
}

/** The claimed names to show, by lowercase address: those of profiles in good standing. */
fun shownNames(profiles: Collection<ContractProfile>): Map<String, String> =
    profiles.mapNotNull { p -> p.shownName?.let { p.address to it } }.toMap()

/**
 * The names the app shows, by lowercase address: the names given on this phone ([local]) first, else the claimed
 * names ([claimed]), as the desktop does for now (BACKLOG B-72 decides how the two meet).
 */
fun mergedNames(local: Map<String, String>, claimed: Map<String, String>) = claimed + local

/** A Trust given to a target, from the contract map "t:<target>:": its giver, the epoch, and whether given as a member. */
data class TrustGiver(val address: String, val epoch: Int, val vouched: Boolean)

/** The giver of an entry of "t:<target>:" ([key] = the giver, [value] = "<epoch>" or "<epoch>:v"). */
fun trustGiver(key: String, value: String): TrustGiver {
    val parts = value.split(":")
    return TrustGiver(key.lowercase(), parts[0].toIntOrNull() ?: 0, parts.getOrNull(1) == "v")
}

/**
 * The givers who count now, and the counts shown: "Trusted by [identities] (+[vouched] vouched)". [givers] are all
 * the contract keeps, counted or not: an account's own Trust stays given (and can be taken back) while it does not count.
 */
data class TrustCount(
    val counted: List<TrustGiver>,
    val identities: Int,
    val vouched: Int,
    val givers: List<TrustGiver> = counted,
    /** The givers' vouchers, by giver: who vouches for each one counted as a vouched member. */
    val vouchers: Map<String, String> = emptyMap(),
) {
    /** "Trusted by 15": the identities and the vouched members together. */
    val total: Int
        get() = identities + vouched

    /** Whether [address] gave its Trust. */
    fun givenBy(address: String?) = address != null && givers.any { it.address == address.lowercase() }
}

/**
 * Which [givers] count now, by each giver's standing now, whatever the Trust was stored as: an identity in a
 * trusting state counts with the identities; an account without one, while its voucher is validated, with the
 * vouched members. [states] holds the givers' and their vouchers' states, [vouchers] the givers' vouchers.
 */
fun countTrust(givers: List<TrustGiver>, states: Map<String, String>, vouchers: Map<String, String>): TrustCount {
    val counted = givers.mapNotNull { giver ->
        val state = states[giver.address]
        val voucher = vouchers[giver.address]
        when {
            isTrusting(state) -> giver.copy(vouched = false)
            voucher != null && isVouchable(state) && isValidated(states[voucher]) -> giver.copy(vouched = true)
            else -> null
        }
    }
    return TrustCount(counted, counted.count { !it.vouched }, counted.count { it.vouched }, givers, vouchers)
}

/** "Trusted by 15": the identities and the vouched members together (the list gives the split); null when nobody counts. */
fun trustText(count: TrustCount): String? = if (count.total == 0) null else "Trusted by ${count.total}"
