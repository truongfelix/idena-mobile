package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** What the app reads from the profiles contract ([Profiles.kt]); the node's own RPC is [NodeProfilesSource]. */
interface ProfilesSource {
    /** The profiles of [addresses] (lowercase), each with its identity state and its voucher's, read now. */
    fun profiles(addresses: Collection<String>): Map<String, ContractProfile>

    /** The Trust of [target] (an address, or "r/<topic>") as the apps count it. */
    fun trust(target: String): TrustCount
}

/** Reads the profiles contract through the app's own node: one request for many keys (a JSON-RPC batch). */
class NodeProfilesSource(private val rpc: NodeRpc, private val contract: String = PROFILE_CONTRACT) : ProfilesSource {

    override fun profiles(addresses: Collection<String>): Map<String, ContractProfile> {
        val list = addresses.map { it.lowercase() }.distinct()
        if (list.isEmpty()) return emptyMap()
        val values = readValues(list.flatMap { address -> PROFILE_MAPS.map { it to address } })
        val byAddress = list.withIndex().associate { (i, address) ->
            address to values.subList(i * PROFILE_MAPS.size, (i + 1) * PROFILE_MAPS.size)
        }
        val vouchers = byAddress.values.mapNotNull { it[5]?.lowercase() }
        val states = states(list + vouchers)
        return byAddress.mapValues { (address, v) -> contractProfile(address, v, states) }
    }

    override fun trust(target: String): TrustCount {
        val givers = entries("t:${target.lowercase()}:").map { (key, value) -> trustGiver(key, value) }
        if (givers.isEmpty()) return countTrust(emptyList(), emptyMap(), emptyMap())
        val addresses = givers.map { it.address }
        val vouchers = addresses.zip(readValues(addresses.map { "v:" to it }))
            .mapNotNull { (address, voucher) -> voucher?.let { address to it.lowercase() } }.toMap()
        return countTrust(givers, states(addresses + vouchers.values), vouchers)
    }

    /**
     * The extra amount a call from [from] sends as a vouched member, at the network's [feePerGas] (10^-18 iDNA), or
     * null: the contract reads it from any account it lists with a voucher, unless it read in this epoch that the
     * account has an identity ([readsMemberExtra]).
     */
    fun memberExtra(from: String, feePerGas: java.math.BigInteger): MemberExtra? {
        val me = profiles(listOf(from)).values.first()
        if (me.voucher == null) return null
        val kept = readValues(listOf("i:" to from.lowercase())).first()
        val epoch = (rpc.call("dna_epoch") as JSONObject).optInt("epoch")
        if (!readsMemberExtra(kept, epoch)) return null
        return MemberExtra(memberExtraAmount(feePerGas), lapsed = !(isVouchable(me.state) && isValidated(me.voucherState)))
    }

    /** The vouches offered to [member], not accepted yet: each voucher and the epoch it offered. */
    fun offersTo(member: String): List<VouchEntry> =
        entries("o:${member.lowercase()}:").map { (key, value) -> VouchEntry(key.lowercase(), value.toIntOrNull() ?: 0) }

    /** The members [voucher] vouches for: each member and the epoch it accepted. */
    fun members(voucher: String): List<VouchEntry> =
        entries("m:${voucher.lowercase()}:").map { (key, value) -> VouchEntry(key.lowercase(), value.toIntOrNull() ?: 0) }

    /** The epoch [voucher] offered to vouch for [member], or null without such an offer. */
    fun offer(member: String, voucher: String): Int? =
        readValues(listOf("o:" to "${member.lowercase()}:${voucher.lowercase()}")).first()?.toIntOrNull()

    /** The challenge of [challenger] on [name], or null. */
    fun challenge(name: String, challenger: String): NameChallenge? =
        parseChallenge(readValues(listOf("c:" to "$name:${challenger.lowercase()}")).first())

    /** The holder of [name] (with or without its "@") and the holder's profile; a null holder when nobody holds it. */
    fun resolve(name: String): ResolvedName {
        val clean = name.removePrefix("@").lowercase()
        if (!PROFILE_NAME.matches(clean)) return ResolvedName(null, null)
        val holder = readValues(listOf("n:" to clean)).first()?.lowercase() ?: return ResolvedName(null, null)
        return ResolvedName(holder, profiles(listOf(holder))[holder])
    }

    /** The values of ([map], key) [entries] in one request: null for a key never written. */
    private fun readValues(entries: List<Pair<String, String>>): List<String?> =
        rpc.batchSettled("contract_readMap", entries.map { (map, key) -> readMapParams(contract, map, key) })
            .map { readMapValue(it) }

    /** The identity states of [addresses], by lowercase address ("Undefined" when the node gives none). */
    private fun states(addresses: List<String>): Map<String, String> {
        val unique = addresses.map { it.lowercase() }.distinct()
        return unique.zip(rpc.batch("dna_identity", unique.map { JSONArray().put(it) }))
            .associate { (address, identity) -> address to ((identity as? JSONObject)?.optString("state")?.ifEmpty { null } ?: "Undefined") }
    }

    /** Every entry of the contract whose key starts with [prefix], the prefix cut from each key, 100 a page. */
    private fun entries(prefix: String): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        var token: String? = null
        do {
            val page = rpc.call(
                "contract_iterateMap",
                JSONArray().put(contract).put(prefix).put(token ?: JSONObject.NULL).put("string").put("string").put(100),
            ) as? JSONObject ?: break
            page.optJSONArray("items")?.let { items ->
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    entries += item.optString("key") to item.optString("value")
                }
            }
            token = nextPageToken(page.opt("continuationToken"))
            if (token != null && !seen.add(token)) throw IOException("contract_iterateMap repeats its pages")
        } while (token != null)
        return entries
    }
}
