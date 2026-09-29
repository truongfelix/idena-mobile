package io.github.truongfelix.idenanode

import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The yearly return of staking and mining, from the node's own data and its reward rules (consensus
 * v12): blockchain/rewards.go (staking), blockchain/blockchain.go applyBlockRewards,
 * rewardFinalCommittee, prepareBlockRewardCtx and GetCommitteeSize (mining), config/consensus.go.
 * The flip and invitation rewards are left out: they depend on the last epoch's results, which the
 * node does not keep.
 */

/** Consensus values the rewards use (config/consensus.go, unchanged up to v12). */
object RewardRules {
    /** Minted per block: BlockReward + FinalCommitteeReward (iDNA). */
    const val BLOCK_REWARD = 1.0
    const val FINAL_COMMITTEE_REWARD = 5.0
    /** Share of the epoch's validation reward (6 iDNA per block of the epoch) paid for staking. */
    const val STAKING_REWARD_PERCENT = 0.18
    const val FINAL_COMMITTEE_PERCENT = 0.7
    const val MAX_COMMITTEE_SIZE = 100
    const val BLOCK_SECONDS = 20.0
}

/** Reward weight of a stake in iDNA: stake^0.9 (stakeWeight, calculateStakeWeight). */
fun stakeWeight(stake: Double): Double = if (stake <= 0) 0.0 else stake.pow(0.9)

/** The final committee size for [validators] online validators (GetCommitteeSize, final). */
fun finalCommitteeSize(validators: Int): Int =
    if (validators <= 8) validators
    else minOf((validators * RewardRules.FINAL_COMMITTEE_PERCENT).roundToInt(), RewardRules.MAX_COMMITTEE_SIZE)

/**
 * The expected mining reward per block (iDNA) for a validator of weight [weight] among [validators]
 * online validators of average weight [averageWeight]. A block mints 6 iDNA shared by weight between
 * the proposer, weighted by committee size x BlockReward / FinalCommitteeReward, and the final
 * committee members (prepareBlockRewardCtx, rewardFinalCommittee). Any validator proposes with the
 * same chance; the committee takes [finalCommitteeSize] of them.
 */
fun miningRewardPerBlock(weight: Double, validators: Int, averageWeight: Double): Double {
    if (validators <= 0 || weight <= 0) return 0.0
    val committee = finalCommitteeSize(validators)
    val pProposer = 1.0 / validators
    val pCommittee = committee.toDouble() / validators
    return pProposer * (1 - pCommittee) * blockReward(weight, true, false, committee, averageWeight) +
        (1 - pProposer) * pCommittee * blockReward(weight, false, true, committee, averageWeight) +
        pProposer * pCommittee * blockReward(weight, true, true, committee, averageWeight)
}

/**
 * One block's reward (iDNA) for a validator of [weight] that is the [proposer], a committee [member],
 * or both; the other places go to validators of [averageWeight]. The 6 iDNA are shared by weight, the
 * proposer's weight counting committee x BlockReward / FinalCommitteeReward times: with equal stakes
 * the proposer gets the BlockReward and each member FinalCommitteeReward / committee.
 */
internal fun blockReward(weight: Double, proposer: Boolean, member: Boolean, committee: Int, averageWeight: Double): Double {
    val coef = committee * RewardRules.BLOCK_REWARD / RewardRules.FINAL_COMMITTEE_REWARD
    val mine = (if (proposer) weight * coef else 0.0) + (if (member) weight else 0.0)
    val others = (if (proposer) 0.0 else averageWeight * coef) + (if (member) committee - 1 else committee) * averageWeight
    return (RewardRules.BLOCK_REWARD + RewardRules.FINAL_COMMITTEE_REWARD) * mine / (mine + others)
}

/** What the APY needs from the network, read from the node (see NodeRpc.networkRewards). */
data class NetworkRewards(
    /** Sum of the stake weights that share the staking reward: Newbie, Verified, Human and the god address. */
    val totalStakingWeight: Double,
    /** Online validators: online validated identities and the delegators of online pools. */
    val validators: Int,
    val averageValidatorWeight: Double,
    /** Length of the current epoch (days), from its first block to the next validation. */
    val epochDays: Double,
    /** Share of blocks with a proposal: empty blocks mint no mining reward. */
    val proposedBlockShare: Double,
)

/** Staking and mining rewards of one epoch and the APY they make. */
data class Apy(val stakingPerEpoch: Double, val miningPerEpoch: Double, val yearly: Double)

/**
 * The APY for an identity with [stake] iDNA that validates (staking) and mines itself (mining), or
 * null if the network data is missing. Rewards of the epoch, divided by the stake, times the epochs
 * in a year.
 */
fun apy(stake: Double, net: NetworkRewards): Apy? {
    if (stake <= 0 || net.epochDays <= 0 || net.totalStakingWeight <= 0) return null
    val weight = stakeWeight(stake)
    val blocks = net.epochDays * 86_400 / RewardRules.BLOCK_SECONDS
    val staking = (RewardRules.BLOCK_REWARD + RewardRules.FINAL_COMMITTEE_REWARD) * blocks *
        RewardRules.STAKING_REWARD_PERCENT * weight / net.totalStakingWeight
    val mining = blocks * net.proposedBlockShare * miningRewardPerBlock(weight, net.validators, net.averageValidatorWeight)
    return Apy(staking, mining, (staking + mining) / stake * 365 / net.epochDays)
}
