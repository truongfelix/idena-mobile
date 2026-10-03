package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * The flips of the identity this epoch. The node lists them as hashes only (dna_identity flips) and
 * cannot show their pictures outside a validation, so the app keeps a copy of each flip it sends
 * ([MadeFlip], files/flips/made/): the four protected frames in story order, the words and the shuffled
 * order. A flip made in another app shows without pictures.
 * Deleting (flip_delete, DeleteFlipTx, a fee) frees its keyword pair and lowers the flips made; it is
 * refused from the flip lottery on (validateDeleteFlipTx).
 */

/** DeleteFlipTx, for the fee estimate. */
const val DELETE_FLIP_TX = 0xE

/**
 * A flip this phone sent in [epoch]: its hash ([cid]), its transaction, its keyword pair and words (indexes
 * into the keyword list), and [shuffled], the story positions in the order of the shuffled story.
 */
data class MadeFlip(
    val cid: String,
    val txHash: String,
    val pairId: Int,
    val words: List<Int>,
    val shuffled: List<Int>,
    val epoch: Int,
    val sentAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("cid", cid).put("txHash", txHash).put("pairId", pairId).put("words", JSONArray(words))
        .put("shuffled", JSONArray(shuffled)).put("epoch", epoch).put("sentAt", sentAt)

    /** The flip as the validation shows it: the story on the left, the shuffled story on the right. */
    fun asFlip(frames: List<ByteArray>, keywords: List<Keyword>): Flip =
        Flip(cid, frames, (0 until FLIP_FRAMES).toList(), shuffled, words.mapNotNull { keywords.getOrNull(it) })

    companion object {
        fun fromJson(json: JSONObject): MadeFlip? = runCatching {
            val words = json.getJSONArray("words").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val shuffled = json.getJSONArray("shuffled").let { a -> (0 until a.length()).map { a.getInt(it) } }
            require(shuffled.sorted() == (0 until FLIP_FRAMES).toList())
            MadeFlip(
                json.getString("cid"), json.getString("txHash"), json.getInt("pairId"), words, shuffled,
                json.getInt("epoch"), json.getLong("sentAt"),
            )
        }.getOrNull()
    }
}

/** The identity's flips (dna_identity flips): their hashes. */
fun identityFlips(identity: JSONObject): List<String> {
    val array = identity.optJSONArray("flips") ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
}

/** What the list says of a flip. */
enum class MyFlipState { Waiting, Published, Deleting }

/** A row of the list: the flip's hash, this phone's copy (null for a flip made in another app), its state. */
data class MyFlipRow(val cid: String, val made: MadeFlip?, val state: MyFlipState)

/**
 * The list: the flips the node counts (published), in its order, with this phone's copies; then the
 * flips sent from this phone and not in a block yet, for [SENT_FLIP_TIMEOUT_MS] at most (one never
 * counted, or deleted from another app, leaves the list). [deleting] are the flips whose delete is sent.
 */
fun myFlipRows(published: List<String>, made: List<MadeFlip>, deleting: Set<String>, now: Long): List<MyFlipRow> {
    val byCid = made.associateBy { it.cid }
    val rows = published.map { MyFlipRow(it, byCid[it], if (it in deleting) MyFlipState.Deleting else MyFlipState.Published) }
    val waiting = made.filter { it.cid !in published && it.cid !in deleting && now - it.sentAt <= SENT_FLIP_TIMEOUT_MS }
        .sortedBy { it.sentAt }
        .map { MyFlipRow(it.cid, it, MyFlipState.Waiting) }
    return rows + waiting
}

/** Whether the node would delete a flip now: published, outside the validation. */
fun canDeleteFlip(row: MyFlipRow, period: String): Boolean = row.state == MyFlipState.Published && period == "None"

/** The payload of a DeleteFlipTx (ProtoDeleteFlipAttachment: bytes cid = 1; [cidBytes]), for the fee estimate. */
fun deleteFlipPayload(cid: String): ByteArray {
    val bytes = cidBytes(cid)
    val out = java.io.ByteArrayOutputStream()
    out.write(0x0A)
    var length = bytes.size
    while (length >= 0x80) {
        out.write((length and 0x7F) or 0x80)
        length = length ushr 7
    }
    out.write(length)
    out.write(bytes)
    return out.toByteArray()
}
