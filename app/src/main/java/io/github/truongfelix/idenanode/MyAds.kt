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
    if (title.isEmpty()) add(t(R.string.ads_write_title)) else if (title.length > AD_TITLE_MAX) add(t(R.string.ads_title_max, AD_TITLE_MAX))
    if (desc.isEmpty()) add(t(R.string.ads_write_description)) else if (desc.length > AD_DESC_MAX) add(t(R.string.ads_desc_max, AD_DESC_MAX))
    if (!AD_LINK.matches(draft.url.trim())) add(t(R.string.ads_link_format))
    if (!hasPicture) add(t(R.string.ads_choose_picture_problem))
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
    inProfile && burnt.signum() > 0 -> AdStage(AdStageKind.Showing, t(R.string.ads_stage_showing, idna(burnt.toPlainString())))
    inProfile -> AdStage(AdStageKind.NotShowing, t(R.string.ads_stage_not_showing))
    contract.isEmpty() -> AdStage(AdStageKind.Draft, t(R.string.ads_stage_draft))
    review?.approved == true -> AdStage(AdStageKind.Approved, t(R.string.ads_stage_approved))
    review?.over == true -> AdStage(AdStageKind.Rejected, t(R.string.ads_stage_rejected))
    voting == null -> AdStage(AdStageKind.InReview, t(R.string.ads_stage_in_review))
    voting.state == VotingState.PENDING -> AdStage(AdStageKind.ReviewNotStarted, t(R.string.ads_stage_not_started))
    else -> AdStage(AdStageKind.InReview, t(R.string.ads_stage_in_review_progress, reviewProgress(voting, now)))
}

/** What an ad's running review waits for, and when. */
private fun reviewProgress(voting: OracleVoting, now: Instant): String = when (voting.state) {
    VotingState.OPEN, VotingState.VOTED -> voting.votingEnd?.let { t(R.string.ads_secret_voting_ends, timeUntil(now, it)) } ?: t(R.string.ads_secret_voting)
    VotingState.COUNTING -> voting.countingEnd?.let { t(R.string.ads_counting_ends, timeUntil(now, it)) } ?: t(R.string.ads_counting)
    VotingState.CAN_BE_PROLONGED -> t(R.string.ads_too_few_votes)
    else -> votingStateText(voting.state)
}

/**
 * Whether an ad for [other] competes with one for [mine]: one viewer can be shown both, as the desktop's
 * canReachSameViewer (idena-desktop #77). Their languages and systems meet (the same, or one left open); a minimum
 * age or stake never keeps two ads apart (a viewer above both minimums meets both).
 */
fun competes(other: AdTarget, mine: AdTarget): Boolean =
    meets(other.language, mine.language) && meets(other.os, mine.os)

/** A language or system left open takes any viewer; two set ones meet only when they are the same. */
private fun meets(a: String, b: String): Boolean = a.isEmpty() || b.isEmpty() || a.equals(b, ignoreCase = true)

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
    if (c.ads == 0) t(R.string.ads_no_competition) else
        tp(R.plurals.ads_competitors, c.ads, c.ads, idna(c.topBurn!!.toPlainString()))

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
    target.age.takeIf { it > 0 }?.let { t(R.string.ads_audience_age, it) },
    target.stake.takeIf { it > 0 }?.let { t(R.string.ads_audience_stake, it) },
).joinToString(" · ").ifEmpty { t(R.string.ads_audience_anyone) }

/** "anyone", or "its audience (French · Android)". */
fun audiencePhrase(target: AdTarget): String =
    if (target == AdTarget()) t(R.string.ads_audience_anyone_lower) else t(R.string.ads_audience_its, audienceText(target))

/** The language [code] named in the app's language ("French", "Français"), else the code. */
fun languageName(code: String): String {
    val inLanguage = java.util.Locale.forLanguageTag(t(R.string.ads_language_names_in))
    return java.util.Locale.forLanguageTag(code).getDisplayLanguage(inLanguage)
        .replaceFirstChar { it.titlecase(inLanguage) }.ifEmpty { code }
}

