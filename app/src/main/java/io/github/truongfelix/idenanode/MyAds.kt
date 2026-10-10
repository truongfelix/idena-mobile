package io.github.truongfelix.idenanode

import java.math.BigDecimal
import java.time.Instant

/*
 * My ads: the ads this identity makes, as the desktop's Ads pages make them (formats in AdFormats.kt). A draft stays
 * on the phone; its review is an oracle voting with the desktop's settings ("Is this ad appropriate?", Approve or
 * Reject); once approved, the ad goes into the identity's profile with its audience, and each burn shows it for a day
 * (4320 blocks) to the identities of that audience.
 */

/** The longest title and description, as the desktop's form. */
const val AD_TITLE_MAX = 40
const val AD_DESC_MAX = 70

/** The thumbnail's and the picture's box in pixels, as the desktop sends them. */
const val AD_THUMB_SIZE = 80
const val AD_MEDIA_SIZE = 320

/** The review fee the desktop starts from, paid to the oracles. */
val DEFAULT_REVIEW_FEE = BigDecimal(100)

/** The languages an audience can name: the desktop's, by code. */
val AD_LANGUAGES = listOf(
    "en", "id", "fr", "de", "es", "ru", "zh", "ko", "hr", "hi", "uk", "sr", "ro", "it", "pt", "pl", "sl", "tr", "bg", "sv", "ja", "el",
)

/** The systems an audience can name, as the desktop's: code and name. */
val AD_OSES = listOf("windows" to "Windows", "macos" to "macOS", "linux" to "Linux", "ios" to "iOS", "android" to "Android")

/** An ad made on this phone: its texts, and once sent to review its [cid] on IPFS and its review voting ([contract]). */
data class AdDraft(
    val id: String,
    val title: String = "",
    val desc: String = "",
    val url: String = "",
    val created: Long = 0,
    val cid: String = "",
    val contract: String = "",
)

private val AD_LINK = Regex("^(https?://[^\\s/]+\\S*|dna:\\S+)$", RegexOption.IGNORE_CASE)

/** What keeps [draft] from going to review, in the order of the form; empty when it can go. */
fun adProblems(draft: AdDraft, hasPicture: Boolean): List<String> = buildList {
    val title = draft.title.trim()
    val desc = draft.desc.trim()
    if (title.isEmpty()) add("Write a title.") else if (title.length > AD_TITLE_MAX) add("The title has at most $AD_TITLE_MAX characters.")
    if (desc.isEmpty()) add("Write a description.") else if (desc.length > AD_DESC_MAX) add("The description has at most $AD_DESC_MAX characters.")
    if (!AD_LINK.matches(draft.url.trim())) add("The link starts with https://, http:// or dna:.")
    if (!hasPicture) add("Choose a picture.")
}

/**
 * The review voting of the ad [cid] titled [adTitle]: the desktop's ad review, its fee [reviewFee] sent at launch.
 * [params]: the desktop's settings; shorter ones only for a test network (as the desktop's dev builds).
 */
fun adReviewForm(adTitle: String, cid: String, reviewFee: BigDecimal, params: AdVotingParams = AD_REVIEW_PARAMS) = NewVotingForm(
    title = AD_REVIEW_TITLE,
    desc = adTitle.trim(),
    options = listOf("Approve", "Reject"),
    startNow = true,
    votingDuration = params.votingDuration,
    countingDuration = params.publicVotingDuration,
    committeeSize = params.committeeSize,
    quorum = params.quorum.toInt(),
    winnerThreshold = Majority.Simple.percent,
    freeVoting = true,
    ownerFee = 0,
    // Sent with the launch, as the desktop's review fee; the contract keeps it as a rewards fund only with an owner fee.
    rewardsFund = reviewFee,
    adCid = cid,
)

/** Where an ad is on its way, and the one next step each offers. */
enum class AdStageKind { Draft, ReviewNotStarted, InReview, Approved, Rejected, Showing, NotShowing }

data class AdStage(val kind: AdStageKind, val text: String)

/**
 * The stage of an ad: a draft without a review voting ([contract] empty); in the profile, showing while it has burns
 * of the last day ([burnt]); else by its review: approved, rejected (over without approval), not started, or in
 * review ([voting], its state and dates).
 */
