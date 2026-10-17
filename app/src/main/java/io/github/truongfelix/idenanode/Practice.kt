package io.github.truongfelix.idenanode

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A practice flip: the flip, its right story and why it should be reported (0: a good flip). */
class PracticeFlip(val flip: Flip, val answer: Choice, val reportReason: Int)

/**
 * The flips of a practice validation: the sample validation of the Idena web app (6 short-session and
 * 9 long-session flips with their right answers), bundled in `assets/practice-flips.json`. Each practice
 * shuffles the flips and swaps their left and right stories at random.
 */
class Practice(val short: List<PracticeFlip>, val long: List<PracticeFlip>) {
    companion object {
        fun load(context: Context, random: Random = Random.Default): Practice {
            val json = JSONObject(context.assets.open("practice-flips.json").bufferedReader().use { it.readText() })
            fun flips(name: String): List<PracticeFlip> {
                val array = json.getJSONArray(name)
                return (0 until array.length()).map { flip(array.getJSONObject(it), random) }.shuffled(random)
            }
            return Practice(flips("short"), flips("long"))
        }

        private fun flip(json: JSONObject, random: Random): PracticeFlip {
            val images = json.getJSONArray("images")
            val orders = json.getJSONArray("orders")
            val keywords = json.getJSONArray("keywords")
            val first = ints(orders.getJSONArray(0))
            val second = ints(orders.getJSONArray(1))
            val firstIsRight = json.getInt("answer") == 1
            val swap = random.nextBoolean()
            val flip = Flip(
                hash = json.getString("hash"),
                images = (0 until images.length()).map { Base64.decode(images.getString(it), Base64.DEFAULT) },
                leftOrder = if (swap) second else first,
                rightOrder = if (swap) first else second,
                keywords = (0 until keywords.length()).map {
                    val keyword = keywords.getJSONObject(it)
                    Keyword(keyword.getString("name"), keyword.optString("desc"))
                },
            )
            val answer = if (firstIsRight != swap) Choice.Left else Choice.Right
            return PracticeFlip(flip, answer, json.getInt("reportReason"))
        }

        private fun ints(array: JSONArray) = (0 until array.length()).map { array.getInt(it) }
    }
}

/** Why a flip should be reported, as the Idena web app explains it (idena-web GetReasonDesc). */
fun reportReasonText(reason: Int): String = when (reason) {
    1 -> t(R.string.flip_reason_keywords)
    2 -> t(R.string.flip_reason_order)
    3 -> t(R.string.flip_reason_enumerated)
    4 -> t(R.string.flip_reason_text)
    5 -> t(R.string.flip_reason_inappropriate)
    6 -> t(R.string.flip_reason_unrelated)
    7 -> t(R.string.flip_reason_waking_up)
    8 -> t(R.string.flip_reason_thumbs)
    9 -> t(R.string.flip_reason_inserted)
    else -> t(R.string.flip_reason_good)
}
