package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class KeccakTest {
    /** keccak256-noble.json: hashes written by @noble/hashes' keccak_256 (sizes around the 136-byte rate, a message's JSON). */
    @Test
    fun matchesNobleHashes() {
        val text = javaClass.classLoader!!.getResourceAsStream("keccak256-noble.json").bufferedReader().use { it.readText() }
        val vectors = JSONArray(text)
        assertEquals(7, vectors.length())
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            assertEquals("input of ${v.getString("text").length} characters", v.getString("keccak256"), keccak256Hex(v.getString("text")))
        }
    }
}
