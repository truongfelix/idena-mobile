package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/** The type of a burn transaction (types.BurnTx) and of a profile change (types.ChangeProfileTx). */
const val BURN_TX = 0xc
const val CHANGE_PROFILE_TX = 0xd

/** One of my ads as the list shows it: [draft] is null for an ad found only in the profile (made elsewhere). */
class MyAd(
    val draft: AdDraft?,
    val cid: String,
    val contract: String,
    val title: String,
    val desc: String,
    val thumb: ByteArray?,
    /** Its entry in the profile, once shown. */
    val entry: ProfileEntry?,
    val stage: AdStage,
)

/** The list of [MyAds]: the ads, the balance, and why the profile could not be read (its ads are then missing). */
class MyAds(val ads: List<MyAd>, val balance: BigDecimal, val profileError: String?)

/** A review ready to send: the ad's bytes and cid, the storage fee, the voting, and the balance it is checked against. */
class ReviewPlan(val adBytes: ByteArray, val cid: String, val storeFee: BigDecimal, val form: NewVotingForm, val publish: PublishPlan) {
    /** Everything it takes from the balance: storage, stake, deploy fee, and the launch (deposit and review fee). */
    val total: BigDecimal
        get() = storeFee + publish.stake + publish.cost.fee + publish.launchAmount
}

/**
 * A "Show my ad" or "Burn more" ready to send: the new profile list when it changes ([profile], its cid and the
 * fees of storing it and of the change), then the burn of [amount] with [key].
 */
class ShowPlan(
    val profile: ByteArray?,
    val profileCid: String,
    val profileFees: BigDecimal,
    val key: String,
    val amount: BigDecimal,
    val burnFee: BigDecimal,
) {
    val fees: BigDecimal
        get() = profileFees + burnFee
}

/** Taking a campaign out of the profile, ready to send: the new profile list, its cid, and the fees. */
class RemovalPlan(val profile: ByteArray, val profileCid: String, val fees: BigDecimal)

/**
 * My ads through the app's own node; [ipfs] reads a cid's content (the phone's files, else the node). The calls block:
 * use them off the main thread.
 */
class MyAdsNode(private val rpc: NodeRpc, private val ipfs: (String) -> ByteArray?) {
    private val posting = SocialPosting(rpc)
    private val reviewsOver = mutableMapOf<Pair<String, String>, Boolean>()

    fun me(): String = (rpc.call("dna_getCoinbaseAddr") as String).lowercase()

    /** The ads of [store] and of the profile of [me], with their stages. */
    fun list(store: AdDraftStore, me: String): MyAds {
        val drafts = store.list()
        val profile = runCatching { profileEntries(me) }
        val entries = profile.getOrDefault(emptyList())
        val burns = adBurns()
        val now = Instant.now()
        val voting = OracleVotingNode(rpc)
        val mine = drafts.map { draft ->
            val entry = entries.lastOrNull { it.cid == draft.cid && draft.cid.isNotEmpty() }
            val contract = draft.contract
            val review = if (contract.isEmpty() || entry != null) null else readReview(contract, draft.cid)
            val state = if (contract.isEmpty() || entry != null || review?.over == true) null else runCatching { voting.voting(contract, me) }.getOrNull()
            MyAd(draft, draft.cid, contract, draft.title, draft.desc, store.thumb(draft.id), entry,
                adStage(contract, state, review, entry != null, burntFor(burns, me, draft.cid), now))
        }
        val known = drafts.mapTo(HashSet()) { it.cid }
        val elsewhere = entries.filter { it.cid !in known }.distinctBy { it.cid }.map { entry ->
            val content = read(entry.cid)?.let(::decodeAd)
            MyAd(null, entry.cid, entry.contract, content?.title ?: "Ad ${entry.cid.take(12)}…", content?.desc.orEmpty(), content?.thumb, entry,
                adStage(entry.contract, null, null, true, burntFor(burns, me, entry.cid), now))
        }
        return MyAds(mine + elsewhere, posting.balance(me), profile.exceptionOrNull()?.let { it.message ?: "unknown error" })
    }

