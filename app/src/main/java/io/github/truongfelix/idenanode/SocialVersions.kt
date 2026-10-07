package io.github.truongfelix.idenanode

/*
 * idena.social's contracts. The current one takes the posts since its deployment; each older one, the posts of
 * the blocks before the next one was deployed, as idena.social-ui reads them (breakingChanges in
 * src/logic/asyncUtils.ts). Older posts are read only and get ids of their own in the app ([appId]), so that
 * they never meet the current contract's. Versions, formats and numbers: the workspace's
 * notes/idena-social-contracts.md; the desktop app has the same table (renderer/screens/social/versions.js).
 */

/**
 * How a version's calls are written. [V1] (v1, v5): sendTip takes the post id as plain text and tips what is
 * sent; a reply's target may be written as hex; a post needs a message. [V9] (v9, v10): sendTip {postId} tips
 * what is sent. [V12] (v11, v12): sendTip {postId, tipAmount} in whole iDNA.
 */
enum class CallFormat { V1, V9, V12 }

/** The unit of a sendTipFromBalance tipAmount (v1 and v5): whole iDNA, or its smallest unit (10^-18 iDNA). */
enum class BalanceTips { Whole, Atomic }

/**
 * A contract version: its [name] (the web app's), [address], the blocks [from] until [to] (excluded; null for the
 * current one) where its calls count, the [format] of its calls, and the unit of its tips from the balance.
 */
data class SocialVersion(
    val name: String,
    val address: String,
    val from: Long,
    val to: Long?,
    val format: CallFormat,
    val balanceTips: BalanceTips? = null,
) {
    val isCurrent: Boolean
        get() = to == null
}

val CURRENT_VERSION = SocialVersion("v12", SOCIAL_CONTRACT, SOCIAL_FIRST_BLOCK, null, CallFormat.V12)

/** The versions, newest first. */
val SOCIAL_VERSIONS = listOf(
    CURRENT_VERSION,
    SocialVersion("v11", "0x18b0a55eb99AcA113f50eEBbdeAf6f96E789277f", 10_727_655, SOCIAL_FIRST_BLOCK, CallFormat.V12),
    SocialVersion("v10", "0xa1c5c1A8c6a1Af596078A5c9653F24c216fE1cb2", 10_627_018, 10_727_655, CallFormat.V9),
    SocialVersion("v9", "0xc0324f3Cf8158D6E27dc0A07c221636056174718", 10_604_687, 10_627_018, CallFormat.V9),
    // v5's first call; the contract was deployed at 10,219,075.
    SocialVersion("v5", "0xC5B35B4Dc4359Cc050D502564E789A374f634fA9", 10_219_188, 10_604_687, CallFormat.V1, BalanceTips.Atomic),
    SocialVersion("v1", "0x8d318630eB62A032d2f8073d74f05cbF7c6C87Ae", 10_135_621, 10_219_188, CallFormat.V1, BalanceTips.Whole),
)

/** The older versions, newest first. */
val OLDER_VERSIONS = SOCIAL_VERSIONS.drop(1)

/** The first block of idena.social's history. */
val HISTORY_START = SOCIAL_VERSIONS.last().from

/** The version whose calls count at [height]; null before the history. */
fun versionAt(height: Long): SocialVersion? = SOCIAL_VERSIONS.firstOrNull { height >= it.from }

/** The version named [name]; an empty name (a call of the current contract) or an unknown one is the current. */
fun versionNamed(name: String): SocialVersion = SOCIAL_VERSIONS.firstOrNull { it.name == name } ?: CURRENT_VERSION

/** The span of each older version's ids in the app: the current contract will never number this many posts. */
const val OLDER_ID_SPAN = 1_000_000_000L

/**
 * The id in the app of post [id] of this version: the same for the current contract; below zero for an older one,
 * each version below the newer ones, so that ordering by id stays the chain's order.
 */
fun SocialVersion.appId(id: Long): Long = id - OLDER_ID_SPAN * SOCIAL_VERSIONS.indexOf(this)

/**
 * The feed of an older version, numbered by its own contract, with the app's ids ([appId]): the posts, the posts
 * their answers name (replyTo, the channel "discuss:<id>") and those its tips name, so that they never meet the
 * current contract's.
 */
fun SocialVersion.withAppIds(feed: List<SocialPost>): List<SocialPost> {
    fun id(text: String): String = text.toLongOrNull()?.let { appId(it).toString() } ?: text
    fun call(post: PostCall): PostCall = post.copy(
        replyTo = id(post.replyTo),
        channel = if (post.channel.startsWith("discuss:")) "discuss:" + id(post.channel.removePrefix("discuss:")) else post.channel,
    )
    fun node(post: SocialPost): SocialPost = post.copy(
        id = appId(post.id),
        call = call(post.call),
        likeCalls = post.likeCalls.map(::call),
        tips = post.tips.map { it.copy(postId = id(it.postId)) },
        replies = post.replies.map(::node),
    )
    return feed.map(::node)
}
