package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class NewVotingTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val filled = NewVotingForm(title = " Best fruit? ", desc = "Pick one", options = listOf("Apple ", "", " Pear"))

    private fun arg(args: JSONArray, index: Int): JSONObject? =
        (0 until args.length()).map { args.getJSONObject(it) }.firstOrNull { it.getInt("index") == index }

    @Test
    fun eachKindSetsTheDesktopsPresetAndKeepsTheTexts() {
        val fact = filled.withKind(VotingKind.Fact, now)
        assertEquals(filled.title, fact.title)
        assertEquals(filled.options, fact.options)
        assertFalse(fact.startNow)
        assertEquals(now + Duration.ofDays(7), fact.startTime)
        assertEquals(51, fact.winnerThreshold)
        assertEquals(5, fact.quorum)
        assertFalse(fact.freeVoting)
        assertEquals(BigDecimal.ZERO, fact.paymentArg)

        val poll = fact.withKind(VotingKind.Poll, now)
        assertTrue(poll.startNow)
        assertEquals(100, poll.winnerThreshold)
        assertEquals(1, poll.quorum)
        assertTrue(poll.freeVoting)
        assertEquals(BigDecimal.ZERO, poll.paymentArg)

        val decision = poll.withKind(VotingKind.Decision, now)
        assertFalse(decision.startNow)
        assertNull(decision.startTime)
        assertEquals(51, decision.winnerThreshold)
        assertEquals(5, decision.quorum)

        // Back to Fact: nothing of the Poll is left (no free voting), the settings start from the defaults.
        assertFalse(decision.withKind(VotingKind.Fact, now).freeVoting)
        val custom = poll.withKind(VotingKind.Custom, now)
        assertEquals(NewVotingForm(kind = VotingKind.Custom, title = filled.title, desc = filled.desc, options = filled.options), custom)
        assertEquals(4320L, custom.votingDuration)
        assertEquals(2160L, custom.countingDuration)
        assertEquals(66, custom.winnerThreshold)
    }

    @Test
    fun theFormSaysWhatKeepsItFromBeingPublished() {
        assertEquals(
            listOf("Write a title.", "Write a description.", "Write at least 2 options."),
            NewVotingForm().problems(),
        )
        assertEquals(emptyList<String>(), filled.problems())
        assertEquals(
            listOf("Options cannot hold links: put them in the description."),
            filled.copy(options = listOf("Apple", "see https://example.org")).problems(),
        )
        assertEquals(emptyList<String>(), filled.copy(options = listOf("Apple", "example.org has no scheme")).problems())
        assertEquals(listOf("Pick the start date, or start now."), filled.copy(startNow = false).problems())
        assertEquals(emptyList<String>(), filled.copy(startNow = false, startTime = now).problems())
        assertEquals(listOf("The committee needs at least 1 oracle."), filled.copy(committeeSize = 0).problems())
        assertEquals(listOf("The quorum is a percentage from 1 to 100."), filled.copy(quorum = 0).problems())
        assertEquals(listOf("The owner fee is a percentage from 0 to 100."), filled.copy(ownerFee = 101).problems())
        assertEquals(listOf("The refund address is not an address."), filled.copy(refundAddress = "0x1234").problems())
        assertEquals(emptyList<String>(), filled.copy(refundAddress = "0x" + "aB".repeat(20)).problems())
    }

    @Test
    fun theFactIsReadBackAsTheVotingsQuestion() {
        val fact = votingFact(factHex(filled))
        assertEquals("Best fruit?", fact.title)
        assertEquals("Pick one", fact.desc)
        // Blank options are left out; the others are numbered in order, trimmed.
        assertEquals(listOf(VotingOption(0, "Apple"), VotingOption(1, "Pear")), fact.options)
        assertTrue(factHex(filled).startsWith("0x"))
    }

    @Test
    fun theDeployHasTheDesktopsArguments() {
        val form = filled.copy(
            startNow = false, startTime = now + Duration.ofDays(1), votingDuration = 8640, countingDuration = 4320,
            committeeSize = 50, quorum = 5, winnerThreshold = 51, minPayment = BigDecimal("2.5"), ownerFee = 10,
            rewardsFund = BigDecimal("100"),
        )
        val args = deployArgs(form, now)
        assertEquals(10, args.length())
        assertEquals("hex", arg(args, 0)!!.getString("format"))
        assertEquals(factHex(form), arg(args, 0)!!.getString("value"))
        val expected = listOf(
            1 to ("uint64" to (now + Duration.ofDays(1)).epochSecond.toString()),
            2 to ("uint64" to "8640"),
            3 to ("uint64" to "4320"),
            4 to ("byte" to "51"),
            5 to ("byte" to "5"),
            6 to ("uint64" to "50"),
            7 to ("dna" to "2.5"),
            8 to ("byte" to "10"),
            9 to ("dna" to "100"),
        )
        expected.forEach { (index, formatValue) ->
            assertEquals("arg $index", formatValue, arg(args, index)!!.let { it.getString("format") to it.getString("value") })
        }
        // Start now: the start is now; free voting: no payment; a refund address is the last argument.
        val now2 = deployArgs(form.copy(startNow = true, freeVoting = true, refundAddress = "0x" + "11".repeat(20)), now)
        assertEquals(now.epochSecond.toString(), arg(now2, 1)!!.getString("value"))
        assertEquals("0", arg(now2, 7)!!.getString("value"))
        assertEquals("hex" to "0x" + "11".repeat(20), arg(now2, 10)!!.let { it.getString("format") to it.getString("value") })
        // No payment typed: 0, free to vote (a payment left out reads as 0 too, devnet 2026-10-10).
        assertEquals("0", arg(deployArgs(form.copy(minPayment = BigDecimal.ZERO), now), 7)!!.getString("value"))
    }

    @Test
    fun theDeployStakesAndPaysAtMostTheEstimate() {
        val args = deployArgs(filled, now)
        val estimate = deployParams("0xme", args, BigDecimal("0.003"), null).getJSONObject(0)
        assertEquals("0x02", estimate.getString("codeHash"))
        assertEquals("0.003", estimate.getString("amount"))
        assertEquals("0xme", estimate.getString("from"))
        assertFalse(estimate.has("maxFee"))
        assertEquals("0.42", deployParams("0xme", args, BigDecimal("0.003"), BigDecimal("0.42")).getJSONObject(0).getString("maxFee"))
    }

    @Test
    fun theStakeIsThreeMillionGas() {
        // bcn_feePerGas in the smallest unit: 1e9 per gas, 3e6 gas = 3e15 = 0.003 iDNA.
        assertEquals(BigDecimal("0.003"), votingStake(BigDecimal("1000000000")))
        assertEquals(0, BigDecimal("0.037037036703").compareTo(votingStake(BigDecimal("12345678901"))))
    }

    @Test
    fun theOwnerDepositIsTheContracts() {
        assertEquals(0, BigDecimal(5000).compareTo(ownerDeposit(1, 1)))
        assertEquals(0, BigDecimal(250).compareTo(ownerDeposit(2000, 100)))
        // The committee is at most the network; each oracle's share is whole units of the smallest unit.
        assertEquals(0, BigDecimal("4999.999999999999999998").compareTo(ownerDeposit(3, 100)))
        assertEquals(0, BigDecimal(5000).compareTo(ownerDeposit(0, 1)))
    }

    @Test
    fun durationsReadAsTheDesktops() {
        assertEquals(listOf("12 h", "1 day", "2 days", "5 days", "1 week"), VOTING_DURATIONS.map(::durationText))
        assertEquals("5 min", durationText(15))
        assertEquals("33 min", durationText(100))
        assertEquals("2 weeks", durationText(336 * BLOCKS_PER_HOUR))
    }

    @Test
    fun anEstimateGivesItsCostOrItsError() {
        val cost = receiptCost(JSONObject("""{"success": true, "txFee": "0.1", "gasCost": "0.0234567"}""")).getOrThrow()
        assertEquals(0, BigDecimal("0.1234567").compareTo(cost.fee))
        assertEquals(BigDecimal("0.246914"), cost.maxFee)
        assertEquals("starting is locked", receiptCost(JSONObject("""{"success": false, "error": "starting is locked"}""")).exceptionOrNull()!!.message)
    }

    @Test
    fun theConfirmationSaysTheStakeTheLaunchAndTheFee() {
        val plan = PublishPlan(JSONArray(), BigDecimal("0.003"), TxCost(BigDecimal("0.123"), BigDecimal("0.246")), "0xc0", BigDecimal("260"))
        val atOnce = publishConfirmText(filled.copy(title = "Best fruit?"), plan) { "DATE" }
        assertEquals(
            "Publish “Best fruit?”? It stakes 0.003 iDNA, which comes back to you when the voting is terminated." +
                "\n\nThen a second transaction launches it, with its own fee: 260 iDNA to the voting (the owner deposit and the rewards fund)." +
                "\n\nSecret voting 1 day, then counting 12 h.\n\nFee: about 0.13 iDNA (at most 0.25).",
            atOnce,
        )
        val later = publishConfirmText(filled.copy(startNow = false, startTime = now, countingDuration = 15), plan) { "DATE" }
        assertTrue(later, later.contains("It waits, not started, until it is launched from its page, from DATE: launching sends 260 iDNA"))
        // The contract raises a counting shorter than 100 blocks.
        assertTrue(later, later.contains("then counting 33 min."))
        assertTrue(launchesAtOnce(filled))
        assertFalse(launchesAtOnce(filled.copy(refundAddress = "0x" + "11".repeat(20))))
    }

    @Test
    fun theFoldedSettingsReadOnOneLine() {
        assertEquals(
            "counting 12 h · committee 100 · quorum 5% · majority 51% · 2 iDNA per vote · owner fee 0% · rewards fund 0 iDNA",
            advancedSummary(NewVotingForm().withKind(VotingKind.Fact, now).copy(minPayment = BigDecimal(2))),
        )
        assertTrue(advancedSummary(NewVotingForm().withKind(VotingKind.Poll, now)).contains("· free to vote ·"))
        assertTrue(advancedSummary(NewVotingForm().withKind(VotingKind.Fact, now)).contains("· free to vote ·"))
    }
}