    /** The review of [contract] for the ad [cid] (the banner's check), or null when it cannot be read. */
    private fun readReview(contract: String, cid: String): AdReview? = runCatching {
        adReview(rpc.call("contract_batchReadData", JSONArray().put(contract).put(adReviewKeys())) as JSONArray, cid)
    }.getOrNull()

    /**
     * The ads in the profile of [address]: empty without a profile. Throws when the profile cannot be read or is no
     * list of ads, so that it is never written back without the ads already in it.
     */
    fun profileEntries(address: String): List<ProfileEntry> {
        val hash = (rpc.call("dna_identity", JSONArray().put(address)) as JSONObject).optString("profileHash")
        if (hash.isEmpty()) return emptyList()
        val bytes = read(hash) ?: throw IOException("the profile cannot be read from IPFS now")
        return runCatching { decodeProfile(bytes) }.getOrElse { throw IOException("the profile is not a list of ads") }
    }

    /** Every ad burn of the last day (bcn_burntCoins), whoever burnt it. */
    fun adBurns(): List<AdBurn> {
        val burnt = rpc.call("bcn_burntCoins") as? JSONArray ?: return emptyList()
        return (0 until burnt.length()).mapNotNull { i ->
            val item = burnt.optJSONObject(i) ?: return@mapNotNull null
            val key = decodeBurnKey(item.optString("key")) ?: return@mapNotNull null
            AdBurn(item.optString("address"), item.optString("amount"), key.first, key.second)
        }
    }

    /** The other ads competing with [cid] for [target]: the approved ones only (the banner's check). */
    fun competition(cid: String, target: AdTarget): Competition {
        val approved = adBurns().filter { it.cid != cid }.filter { runCatching { approvedAd(rpc, it, ::read, reviewsOver) }.getOrNull() != null }
        return competition(approved, cid, target)
    }

    /**
     * Plans the review of [draft] with its pictures and [reviewFee]: the ad's cid, storage fee and voting. [params]: the
     * desktop's settings, shorter ones only on a test network.
     */
    fun planReview(me: String, draft: AdDraft, thumb: ByteArray, media: ByteArray, reviewFee: BigDecimal, params: AdVotingParams = AD_REVIEW_PARAMS): ReviewPlan {
        if (!rpc.synced()) throw IOException("the node is still syncing; send it once it is synced")
        val bytes = encodeAd(draft.title.trim(), draft.desc.trim(), draft.url.trim(), thumb, media, params)
        if (bytes.size > MAX_IPFS_FILE) throw IOException("the pictures are too large")
        val cid = posting.cid(bytes)
        val storeFee = posting.estimateStore(me, cid, bytes.size)
        val form = adReviewForm(draft.title, cid, reviewFee, params)
        return ReviewPlan(bytes, cid, storeFee, form, VotingOwnerNode(rpc).planPublish(me, form, Instant.now()))
    }

    /** Adds the ad of [plan] to IPFS and sends its storage; returns the storage transaction. */
    fun storeAd(plan: ReviewPlan): String {
        val added = posting.add(plan.adBytes)
        if (added != plan.cid) throw IOException("IPFS gave the ad another cid")
        return posting.store(added)
    }

    /**
     * Plans showing the ad [cid] (reviewed by [contract]) to [target] with a burn of [amount]: the profile gets the ad
     * (or its new audience) unless it lists it so already.
     */
    fun planShow(me: String, cid: String, contract: String, target: AdTarget, amount: BigDecimal): ShowPlan {
        if (!rpc.synced()) throw IOException("the node is still syncing; try once it is synced")
        val entry = ProfileEntry(cid, targetHex(target), contract, me)
        val entries = profileEntries(me)
        if (entries.contains(entry)) return planBurn(me, entry, amount)
        val profile = encodeProfile(withProfileEntry(entries, entry))
        val profileCid = posting.cid(profile)
        val fees = posting.estimateStore(me, profileCid, profile.size) +
            estimate(JSONObject().put("type", CHANGE_PROFILE_TX).put("from", me).put("payload", bytesToHex(changeProfilePayload(profileCid))))
        val burn = planBurn(me, entry, amount)
        return ShowPlan(profile, profileCid, fees, burn.key, amount, burn.burnFee)
    }

