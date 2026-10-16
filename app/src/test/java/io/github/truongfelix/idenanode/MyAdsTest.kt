package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant

class MyAdsTest {
    private val now = Instant.parse("2026-10-11T10:00:00Z")
    private val ready = AdDraft("a", title = "Fresh bread", desc = "Warm loaves every morning", url = "https://example.com")

    @Test
    fun anAdGoesToReviewWithTheDesktopsLimits() {
        assertEquals(emptyList<String>(), adProblems(ready, hasPicture = true))
        assertEquals(
            listOf("Write a title.", "Write a description.", "The link starts with https://, http:// or dna:.", "Choose a picture."),
            adProblems(AdDraft("b"), hasPicture = false),
        )
        assertEquals(
            listOf("The title has at most 40 characters.", "The description has at most 70 characters."),
            adProblems(ready.copy(title = "t".repeat(41), desc = "d".repeat(71)), hasPicture = true),
        )
        // Exactly the limits pass; the desktop's dna: links too.
        assertEquals(emptyList<String>(), adProblems(ready.copy(title = "t".repeat(40), desc = "d".repeat(70), url = "dna://vote/v1?x=1"), true))
        assertEquals(listOf("The link starts with https://, http:// or dna:."), adProblems(ready.copy(url = "example.com"), true))
    }

    @Test
    fun theReviewVotingIsTheDesktops() {
        val form = adReviewForm(" Fresh bread ", "bafkreiad", BigDecimal(100))
        val fact = JSONObject(String(hexToBytes(factHex(form)), Charsets.UTF_8))
        assertEquals("Is this ad appropriate?", fact.getString("title"))
        assertEquals("Fresh bread", fact.getString("desc"))
        assertEquals("bafkreiad", fact.getString("adCid"))
        assertEquals("""[{"id":0,"value":"Approve"},{"id":1,"value":"Reject"}]""", fact.getJSONArray("options").toString())
        val args = deployArgs(form, now)
        fun arg(i: Int) = args.getJSONObject(i).getString("value")
        assertEquals(now.epochSecond.toString(), arg(1))
        assertEquals(listOf("4320", "4320", "51", "1", "300", "0", "0"), (2..8).map(::arg))
        assertTrue(launchesAtOnce(form))
        // Another voting's fact has no adCid.
        assertFalse(JSONObject(String(hexToBytes(factHex(form.copy(adCid = ""))), Charsets.UTF_8)).has("adCid"))
    }

    private val fact = bytesToHex("""{"title":"Is this ad appropriate?","desc":"Fresh bread","options":[]}""".toByteArray())

    private fun voting(state: String, vararg fields: Pair<String, Any>): OracleVoting {
        val json = JSONObject(
            """{"contractAddress": "0xC0", "author": "0xME", "balance": "0", "fact": "$fact", "state": "$state", "committeeSize": 300,
               "quorum": 1, "winnerThreshold": 51, "ownerFee": 0, "isOracle": false}""",
        )
        fields.forEach { (name, value) -> json.put(name, value) }
        return parseVoting(json)
    }