/** What can be done with an ad from the list. */
enum class AdAction { Preview, Edit, Delete, SendToReview, StartReview, SeeVoting, Show, BurnMore, CopyAsDraft, RemoveFromProfile }

/** The one next step an ad's card offers, as its button. */
fun mainAction(stage: AdStageKind): Pair<String, AdAction> = when (stage) {
    AdStageKind.Draft -> t(R.string.ads_action_send_to_review) to AdAction.SendToReview
    AdStageKind.ReviewNotStarted -> t(R.string.ads_action_start_review) to AdAction.StartReview
    AdStageKind.InReview -> t(R.string.ads_action_see_voting) to AdAction.SeeVoting
    AdStageKind.Approved -> t(R.string.ads_action_show) to AdAction.Show
    AdStageKind.Rejected -> t(R.string.ads_action_copy_as_draft) to AdAction.CopyAsDraft
    AdStageKind.Showing -> t(R.string.ads_action_burn_more) to AdAction.BurnMore
    AdStageKind.NotShowing -> t(R.string.ads_action_show_again) to AdAction.Show
}

/**
 * The card's ⋯ menu: the preview, editing a draft, its voting, deleting a draft or a rejected ad (only the phone's
 * copy: nothing is sent), and taking a campaign (an ad in the profile) out of the profile. An ad with a review running
 * or approved keeps its copy, which holds its voting.
 */
fun menuActions(stage: AdStageKind, onPhone: Boolean, hasVoting: Boolean): List<Pair<String, AdAction>> = buildList {
    add(t(R.string.ads_preview) to AdAction.Preview)
    if (onPhone && stage == AdStageKind.Draft) add(t(R.string.ads_action_edit) to AdAction.Edit)
    if (hasVoting && stage != AdStageKind.InReview) add(t(R.string.ads_action_see_voting) to AdAction.SeeVoting)
    if (onPhone && (stage == AdStageKind.Draft || stage == AdStageKind.Rejected)) add(t(R.string.common_delete) to AdAction.Delete)
    if (stage == AdStageKind.Showing || stage == AdStageKind.NotShowing) add(t(R.string.ads_action_remove_from_profile) to AdAction.RemoveFromProfile)
}

/** The profile list without the campaign [entry]: its content, voting and audience name it (the author does not). */
fun withoutProfileEntry(entries: List<ProfileEntry>, entry: ProfileEntry): List<ProfileEntry> =
    entries.filterNot { it.cid == entry.cid && it.contract == entry.contract && it.target == entry.target }

/** What the removal confirmation says: what changes for the campaign [title], and the [fees]. */
fun removeConfirmText(title: String, fees: BigDecimal): String =
    t(R.string.ads_remove_confirm, title) + "\n\n" + t(R.string.ads_fees_about, fee(fees))

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
    append(
        t(
            R.string.ads_review_confirm,
            title.trim(),
            AD_REVIEW_TITLE,
            durationText(form.votingDuration),
            durationText(maxOf(form.countingDuration, MIN_COUNTING_BLOCKS)),
            idna(costs.reviewFee.toPlainString()),
            idna(costs.deposit.toPlainString()),
            idna(costs.stake.toPlainString()),
            fee(costs.storeFee),
            fee(costs.deployFee),
            idna(costs.total.toPlainString()),
            idna(balance.toPlainString()),
        ),
    )
    if (costs.total > balance) append("\n\n" + t(R.string.ads_review_balance_too_low))
}

/**
 * What the confirmation says before showing the ad [title] to [target] with a burn of [amount]: the profile change
 * first when it is needed ([profileFees] > 0), then the burn and the fees.
 */
fun showConfirmText(title: String, target: AdTarget, amount: BigDecimal, profileChange: Boolean, fees: BigDecimal): String = buildString {
    append(t(R.string.ads_show_confirm, title.trim(), audiencePhrase(target), idna(amount.toPlainString())))
    if (profileChange) append("\n\n" + t(R.string.ads_show_confirm_profile))
    append("\n\n" + t(R.string.ads_fees_about, fee(fees)))
}
