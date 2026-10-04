package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WriteBufferTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    @Test
    fun theFourSizesWithIdenasByDefault() {
        assertEquals(listOf(4, 16, 32, 64), WriteBuffer.entries.map { it.mib })
        assertEquals(WriteBuffer.MiB4, WriteBuffer.DEFAULT)
        assertEquals(listOf("Default", "16 MiB", "32 MiB", "64 MiB"), WriteBuffer.entries.map { it.label })
    }

    @Test
    fun twoDatabasesSetOnTheirOwn() {
        assertEquals(listOf(NodeDatabase.Chain, NodeDatabase.Ipfs), NodeDatabase.entries)
        assertEquals("Chain database buffer", NodeDatabase.Chain.title)
        assertEquals("IPFS database buffer", NodeDatabase.Ipfs.title)
    }

    @Test
    fun aFileHoldsAnOfferedSizeElseTheDefault() {
        assertEquals(WriteBuffer.MiB4, WriteBuffer.parse("4"))
        assertEquals(WriteBuffer.MiB32, WriteBuffer.parse("32"))
        assertEquals(WriteBuffer.MiB64, WriteBuffer.parse(" 64\n"))
        assertEquals(WriteBuffer.DEFAULT, WriteBuffer.parse(null))
        assertEquals(WriteBuffer.DEFAULT, WriteBuffer.parse(""))
        assertEquals(WriteBuffer.DEFAULT, WriteBuffer.parse("8"))
        assertEquals(WriteBuffer.DEFAULT, WriteBuffer.parse("MiB32"))
    }

    @Test
    fun pendingOnlyWhileTheNodeRunsWithAnotherSize() {
        assertTrue(writeBufferPending(syncEnabled = true, running = WriteBuffer.MiB4, chosen = WriteBuffer.MiB16))
        assertFalse(writeBufferPending(syncEnabled = true, running = WriteBuffer.MiB16, chosen = WriteBuffer.MiB16))
        // Stopped: the next start takes the choice anyway.
        assertFalse(writeBufferPending(syncEnabled = false, running = WriteBuffer.MiB4, chosen = WriteBuffer.MiB16))
        // Never started by this app: nothing to compare.
        assertFalse(writeBufferPending(syncEnabled = true, running = null, chosen = WriteBuffer.MiB16))
    }

    @Test
    fun theNodeConfigCarriesBothWriteBuffers() {
        val config = JSONObject(NodeSettings.nodeConfig("abc123", WriteBuffer.MiB32, WriteBuffer.MiB16))
        assertEquals(32, config.getJSONObject("Database").getInt("WriteBufferMiB"))
        assertEquals(16, config.getJSONObject("IpfsConf").getInt("DatastoreWriteBufferMiB"))
        val rpc = config.getJSONObject("RPC")
        assertEquals("127.0.0.1", rpc.getString("HTTPHost"))
        assertEquals(NodeSettings.RPC_PORT, rpc.getInt("HTTPPort"))
        assertEquals("abc123", rpc.getString("APIKey"))
        assertEquals(0, rpc.getJSONArray("HTTPCors").length())
        val ipfs = config.getJSONObject("IpfsConf")
        assertEquals("", ipfs.getString("Profile"))
        assertTrue(ipfs.getBoolean("StaticPort"))
    }

    @Test
    fun noRestartDuringTheValidation() {
        val risk = restartRisk(now, now.minus(Duration.ofMinutes(10)), "ShortSession")!!
        assertFalse(risk.canRestart)
        assertFalse(restartRisk(now, now.plus(Duration.ofMinutes(4)), "FlipLottery")!!.canRestart)
    }

    @Test
    fun aWarningWithinThreeHoursBeforeTheValidation() {
        val risk = restartRisk(now, now.plus(Duration.ofMinutes(150)), "None")!!
        assertTrue(risk.canRestart)
        assertEquals("The validation starts in 2 h 30 min: the node may still be looking for peers then.", risk.message)
        assertTrue(restartRisk(now, now.plus(Duration.ofMinutes(179)), "None")!!.canRestart)
    }

    @Test
    fun noWarningOtherwise() {
        assertNull(restartRisk(now, now.plus(Duration.ofHours(3)), "None"))
        assertNull(restartRisk(now, now.plus(Duration.ofDays(4)), "None"))
        // The validation time of an epoch that has passed, or none known.
        assertNull(restartRisk(now, now.minus(Duration.ofHours(1)), "None"))
        assertNull(restartRisk(now, null, "None"))
        assertNull(restartRisk(now, null, ""))
    }

    @Test
    fun theRamAsSold() {
        assertEquals(3, ramGb(2_893_000_000)) // Redmi Note 7, 3 GB
        assertEquals(4, ramGb(3_850_000_000))
        assertEquals(6, ramGb(5_780_000_000))
        assertEquals(8, ramGb(7_740_000_000))
        assertEquals(12, ramGb(11_600_000_000))
    }

    @Test
    fun theDefaultIsIdenasForBoth() {
        val config = JSONObject(NodeSettings.nodeConfig("abc123", WriteBuffer.DEFAULT, WriteBuffer.DEFAULT))
        assertEquals(4, config.getJSONObject("Database").getInt("WriteBufferMiB"))
        assertEquals(4, config.getJSONObject("IpfsConf").getInt("DatastoreWriteBufferMiB"))
    }
}
