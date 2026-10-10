package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class VotingActionsTest {
    private val fact = bytesToHex("""{"title":"Best fruit?","desc":"","options":[{"id":0,"value":"Apple"},{"id":1,"value":"Pear"}]}""".toByteArray())
    private val cost = TxCost(BigDecimal("0.123"), BigDecimal("0.246"))
    private val fee = "\n\nFee: about 0.13 iDNA (at most 0.25)."

    // As contract_oracleVoting answers: committee 10, quorum 20% (2 votes), deposit 250, rewards fund 10, holding 100.
    private fun voting(state: String, vararg fields: Pair<String, Any>): OracleVoting {
        val json = JSONObject(
            """
            {"contractAddress": "0xC0", "author": "0xAUTHOR", "balance": "100", "fact": "$fact", "voteProofsCount": 0,
             "votesCount": 0, "state": "$state", "quorum": 20, "committeeSize": 10, "winnerThreshold": 51,
             "ownerFee": 0, "isOracle": false, "epochWithoutGrowth": 0, "ownerDeposit": "250", "oracleRewardFund": "10",
             "startTime": "2026-10-17T10:00:00Z"}
            """,
        )
        fields.forEach { (name, value) -> json.put(name, value) }
        return parseVoting(json)
    }

    @Test
    fun thePageOffersWhatTheNodeAccepts() {
        val none = ActionChecks(finish = false, prolong = false, terminate = false)
        assertEquals(listOf(VotingAction.Launch, VotingAction.AddFunds), votingActions(voting(VotingState.PENDING), null))
        assertEquals(listOf(VotingAction.AddFunds), votingActions(voting(VotingState.OPEN), none))
        assertEquals(listOf(VotingAction.Prolong, VotingAction.AddFunds), votingActions(voting(VotingState.OPEN), none.copy(prolong = true)))
        // Finish, and then no Prolong (as the desktop).
        assertEquals(
            listOf(VotingAction.Finish, VotingAction.AddFunds),
            votingActions(voting(VotingState.COUNTING), none.copy(finish = true, prolong = true)),
        )
        assertEquals(
            listOf(VotingAction.Prolong, VotingAction.AddFunds, VotingAction.Terminate),
            votingActions(voting(VotingState.CAN_BE_PROLONGED), none.copy(prolong = true, terminate = true)),
        )
        // A finished voting: no Finish, no Add funds; Terminate when the node takes it.
        assertEquals(listOf(VotingAction.Terminate), votingActions(voting(VotingState.ARCHIVE), none.copy(finish = true, terminate = true)))
        assertEquals(emptyList<VotingAction>(), votingActions(voting(VotingState.ARCHIVE), none))
        assertEquals(emptyList<VotingAction>(), votingActions(voting(VotingState.TERMINATED), ActionChecks(true, true, true)))
    }

    @Test
    fun finishSaysRefundsForAFailedVoting() {
        val checks = ActionChecks(finish = true, prolong = false, terminate = false)
        assertEquals("Claim rewards", actionLabel(VotingAction.Finish, voting(VotingState.COUNTING), checks))
        val failed = voting(VotingState.COUNTING, "epochWithoutGrowth" to 3)
        assertEquals("Claim refunds", actionLabel(VotingAction.Finish, failed, checks))
        assertEquals("Claim rewards", actionLabel(VotingAction.Finish, voting(VotingState.COUNTING, "epochWithoutGrowth" to 3, "voteProofsCount" to 2), checks))
        assertFalse(votingFailed(failed, checks.copy(prolong = true)))
    }

    @Test
    fun launchSendsTheDepositAndTheFundLessTheBalance() {
        assertEquals(0, BigDecimal(160).compareTo(launchAmount(voting(VotingState.PENDING))))
        assertEquals(0, BigDecimal.ZERO.compareTo(launchAmount(voting(VotingState.PENDING, "balance" to "300"))))
        val bare = voting(VotingState.PENDING).copy(ownerDeposit = null, rewardsFund = null)
        assertEquals(0, BigDecimal.ZERO.compareTo(launchAmount(bare)))
    }

    @Test
    fun theCallsAreTheDesktops() {
        val estimate = callParams("0xme", "0xc0", "startVoting", BigDecimal(160), null).getJSONObject(0)
        assertEquals("startVoting", estimate.getString("method"))
        assertEquals("160", estimate.getString("amount"))
        assertEquals(0, estimate.getJSONArray("args").length())
        assertFalse(estimate.has("maxFee"))
        assertEquals("0.5", callParams("0xme", "0xc0", "startVoting", BigDecimal(160), BigDecimal("0.5")).getJSONObject(0).getString("maxFee"))
        val terminate = terminateParams("0xme", voting(VotingState.ARCHIVE), BigDecimal("0.5")).getJSONObject(0)
        assertEquals("0xc0", terminate.getString("contract"))
        assertEquals("0xauthor", terminate.getJSONArray("args").getJSONObject(0).getString("value"))
        assertEquals("0.5", terminate.getString("maxFee"))
        assertEquals(
            listOf("startVoting", "prolongVoting", "finishVoting", null, null),
            listOf(VotingAction.Launch, VotingAction.Prolong, VotingAction.Finish, VotingAction.AddFunds, VotingAction.Terminate).map(::actionMethod),
        )
    }

    @Test
    fun eachConfirmationSaysWhatTheContractDoesAndTheFee() {
        val pending = voting(VotingState.PENDING)
        assertEquals(
            "Launch “Best fruit?”? It sends 160 iDNA to the voting: the owner deposit and the rewards fund, less the " +
                "100 iDNA it holds. Then its committee is drawn and the secret voting starts.$fee",
            actionConfirmText(VotingAction.Launch, pending, "0xme", BigDecimal(160), cost),
        )
        assertEquals(
            "Launch “Best fruit?”? It sends 260 iDNA to the voting: the owner deposit and the rewards fund. " +
                "Then its committee is drawn and the secret voting starts.$fee",
            actionConfirmText(VotingAction.Launch, voting(VotingState.PENDING, "balance" to "0"), "0xme", BigDecimal(260), cost),
        )
        assertEquals(
            "Launch “Best fruit?”? It holds its owner deposit already (300 iDNA): nothing more is sent. " +
                "Then its committee is drawn and the secret voting starts.$fee",
            actionConfirmText(VotingAction.Launch, voting(VotingState.PENDING, "balance" to "300"), "0xme", BigDecimal.ZERO, cost),
        )
        assertEquals(
            "Prolong “Best fruit?”? The contract draws a new committee for it.$fee",
            actionConfirmText(VotingAction.Prolong, voting(VotingState.CAN_BE_PROLONGED), "0xme", BigDecimal.ZERO, cost),
        )
        assertEquals(
            "Finish “Best fruit?” and pay out? The contract counts the votes and pays the oracles who voted for the " +
                "result (all who voted, when no option wins), sends the owner's share (the deposit back and the owner " +
                "fee) to 0xauth…thor, and burns what is left.$fee",
            actionConfirmText(VotingAction.Finish, voting(VotingState.COUNTING), "0xme", BigDecimal.ZERO, cost),
        )
        val mine = voting(VotingState.COUNTING, "refundRecipient" to "0xME")
        assertEquals(true, actionConfirmText(VotingAction.Finish, mine, "0xme", BigDecimal.ZERO, cost).contains("fee) to you, and burns"))
        assertEquals(
            "Terminate “Best fruit?”? It never started: the contract sends the 100 iDNA it holds to you, and its stake " +
                "back to its author.$fee",
            actionConfirmText(VotingAction.Terminate, pending, "0xme", BigDecimal.ZERO, cost),
        )
        assertEquals(
            "Terminate “Best fruit?”? The contract removes it and sends its stake back to its author.$fee",
            actionConfirmText(VotingAction.Terminate, voting(VotingState.ARCHIVE, "balance" to "0"), "0xme", BigDecimal.ZERO, cost),
        )
        assertEquals(
            "Terminate “Best fruit?”? The contract closes it: the owner's share (the deposit back and the owner fee) " +
                "goes to 0xauth…thor, the rest of its 100 iDNA to the oracles who voted (to you, when nobody voted), and " +
                "its stake back to its author.$fee",
            actionConfirmText(VotingAction.Terminate, voting(VotingState.COUNTING), "0xme", BigDecimal.ZERO, cost),
        )
        assertEquals(
            "Send 5 iDNA to “Best fruit?”? It joins the voting's funds, paid out by its rules; it cannot be taken back." +
                "\n\nFee: about 0.13 iDNA.",
            actionConfirmText(VotingAction.AddFunds, pending, "0xme", BigDecimal(5), cost),
        )
    }

    @Test
    fun theContractsRefusalsReadAsSentences() {
        val date: (Instant) -> String = { "DATE" }
        val pending = voting(VotingState.PENDING)
        assertEquals("It cannot be launched before its start, DATE.", actionErrorText("starting is locked", pending, date))
        assertEquals("The voting holds less than its owner deposit: send more.", actionErrorText("contract balance is less than minimal deposit", pending, date))
        assertEquals("It cannot be terminated yet.", actionErrorText("voting can not be terminated", pending, date))
        val ended = voting(VotingState.ARCHIVE, "estimatedTerminationTime" to "2026-10-20T10:00:00Z")
        assertEquals("It cannot be terminated yet: from DATE.", actionErrorText("voting can not be terminated", ended, date))
        assertEquals(
            "It cannot be prolonged anymore: three prolongations brought no more votes.",
            actionErrorText("voting can not be prolonged anymore", ended, date),
        )
        assertEquals("The node refused it: insufficient funds.", actionErrorText("insufficient funds", ended, date))
        assertEquals("The node refused it.", actionErrorText(null, ended, date))
    }
}