    /** Plans a burn of [amount] for the profile's [entry] ("Burn more"): its key names the audience as the profile does. */
    fun planBurn(me: String, entry: ProfileEntry, amount: BigDecimal): ShowPlan {
        val key = adBurnKey(entry.cid, entry.target)
        val fee = estimate(burnArgs(me, key, amount))
        return ShowPlan(null, "", BigDecimal.ZERO, key, amount, fee)
    }

    /** Sends [plan]: the profile (stored on IPFS, then the change) when it changes, then the burn; returns the burn. */
    fun sendShow(me: String, plan: ShowPlan): String {
        plan.profile?.let { profile ->
            val added = posting.add(profile)
            if (added != plan.profileCid) throw IOException("IPFS gave the profile another cid")
            posting.store(added)
            send(JSONObject().put("type", CHANGE_PROFILE_TX).put("from", me).put("payload", bytesToHex(changeProfilePayload(added))))
        }
        return send(burnArgs(me, plan.key, plan.amount))
    }

    /**
     * Plans taking the campaign [entry] out of the profile of [me]: the list without it, and the fees of storing it and
     * of the change. An empty list is no file to store (the node refuses to store 0 bytes, and every IPFS node reads
     * the empty file's cid at once): only the change, to that cid. Throws when the profile does not list it.
     */
    fun planRemoval(me: String, entry: ProfileEntry): RemovalPlan {
        if (!rpc.synced()) throw IOException("the node is still syncing; try once it is synced")
        val entries = profileEntries(me)
        val remaining = withoutProfileEntry(entries, entry)
        if (remaining.size == entries.size) throw IOException("your profile does not list this campaign")
        val profile = encodeProfile(remaining)
        val profileCid = posting.cid(profile)
        val storeFee = if (profile.isEmpty()) BigDecimal.ZERO else posting.estimateStore(me, profileCid, profile.size)
        val fees = storeFee +
            estimate(JSONObject().put("type", CHANGE_PROFILE_TX).put("from", me).put("payload", bytesToHex(changeProfilePayload(profileCid))))
        return RemovalPlan(profile, profileCid, fees)
    }

    /** Sends [plan]: the profile list to IPFS and its storage (unless empty), then the profile change; returns the change. */
    fun sendRemoval(me: String, plan: RemovalPlan): String {
        if (plan.profile.isNotEmpty()) {
            val added = posting.add(plan.profile)
            if (added != plan.profileCid) throw IOException("IPFS gave the profile another cid")
            posting.store(added)
        }
        return send(JSONObject().put("type", CHANGE_PROFILE_TX).put("from", me).put("payload", bytesToHex(changeProfilePayload(plan.profileCid))))
    }

    private fun burnArgs(me: String, key: String, amount: BigDecimal) = JSONObject()
        .put("type", BURN_TX).put("from", me).put("amount", amount.toPlainString()).put("payload", bytesToHex(burnPayload(key)))

    /** The fee of a transaction from bcn_estimateTx, which checks it as the mempool would. Throws with the node's reason. */
    private fun estimate(args: JSONObject): BigDecimal =
        BigDecimal((rpc.call("bcn_estimateTx", JSONArray().put(args)) as JSONObject).optString("txFee", "0"))

    /** Sends a transaction the node signs (its max fee twice the fee); returns its hash. */
    private fun send(args: JSONObject): String = rpc.call("dna_sendTransaction", JSONArray().put(args)) as String

    /** The content of [cid]; null when not found or not a cid. */
    private fun read(cid: String): ByteArray? = if (ipfsCid("ipfs://$cid") == null) null else ipfs(cid)
}