fun adStage(contract: String, voting: OracleVoting?, review: AdReview?, inProfile: Boolean, burnt: BigDecimal, now: Instant): AdStage = when {
    inProfile && burnt.signum() > 0 -> AdStage(AdStageKind.Showing, "Showing · ${idna(burnt.toPlainString())} burnt in 24 h")
    inProfile -> AdStage(AdStageKind.NotShowing, "Not showing: no burn in 24 h")
    contract.isEmpty() -> AdStage(AdStageKind.Draft, "Draft")
    review?.approved == true -> AdStage(AdStageKind.Approved, "Approved: ready to show")
    review?.over == true -> AdStage(AdStageKind.Rejected, "Not approved by the review")
    voting == null -> AdStage(AdStageKind.InReview, "In review")
    voting.state == VotingState.PENDING -> AdStage(AdStageKind.ReviewNotStarted, "Review not started")
    else -> AdStage(AdStageKind.InReview, "In review · " + reviewProgress(voting, now))
}

/** What an ad's running review waits for, and when. */
private fun reviewProgress(voting: OracleVoting, now: Instant): String = when (voting.state) {
    VotingState.OPEN, VotingState.VOTED -> voting.votingEnd?.let { "secret voting ends ${timeUntil(now, it)}" } ?: "secret voting"
    VotingState.COUNTING -> voting.countingEnd?.let { "counting ends ${timeUntil(now, it)}" } ?: "counting"
    VotingState.CAN_BE_PROLONGED -> "too few votes: prolong it on its page"
    else -> votingStateText(voting.state)
}

/**
 * Whether an ad for [other] competes with one for [mine], as the desktop's areCompetingAds: some setting of [other]
 * is open, or includes [mine] (same language or system, at most its age or stake).
 */
fun competes(other: AdTarget, mine: AdTarget): Boolean =
    other.language.isEmpty() || other.language.equals(mine.language, ignoreCase = true) ||
        other.os.isEmpty() || other.os.equals(mine.os, ignoreCase = true) ||
        other.age == 0 || mine.age >= other.age ||
        other.stake == 0 || mine.stake >= other.stake

/** The other ads competing with [cid] for [target]: how many, and the most burnt for one of them over the last day. */
data class Competition(val ads: Int, val topBurn: BigDecimal?)

/** The [Competition] of [cid] for [target] among the approved [burns] (one per ad and author, as [rankedBurns] gives). */
fun competition(burns: List<AdBurn>, cid: String, target: AdTarget): Competition {
    val others = burns.filter { it.cid != cid && competes(it.target, target) }
    val perAd = others.groupBy { it.address.lowercase() to it.cid }.values.map { list -> list.maxOf { it.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO } }
    return Competition(perAd.size, perAd.maxOrNull())
}

/** "2 competitors · top burn 300 iDNA", or that no other ad competes. */
fun competitionText(c: Competition): String =
    if (c.ads == 0) "No other ad competes for this audience." else
        "${c.ads} competitor${if (c.ads == 1) "" else "s"} · top burn ${idna(c.topBurn!!.toPlainString())} in 24 h"

/** The iDNA [address] burnt for [cid] over the last day, from bcn_burntCoins' items. */
fun burntFor(burns: List<AdBurn>, address: String, cid: String): BigDecimal =
    burns.filter { it.cid == cid && it.address.equals(address, ignoreCase = true) }
        .fold(BigDecimal.ZERO) { sum, burn -> sum + (burn.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO) }

/** The profile list with the ad [entry]: an earlier entry of the same ad is replaced (its audience changes). */
fun withProfileEntry(entries: List<ProfileEntry>, entry: ProfileEntry): List<ProfileEntry> {
    val at = entries.indexOfFirst { it.cid == entry.cid }
    return if (at < 0) entries + entry else entries.toMutableList().apply { set(at, entry) }
}

/** "French · Android · age 5+ · stake 100+ iDNA", or "Anyone" for an open audience. */
fun audienceText(target: AdTarget): String = listOfNotNull(
    target.language.takeIf { it.isNotEmpty() }?.let { languageName(it) },
    target.os.takeIf { it.isNotEmpty() }?.let { os -> AD_OSES.firstOrNull { it.first == os }?.second ?: os },
    target.age.takeIf { it > 0 }?.let { "age $it+" },
    target.stake.takeIf { it > 0 }?.let { "stake $it+ iDNA" },
).joinToString(" · ").ifEmpty { "Anyone" }

/** "anyone", or "its audience (French · Android)". */
fun audiencePhrase(target: AdTarget): String = if (target == AdTarget()) "anyone" else "its audience (${audienceText(target)})"