    @Test
    fun anAdsStageComesFromItsReviewAndBurns() {
        fun stage(contract: String = "0xc0", voting: OracleVoting? = null, review: AdReview? = null, inProfile: Boolean = false, burnt: String = "0") =
            adStage(contract, voting, review, inProfile, BigDecimal(burnt), now)
        assertEquals(AdStage(AdStageKind.Draft, "Draft"), stage(contract = ""))
        assertEquals(AdStageKind.ReviewNotStarted, stage(voting = voting(VotingState.PENDING)).kind)
        assertEquals(
            AdStage(AdStageKind.InReview, "In review · secret voting ends in 5 h"),
            stage(voting = voting(VotingState.OPEN, "estimatedVotingFinishTime" to "2026-10-11T15:30:00Z")),
        )
        assertEquals("In review · counting ends in 20 h", stage(voting = voting(VotingState.COUNTING, "estimatedPublicVotingFinishTime" to "2026-10-12T06:00:00Z")).text)
        assertEquals("In review · too few votes: prolong it on its page", stage(voting = voting(VotingState.CAN_BE_PROLONGED)).text)
        assertEquals(AdStage(AdStageKind.Approved, "Approved: ready to show"), stage(review = AdReview(over = true, approved = true)))
        assertEquals(AdStageKind.Rejected, stage(review = AdReview(over = true, approved = false)).kind)
        // A review still running is no verdict.
        assertEquals(AdStageKind.InReview, stage(review = AdReview(over = false, approved = false)).kind)
        assertEquals(AdStage(AdStageKind.Showing, "Showing · 120 iDNA burnt in 24 h"), stage(inProfile = true, burnt = "120"))
        assertEquals(AdStageKind.NotShowing, stage(inProfile = true).kind)
    }

    @Test
    fun eachStageOffersOneNextStep() {
        assertEquals("Send to review…" to AdAction.SendToReview, mainAction(AdStageKind.Draft))
        assertEquals(AdAction.Show, mainAction(AdStageKind.Approved).second)
        assertEquals(AdAction.CopyAsDraft, mainAction(AdStageKind.Rejected).second)
        assertEquals(AdAction.BurnMore, mainAction(AdStageKind.Showing).second)
        assertEquals(listOf(AdAction.Preview, AdAction.Edit, AdAction.Delete), menuActions(AdStageKind.Draft, onPhone = true, hasVoting = false).map { it.second })
        assertEquals(listOf(AdAction.Preview, AdAction.SeeVoting, AdAction.Delete), menuActions(AdStageKind.Rejected, true, true).map { it.second })
        // An ad in review or approved keeps its copy: it holds the voting.
        assertEquals(listOf(AdAction.Preview), menuActions(AdStageKind.InReview, true, true).map { it.second })
        assertEquals(listOf(AdAction.Preview, AdAction.SeeVoting), menuActions(AdStageKind.Approved, true, true).map { it.second })
        assertEquals(listOf(AdAction.Preview, AdAction.SeeVoting), menuActions(AdStageKind.Showing, onPhone = false, hasVoting = true).map { it.second })
    }

    @Test
    fun otherAdsCompeteWhenOneViewerCanBeShownBoth() {
        val mine = AdTarget(language = "fr", os = "android", age = 3, stake = 100)
        assertTrue(competes(AdTarget(), mine))
        assertTrue(competes(AdTarget(language = "FR"), mine))
        assertTrue(competes(AdTarget(language = "fr", os = "android", age = 9, stake = 500), mine))
        assertTrue(competes(mine, AdTarget()))
        assertFalse(competes(AdTarget(language = "fr", os = "ios"), mine))
        assertFalse(competes(AdTarget(language = "de"), mine))
        assertFalse(competes(AdTarget(language = "de", os = "ios", age = 9, stake = 500), mine))
        val burns = listOf(
            AdBurn("0xA", "50", "bafkreimine", AdTarget()),
            AdBurn("0xB", "300", "bafkreiother", AdTarget()),
            AdBurn("0xB", "20", "bafkreiother", AdTarget(language = "fr")),
            AdBurn("0xC", "70", "bafkreithird", AdTarget(language = "de", os = "ios", age = 9, stake = 500)),
        )
        assertEquals(Competition(1, BigDecimal(300)), competition(burns, "bafkreimine", mine))
        assertEquals("1 competitor · top burn 300 iDNA in 24 h", competitionText(competition(burns, "bafkreimine", mine)))
        assertEquals("No other ad competes for this audience.", competitionText(Competition(0, null)))
        assertEquals(BigDecimal(50), burntFor(burns, "0xa", "bafkreimine"))
    }

