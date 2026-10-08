package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class OracleVotingsTest {
    private fun factHex(json: String) = bytesToHex(json.toByteArray(Charsets.UTF_8))

    private val fact = factHex(
        """{"title":"Best fruit?","desc":"Pick one","options":[{"id":0,"value":"Apple"},{"id":1,"value":"Pear"}]}""",
    )

    // As contract_oracleVoting answers (stats/oraclevotings Voting): decimals as strings, times in RFC 3339.
    private fun votingJson(state: String = "Open", extra: String = "") = JSONObject(
        """
        {"contractAddress": "0xC0FFEE", "author": "0xAUTHOR", "balance": "120.5", "stake": "30000",
         "fact": "$fact", "ballotsCount": 4, "secretVotesCount": 3, "votesCount": 1,
         "votes": [{"option": 1, "count": 1, "allCount": 1}], "state": "$state",
         "createTime": "2026-10-01T10:00:00Z", "startTime": "2026-10-01T10:00:20Z",
         "estimatedVotingFinishTime": "2026-10-10T10:00:00Z", "estimatedPublicVotingFinishTime": "2026-10-11T10:00:00Z",
         "estimatedOracleReward": "12.25", "estimatedMaxOracleReward": "40", "estimatedTotalReward": "100",
         "minPayment": "5", "quorum": 20, "committeeSize": 7, "votingDuration": 4320, "publicVotingDuration": 4320,
         "winnerThreshold": 66, "ownerFee": 10, "isOracle": true, "epochWithoutGrowth": 0, "hash": "0x01"$extra}
        """,
    )

    @Test
    fun aVotingIsReadWithItsFields() {
        val voting = parseVoting(votingJson())
        assertEquals("0xc0ffee", voting.contract)
        assertEquals("0xauthor", voting.author)
        assertEquals("Best fruit?", voting.fact.title)
        assertEquals(listOf(VotingOption(0, "Apple"), VotingOption(1, "Pear")), voting.fact.options)
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), voting.votingEnd)
        assertEquals(Instant.parse("2026-10-11T10:00:00Z"), voting.countingEnd)
        assertEquals(BigDecimal("5"), voting.minPayment)
        assertEquals(BigDecimal("12.25"), voting.oracleReward)
        assertEquals(4L, voting.votesSent)
        assertEquals(1L, voting.votes)
        assertEquals(mapOf(1 to 1L), voting.optionVotes)
        assertNull(voting.result)
        assertTrue(voting.canVote)
    }

    @Test
    fun theVotesSentAreReadUnderBothNames() {
        val indexerName = votingJson().apply { remove("ballotsCount") }.put("voteProofsCount", 6)
        assertEquals(6L, parseVoting(indexerName).votesSent)
        assertEquals(4L, parseVoting(votingJson()).votesSent)
    }

    @Test
    fun aClosedVotingHasItsTimesAndResult() {
        val json = votingJson("Archive", """, "votingFinishTime": "2026-10-10T10:00:05Z", "result": 1, "totalReward": "80"""")
        listOf("estimatedVotingFinishTime", "estimatedPublicVotingFinishTime", "estimatedTotalReward").forEach { json.remove(it) }
        val voting = parseVoting(json)
        assertEquals(Instant.parse("2026-10-10T10:00:05Z"), voting.votingEnd)
        assertNull(voting.countingEnd)
        assertEquals(1, voting.result)
        assertEquals(BigDecimal("80"), voting.totalReward)
        assertFalse(voting.canVote)
    }

    @Test
    fun onlyAnOpenVotingOfTheCommitteeCanBeVotedIn() {
        assertFalse(parseVoting(votingJson("Voted")).canVote)
        assertFalse(parseVoting(votingJson().put("isOracle", false)).canVote)
        assertFalse(parseVoting(votingJson().put("fact", factHex("""{"title":"No options"}"""))).canVote)
    }

    @Test
    fun theFactKeepsOnlyOptionsThatCanBeVotedFor() {
        val parsed = votingFact(
            factHex(
                """{"title":"T","options":[{"id":"2","value":"two"},{"id":3.0,"value":"three"},{"id":256,"value":"big"},
                   {"id":-1,"value":"neg"},{"id":"x","value":"letter"},{"id":4,"value":5},{"value":"no id"},"plain"]}""",
            ),
        )
        assertEquals(listOf(VotingOption(2, "two"), VotingOption(3, "three")), parsed.options)
    }

    @Test
    fun aFactThatIsNotAnObjectGivesAnUntitledVoting() {
        val empty = VotingFact("", "", emptyList())
        assertEquals(empty, votingFact("0xzz"))
        assertEquals(empty, votingFact(factHex("[1,2]")))
        assertEquals(empty, votingFact(""))
        assertEquals(VotingFact("", "", emptyList()), votingFact(factHex("""{"title":7,"desc":["x"]}""")))
        assertEquals("Untitled voting", votingTitle(parseVoting(votingJson().put("fact", factHex("{}")))))
    }

    @Test
    fun anEmptyListHasNoResult() {
        val page = parseVotingPage(JSONObject("{}"))
        assertTrue(page.votings.isEmpty())
        assertNull(page.next)
        assertNull(toVoteText(page))
        assertNull(toVoteText(parseVotingPage(null)))
    }

    @Test
    fun aListPageAndItsToken() {
        val page = parseVotingPage(JSONObject().put("result", JSONArray().put(votingJson()).put(votingJson())).put("continuationToken", "abc"))
        assertEquals(2, page.votings.size)
        assertEquals("abc", page.next)
        assertEquals("2+ to vote", toVoteText(page))
        assertEquals("1 to vote", toVoteText(VotingPage(listOf(parseVoting(votingJson())), null)))
    }

    @Test
    fun theListParametersFollowTheTab() {
        val toVote = votingListParams(VotingTab.ToVote, "0xme", null).getJSONObject(0)
        assertEquals("0xme", toVote.getString("oracle"))
        assertFalse(toVote.getBoolean("all"))
        assertEquals("""["Open"]""", toVote.getJSONArray("states").toString())
        assertEquals(VOTINGS_PER_PAGE, toVote.getInt("limit"))
        assertFalse(toVote.has("continuationToken"))
        assertFalse(toVote.has("sortBy"))
        val all = votingListParams(VotingTab.All, "0xme", "next").getJSONObject(0)
        assertTrue(all.getBoolean("all"))
        assertEquals(VotingState.ALL.size, all.getJSONArray("states").length())
        assertEquals("next", all.getString("continuationToken"))
    }

    @Test
    fun theListLineSaysWhatComesNext() {
        val now = Instant.parse("2026-10-08T10:00:00Z")
        assertEquals("Open · secret voting ends in 2 days · about 12.25 iDNA per oracle", votingSummary(parseVoting(votingJson()), now))
        assertEquals("Voted · votes open in 2 days", votingSummary(parseVoting(votingJson("Voted")), now))
        assertEquals("Counting · ends in 3 days", votingSummary(parseVoting(votingJson("Counting")), now))
        assertEquals("Finished · result: Pear", votingSummary(parseVoting(votingJson("Archive", """, "result": 1""")), now))
        assertEquals("Terminated", votingSummary(parseVoting(votingJson("Terminated")), now))
    }

    @Test
    fun theQuorumIsAShareOfTheCommitteeRoundedUp() {
        assertEquals(2L, parseVoting(votingJson()).quorumVotes)
        assertEquals(20L, parseVoting(votingJson().put("committeeSize", 100)).quorumVotes)
        assertEquals(1L, parseVoting(votingJson().put("committeeSize", 0)).quorumVotes)
    }

    /** The arguments of a call: index, format and value of each. */
    private fun args(call: JSONObject): List<Triple<Int, String, String>> {
        val list = call.getJSONArray("args")
        return (0 until list.length()).map { list.getJSONObject(it).let { arg -> Triple(arg.getInt("index"), arg.getString("format"), arg.getString("value")) } }
    }

    @Test
    fun theVoteCallsAreTheDesktops() {
        assertEquals("salt-0xc0ffee-230", saltMessage("0xc0ffee", 230))
        val readonly = readonlyCallParams("0xc", "voteHash", "hex", contractArg(0, "byte", "1"), contractArg(1, "hex", "0xsalt")).getJSONObject(0)
        assertEquals("voteHash", readonly.getString("method"))
        assertEquals("hex", readonly.getString("format"))
        assertEquals(listOf(Triple(0, "byte", "1"), Triple(1, "hex", "0xsalt")), args(readonly))
        assertEquals(emptyList<Triple<Int, String, String>>(), args(readonlyCallParams("0xc", "voteBlock", "uint64").getJSONObject(0)))

        val estimate = secretVoteParams("0xme", "0xc", "0xhash", BigDecimal("5"), null).getJSONObject(0)
        assertEquals("sendVoteProof", estimate.getString("method"))
        assertEquals("5", estimate.getString("amount"))
        assertFalse(estimate.has("maxFee"))
        assertFalse(estimate.has("broadcastBlock"))
        assertEquals(listOf(Triple(0, "hex", "0xhash")), args(estimate))
        assertEquals("0.2", secretVoteParams("0xme", "0xc", "0xhash", BigDecimal("5"), BigDecimal("0.2")).getJSONObject(0).getString("maxFee"))

        val vote = voteParams("0xme", "0xc", 1, "0xsalt", 12_345, BigDecimal("0.2")).getJSONObject(0)
        assertEquals("sendVote", vote.getString("method"))
        assertEquals("0", vote.getString("amount"))
        assertEquals(12_345L, vote.getLong("broadcastBlock"))
        assertEquals("0.2", vote.getString("maxFee"))
        assertEquals(listOf(Triple(0, "byte", "1"), Triple(1, "hex", "0xsalt")), args(vote))
    }

    @Test
    fun sentVotesAreSavedAndReplaced() {
        val first = SentVote("0xme", "0xc", 0, "0xtx1", 100, 1)
        val other = SentVote("0xme", "0xd", 1, "0xtx2", 200, 2)
        val again = SentVote("0xme", "0xc", 1, "0xtx3", 100, 3)
        val saved = JSONArray(withSentVote(withSentVote(listOf(first), other), again).map { it.toJson() }).toString()
        assertEquals(listOf(other, again), parseSentVotes(saved))
        assertEquals(listOf(first), parseSentVotes("""[${first.toJson()}, {"from": "0xme"}, 7]"""))
        assertTrue(parseSentVotes("not json").isEmpty())
        assertEquals("0xme", SentVote.fromJson(first.toJson().put("from", "0xME"))?.from)
    }

    private val date: (Instant) -> String = { "D(${it.epochSecond})" }

    @Test
    fun theConfirmationSaysWhatTheVoteCostsAndWhatComesAfter() {
        val voting = parseVoting(votingJson())
        val plan = VotePlan("0xme", "0xc0ffee", 1, "0xsalt", "0xhash", 12_345, BigDecimal("5"), BigDecimal("0.0123"), BigDecimal("0.0246"))
        val end = Instant.parse("2026-10-10T10:00:00Z").epochSecond
        val counted = Instant.parse("2026-10-11T10:00:00Z").epochSecond
        assertEquals(
            "Vote “Pear”? The app cannot change it once sent.\n\n" +
                "The secret vote sends 5 iDNA to the voting, plus a fee of about 0.02 iDNA (at most 0.03).\n\n" +
                "Your node then opens the vote itself at block 12345, D($end). Keep sync on from then until D($counted): " +
                "a vote that is not opened does not count.",
            voteConfirmText(voting, plan, date),
        )
        val free = voteConfirmText(voting, plan.copy(payment = BigDecimal.ZERO), date)
        assertTrue(free, free.contains("The secret vote costs only its fee: about 0.02 iDNA (at most 0.03)."))
    }

    @Test
    fun theScreenSaysWhereTheVoteIs() {
        val sent = SentVote("0xme", "0xc0ffee", 1, "0xtx", 12_345, 1)
        val end = Instant.parse("2026-10-10T10:00:00Z").epochSecond
        val counted = Instant.parse("2026-10-11T10:00:00Z").epochSecond
        assertEquals("Your secret vote for “Pear” is waiting for a block…", yourVoteText(parseVoting(votingJson()), sent, null, date))
        assertEquals(
            "You voted “Pear”. It stays secret until block 12345, D($end); then your node opens it. Keep sync on from then until D($counted).",
            yourVoteText(parseVoting(votingJson("Voted")), sent, null, date),
        )
        assertEquals(
            "You voted “Pear”. Your node opens the vote now (it can until D($counted)): keep sync on.",
            yourVoteText(parseVoting(votingJson("Counting")), sent, null, date),
        )
        assertEquals("You voted “Apple”: your vote is open ✓", yourVoteText(parseVoting(votingJson("Counting")), sent, 0, date))
        assertEquals("You voted “option 9”: your vote is open ✓", yourVoteText(parseVoting(votingJson("Archive")), null, 9, date))
        assertEquals(
            "You voted from another app: the node that sent the vote opens it.",
            yourVoteText(parseVoting(votingJson("Voted")), null, null, date),
        )
        assertNull(yourVoteText(parseVoting(votingJson()), null, null, date))
        assertNull(yourVoteText(parseVoting(votingJson("Archive")), sent, null, date))
    }
}
