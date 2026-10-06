package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class HistoryTest {
    // As dna_identityHistory answers: the current epoch first, then the past ones newest first.
    private val history = JSONObject(
        """
        {"address": "0x1", "own": true, "epoch": 230, "ceremoniesComplete": true,
         "epochs": [
          {"epoch": 230, "ceremony": null, "validated": null, "summary": null,
           "mining": {"proposedBlocks": 1, "proposerReward": "2", "committeeBlocks": 50, "committeeReward": "5",
                      "penaltyBurnt": "0"}},
          {"epoch": 229, "ceremony": {"height": 300, "time": 1791128034}, "validated": true,
           "summary": {"epoch": 229, "validationFailed": false, "participated": true, "prevState": "Verified",
                       "state": "Human", "approved": true, "missed": false, "penalized": false, "madeFlips": 3,
                       "shortAnswers": {"point": 5.5, "flipsCount": 6}, "longAnswers": {"point": 20, "flipsCount": 24},
                       "shortAnswersCount": 6, "longAnswersCount": 30,
                       "rewards": {"staking": {"earned": "1.5", "missed": "0"},
                                   "flips": {"earned": "2.25", "missed": null},
                                   "invitations": {"earned": "0", "missed": "3", "reason": "not_validated"}},
                       "delegateeReward": {"address": "0xpool", "amount": "0.5"}},
           "transactions": {"sent": 4, "flips": 3, "shortAnswers": true, "longAnswers": true},
           "mining": {"proposedBlocks": 2, "proposerReward": "4.5", "committeeBlocks": 100,
                      "committeeReward": "10", "penaltyBurnt": "0.5"}},
          {"epoch": 228, "ceremony": {"height": 200, "time": 1790696042}, "validated": null, "summary": null,
           "transactions": {"sent": 3, "flips": 0, "shortAnswers": true, "longAnswers": true}},
          {"epoch": 227, "ceremony": {"height": 100, "time": 1}, "validated": true},
          {"epoch": 226, "ceremony": {"height": 50, "time": 1}, "validated": false},
          {"epoch": 225, "ceremony": {"height": 40, "time": 1}, "validated": null}
         ],
         "scores": [{"epoch": null, "shortPoints": 3, "shortFlips": 4},
                    {"epoch": 228, "shortPoints": 4, "shortFlips": 4},
                    {"epoch": 229, "shortPoints": 5.5, "shortFlips": 6}]}
        """,
    )

    @Test
    fun listsPastCeremoniesDownToTheFirstWithActivity() {
        val parsed = parseIdentityHistory(history)
        assertEquals(listOf(229, 228, 227), parsed.ceremonies.map { it.epoch })
        val last = parsed.ceremonies[0]
        assertEquals(CeremonyOutcome.Validated, last.outcome)
        assertEquals("Verified", last.prevState)
        assertEquals("Human", last.state)
        assertEquals(5.5 to 6, last.score)
        assertEquals(3, last.flips)
        assertEquals(1791128034L, last.time)
        // No summary: the score with that epoch, the answers sent, the result unknown.
        val before = parsed.ceremonies[1]
        assertEquals(CeremonyOutcome.Unknown, before.outcome)
        assertEquals(4.0 to 4, before.score)
        assertTrue(before.longAnswersSent)
        assertNull(before.report)
        assertEquals(CeremonyOutcome.Validated, parsed.ceremonies[2].outcome)
        assertNull(parsed.ceremonies[2].score)
    }

    @Test
    fun readsTheValidationReport() {
        val report = parseIdentityHistory(history).ceremonies[0].report!!
        assertEquals(BigDecimal("3.75"), report.earned)
        assertEquals(BigDecimal("3"), report.missedRewards)
        assertEquals(listOf("Staking", "Flips", "Invitations"), report.rewards.map { it.kind })
        assertNull(report.rewards[1].missed)
        assertEquals("not validated", missedReason(report.rewards[2].reason))
        assertEquals(30, report.longAnswers)
        assertEquals("0xpool", report.delegatee)
        assertEquals(BigDecimal("0.5"), report.delegated)
    }

    @Test
    fun addsUpTheMiningOfEveryEpoch() {
        val mining = parseIdentityHistory(history).mining
        assertEquals(listOf(230, 229), mining.map { it.epoch })
        assertEquals(BigDecimal("14.0"), mining[1].total)
        assertEquals(BigDecimal("7"), mining[0].total)
    }

    @Test
    fun anAddressWithoutActivityHasNoCeremonies() {
        val parsed = parseIdentityHistory(
            JSONObject(
                """{"address": "0x2", "own": false, "epoch": 230, "ceremoniesComplete": false,
                 "epochs": [{"epoch": 230},
                  {"epoch": 229, "ceremony": {"height": 1, "time": 1}, "validated": false},
                  {"epoch": 228, "ceremony": {"height": 1, "time": 1},
                   "summary": {"participated": false, "prevState": "Undefined", "state": "Undefined"}}],
                 "scores": []}""",
            ),
        )
        assertTrue(parsed.ceremonies.isEmpty())
        assertFalse(parsed.ceremoniesComplete)
        // Before any ceremony there is nothing to find.
        assertTrue(parseIdentityHistory(JSONObject("""{"epoch": 0, "epochs": [{"epoch": 0}]}""")).ceremoniesComplete)
    }

    @Test
    fun readsTransactionPages() {
        val (txs, token) = parseTransactions(
            JSONObject(
                """{"transactions": [
                  {"hash": "0xa", "type": "send", "from": "0xAB", "to": "0xcd", "amount": "2.5", "usedFee": "0.01",
                   "epoch": 229, "timestamp": 100, "payload": "0x"},
                  {"hash": "0xb", "type": "deployContract", "from": "0xab", "to": null, "amount": "0",
                   "usedFee": "1", "epoch": 229, "timestamp": 90, "payload": "0x"}],
                 "token": "0x0102"}""",
            ),
        )
        assertEquals("0x0102", token)
        assertEquals(BigDecimal("-2.5"), txs[0].signedAmount("0xab"))
        assertEquals(BigDecimal("2.5"), txs[0].signedAmount("0xcd"))
        assertEquals("0xcd", txs[0].counterParty("0xab"))
        assertNull(txs[1].to)
        assertEquals("Contract deployed", txTypeName(txs[1].type, txs[1].payload))
        assertEquals("Mining status Off", txTypeName("online", "0x"))
        assertEquals(emptyList<HistoryTx>() to null, parseTransactions(JSONObject("""{"transactions": null, "token": null}""")))
    }
}