    @Test
    fun theProfileKeepsOneEntryPerAd() {
        val one = ProfileEntry("bafkreione", "", "0x1", "0xme")
        val two = ProfileEntry("bafkreitwo", "", "0x2", "0xme")
        assertEquals(listOf(one, two), withProfileEntry(listOf(one), two))
        val narrower = two.copy(target = targetHex(AdTarget(language = "fr")))
        assertEquals(listOf(one, narrower), withProfileEntry(listOf(one, two), narrower))
    }

    @Test
    fun theConfirmationsListEveryCost() {
        val costs = ReviewCosts(BigDecimal(100), BigDecimal(5000), BigDecimal("0.003"), BigDecimal("4.2"), BigDecimal("0.123"))
        assertEquals(
            "Send “Fresh bread” to review? Oracles answer “Is this ad appropriate?” (Approve or Reject) in a secret voting of " +
                "1 day, then the votes are counted for 1 day. The ad can no longer be changed.\n\nIt takes from your balance:" +
                "\n• review fee: 100 iDNA, for the oracles who vote" +
                "\n• deposit: 5000 iDNA, back when the voting ends" +
                "\n• stake: 0.003 iDNA, half back when the voting is terminated, the other half burned" +
                "\n• storing the ad on IPFS: about 4.20 iDNA" +
                "\n• fees: about 0.13 iDNA, and a small one to launch the voting" +
                "\nTotal: about 5104.326 iDNA of your 6240 iDNA.",
            reviewConfirmText("Fresh bread", costs, BigDecimal(6240), adReviewForm("Fresh bread", "bafkreiad", BigDecimal(100))),
        )
        assertTrue(reviewConfirmText("Fresh bread", costs, BigDecimal(5000), adReviewForm("Fresh bread", "bafkreiad", BigDecimal(100))).endsWith("Your balance is too low for this review."))
        val short = adReviewForm("Fresh bread", "bafkreiad", BigDecimal(100), AdVotingParams(15, 100, 1, 300))
        assertTrue(reviewConfirmText("Fresh bread", costs, BigDecimal(6240), short).contains("secret voting of 5 min, then the votes are counted for 33 min."))
        assertEquals(
            "Show “Fresh bread” for 24 h to its audience (French · Android · age 5+)? It burns 100 iDNA: the coins are " +
                "destroyed. The ad then ranks against the other ads by the iDNA burnt for it over the last 24 h; burn again to " +
                "keep it shown.\n\nFirst your profile gets the ad and its audience (stored on IPFS, then a profile change), as the " +
                "desktop's campaigns.\n\nFees: about 0.52 iDNA.",
            showConfirmText("Fresh bread", AdTarget("fr", 5, "android"), BigDecimal(100), true, BigDecimal("0.511")),
        )
        assertTrue(showConfirmText("Fresh bread", AdTarget(), BigDecimal(1), false, BigDecimal.ONE).startsWith("Show “Fresh bread” for 24 h to anyone?"))
    }

    @Test
    fun draftsAreKeptOnThePhone() {
        val store = AdDraftStore(Files.createTempDirectory("ads").toFile())
        val older = AdDraft(AdDraftStore.newId(), "Older", created = 1)
        val newer = AdDraft(AdDraftStore.newId(), "Newer", "line", "https://example.com", created = 2)
        store.save(older)
        store.save(newer, thumb = byteArrayOf(1), media = byteArrayOf(2, 3))
        assertEquals(listOf(newer, older), store.list())
        // A save without pictures keeps them; the review's cid and voting are kept.
        store.save(newer.copy(cid = "bafkreiad", contract = "0xc0"))
        assertEquals("0xc0", store.load(newer.id).contract)
        assertEquals(listOf<Byte>(2, 3), store.media(newer.id)!!.toList())
        store.delete(older.id)
        assertEquals(listOf(newer.id), store.list().map { it.id })
        assertThrows(IllegalArgumentException::class.java) { store.load("../escape") }
    }
}
