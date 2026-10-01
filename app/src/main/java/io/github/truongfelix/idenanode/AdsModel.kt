package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale

/**
 * The ads of the banner, read through the phone's own node (burns, profiles and ads on IPFS, review votes)
 * every 10 minutes while the app is shown and the node is synced with peers.
 */
class AdsModel(private val context: Context) {
    /** The approved ads for this identity, best first (at most [MAX_ADS]). */
    var ads by mutableStateOf<List<ChainAd>>(emptyList())
        private set

    /** Review votes that are over, which no longer change, by contract and ad. */
    private val reviewsOver = mutableMapOf<Pair<String, String>, Boolean>()

    suspend fun run(lifecycle: Lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val read = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
                if (read != null) ads = read
                // A node still starting or syncing has the burns of an old block: try again soon.
                delay(if (read != null) 10 * 60_000L else 60_000L)
            }
        }
    }

    /** The ads to show now; null while the node is not synced with peers. */
    private fun read(): List<ChainAd>? {
        val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
        val sync = rpc.call("bcn_syncing") as JSONObject
        val peers = (rpc.call("net_peers") as? JSONArray)?.length() ?: 0
        if (sync.optBoolean("syncing") || peers == 0) return null
        val identity = rpc.call("dna_identity") as JSONObject
        val viewer = AdViewer(
            language = Locale.getDefault().language,
            os = "android",
            age = identity.optInt("age"),
            stake = identity.optString("stake").toBigDecimalOrNull() ?: BigDecimal.ZERO,
        )
        val burnt = rpc.call("bcn_burntCoins") as? JSONArray ?: return emptyList()
        val shown = mutableListOf<ChainAd>()
        for (burn in rankedBurns(burnt, viewer)) {
            if (shown.size == MAX_ADS) break
            if (shown.any { it.cid == burn.cid }) continue
            runCatching { approvedAd(rpc, burn) }.getOrNull()?.let { shown += it }
        }
        return shown
    }

    /** The ad of [burn] when its author lists it and its review approved it; null otherwise. */
    private fun approvedAd(rpc: NodeRpc, burn: AdBurn): ChainAd? {
        val author = rpc.call("dna_identity", JSONArray().put(burn.address)) as? JSONObject ?: return null
        val profile = ipfs(author.optString("profileHash")) ?: return null
        val listed = decodeProfileAds(profile).firstOrNull { it.cid == burn.cid } ?: return null
        if (!Regex("^0x[0-9a-fA-F]{40}$").matches(listed.contract)) return null
        val review = listed.contract.lowercase() to burn.cid
        val approved = reviewsOver[review] ?: run {
            val data = rpc.call("contract_batchReadData", JSONArray().put(listed.contract).put(adReviewKeys())) as? JSONArray
                ?: return null
            val result = adReview(data, burn.cid) ?: return null
            if (result.over) reviewsOver[review] = result.approved
            result.approved
        }
        if (!approved) return null
        val content = ipfs(burn.cid)?.let(::decodeAd) ?: return null
        return ChainAd(burn.cid, burn.address, burn.amount, content)
    }

    /** The content of [cid] from the phone's files, else through the node; null when not found. */
    private fun ipfs(cid: String): ByteArray? {
        // The cid names a file: only letters and digits.
        if (ipfsCid("ipfs://$cid") == null) return null
        return IpfsStore.cached(context, cid) ?: IpfsStore.fetch(context, cid)
    }
}