/** The language [code] named in English ("French"), else the code. */
fun languageName(code: String): String =
    java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { code }

/** What can be done with an ad from the list. */
enum class AdAction { Preview, Edit, Delete, SendToReview, StartReview, SeeVoting, Show, BurnMore, CopyAsDraft }

/** The one next step an ad's card offers, as its button. */
fun mainAction(stage: AdStageKind): Pair<String, AdAction> = when (stage) {
    AdStageKind.Draft -> "Send to review…" to AdAction.SendToReview
    AdStageKind.ReviewNotStarted -> "Start the review…" to AdAction.StartReview
    AdStageKind.InReview -> "See the voting" to AdAction.SeeVoting
    AdStageKind.Approved -> "Show my ad…" to AdAction.Show
    AdStageKind.Rejected -> "Edit as a new draft" to AdAction.CopyAsDraft
    AdStageKind.Showing -> "Burn more…" to AdAction.BurnMore
    AdStageKind.NotShowing -> "Show it again…" to AdAction.Show
}

/**
 * The card's ⋯ menu: the preview, editing a draft, its voting, and deleting a draft or a rejected ad (only the phone's
 * copy: nothing is sent). An ad with a review running or approved keeps its copy, which holds its voting.
 */
fun menuActions(stage: AdStageKind, onPhone: Boolean, hasVoting: Boolean): List<Pair<String, AdAction>> = buildList {
    add("Preview" to AdAction.Preview)
    if (onPhone && stage == AdStageKind.Draft) add("Edit" to AdAction.Edit)
    if (hasVoting && stage != AdStageKind.InReview) add("See the voting" to AdAction.SeeVoting)
    if (onPhone && (stage == AdStageKind.Draft || stage == AdStageKind.Rejected)) add("Delete" to AdAction.Delete)
}

/** What sending an ad to review takes from the balance, in iDNA. */
data class ReviewCosts(val reviewFee: BigDecimal, val deposit: BigDecimal, val stake: BigDecimal, val storeFee: BigDecimal, val deployFee: BigDecimal) {
    val total: BigDecimal
        get() = reviewFee + deposit + stake + storeFee + deployFee
}

/**
 * What the confirmation says before sending the ad [title] to review with [form] (its periods); with [balance] below the
 * total, why it cannot.
 */
fun reviewConfirmText(title: String, costs: ReviewCosts, balance: BigDecimal, form: NewVotingForm): String = buildString {
    append("Send “${title.trim()}” to review? Oracles answer “$AD_REVIEW_TITLE” (Approve or Reject) in a secret voting of ")
    append("${durationText(form.votingDuration)}, then the votes are counted for ${durationText(maxOf(form.countingDuration, MIN_COUNTING_BLOCKS))}. ")
    append("The ad can no longer be changed.\n\nIt takes from your balance:")
    append("\n• review fee: ${idna(costs.reviewFee.toPlainString())}, for the oracles who vote")
    append("\n• deposit: ${idna(costs.deposit.toPlainString())}, back when the voting ends")
    append("\n• stake: ${idna(costs.stake.toPlainString())}, half back when the voting is terminated, the other half burned")
    append("\n• storing the ad on IPFS: about ${fee(costs.storeFee)} iDNA")
    append("\n• fees: about ${fee(costs.deployFee)} iDNA, and a small one to launch the voting")
    append("\nTotal: about ${idna(costs.total.toPlainString())} of your ${idna(balance.toPlainString())}.")
    if (costs.total > balance) append("\n\nYour balance is too low for this review.")
}

/**
 * What the confirmation says before showing the ad [title] to [target] with a burn of [amount]: the profile change
 * first when it is needed ([profileFees] > 0), then the burn and the fees.
 */
fun showConfirmText(title: String, target: AdTarget, amount: BigDecimal, profileChange: Boolean, fees: BigDecimal): String = buildString {
    append("Show “${title.trim()}” for 24 h to ${audiencePhrase(target)}? ")
    append("It burns ${idna(amount.toPlainString())}: the coins are destroyed. The ad then ranks against the other ads by the iDNA ")
    append("burnt for it over the last 24 h; burn again to keep it shown.")
    if (profileChange) append("\n\nFirst your profile gets the ad and its audience (stored on IPFS, then a profile change), as the desktop's campaigns.")
    append("\n\nFees: about ${fee(fees)} iDNA.")
}
