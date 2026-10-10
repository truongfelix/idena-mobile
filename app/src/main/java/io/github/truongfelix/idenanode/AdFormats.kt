package io.github.truongfelix.idenanode

import java.io.ByteArrayOutputStream

/*
 * The formats an ad is written in, as the desktop writes them (renderer/shared/models/proto/models.proto, encoded as
 * its google-protobuf code does: fields at their default value left out): the ad on IPFS (ProtoAd), its audience
 * (ProtoAdTarget), the burn that shows it (ProtoAdBurnKey in a ProtoBurnAttachment), the author's list of ads in the
 * profile (ProtoProfile) and the profile change (ProtoChangeProfileAttachment). Strings holding bytes are lowercase
 * hex without 0x, as the desktop's.
 */

/** The review settings an ad carries and its voting must have (the desktop's adVotingDefaults). */
data class AdVotingParams(val votingDuration: Long, val publicVotingDuration: Long, val quorum: Long, val committeeSize: Long)

/** The desktop's review: secret voting and counting of 4320 blocks each (a day), quorum 1%, a committee of 300. */
val AD_REVIEW_PARAMS = AdVotingParams(votingDuration = 4320, publicVotingDuration = 4320, quorum = 1, committeeSize = 300)

/** An ad in its author's profile (ProtoProfileAd): its cid, audience ([target], hex), review voting and author. */
data class ProfileEntry(val cid: String, val target: String, val contract: String, val author: String)

/** The ad as stored on IPFS (ProtoAd, version 0), with the review settings [params]. */
fun encodeAd(title: String, desc: String, url: String, thumb: ByteArray, media: ByteArray, params: AdVotingParams = AD_REVIEW_PARAMS): ByteArray {
    val votingParams = ProtoWriter()
        .varint(1, params.votingDuration)
        .varint(2, params.publicVotingDuration)
        .varint(3, params.quorum)
        .varint(4, params.committeeSize)
    return ProtoWriter()
        .string(1, title)
        .string(2, desc)
        .string(3, url)
        .bytes(4, thumb)
        .bytes(5, media)
        // The desktop always writes the settings and reads them without a check: an ad without them breaks its page.
        .message(7, votingParams.toByteArray())
        .toByteArray()
}

/** An audience (ProtoAdTarget). */
fun encodeTarget(target: AdTarget): ByteArray = ProtoWriter()
    .string(1, target.language)
    .varint(2, target.age.toLong())
    .string(3, target.os)
    .varint(4, target.stake.toLong())
    .toByteArray()

/** An audience as the profile and the burn key hold it: hex without 0x, empty for anyone. */
fun targetHex(target: AdTarget): String = bytesToHex(encodeTarget(target)).removePrefix("0x")

/** The key of a burn for the ad [cid] shown to [target] (ProtoAdBurnKey, hex without 0x): what bcn_burntCoins lists. */
fun adBurnKey(cid: String, target: String): String =
    bytesToHex(ProtoWriter().string(1, cid).string(2, target).toByteArray()).removePrefix("0x")

/** The payload of a burn transaction (ProtoBurnAttachment) with [key]. */
fun burnPayload(key: String): ByteArray = ProtoWriter().string(1, key).toByteArray()

/** The author's list of ads (ProtoProfile), in order. */
fun encodeProfile(entries: List<ProfileEntry>): ByteArray {
    val profile = ProtoWriter()
    for (entry in entries) {
        profile.message(
            1,
            ProtoWriter().string(1, entry.cid).string(2, entry.target).string(3, entry.contract).string(4, entry.author).toByteArray(),
        )
    }
    return profile.toByteArray()
}

/**
 * The ads of a profile (ProtoProfile) with all their fields, to write the list back with one more. Throws when [bytes]
 * is not such a list (an entry without a cid), so that a profile read wrong is never written back emptied.
 */
fun decodeProfile(bytes: ByteArray): List<ProfileEntry> {
    val profile = Proto(bytes)
    require(profile.all(1).all { it is ByteArray }) { "not a list of ads" }
    return profile.all(1).map {
        val ad = Proto(it as ByteArray)
        val entry = ProfileEntry(ad.string(1), ad.string(2), ad.string(3), ad.string(4))
        require(ipfsCid("ipfs://${entry.cid}") != null) { "an ad without a cid" }
        entry
    }
}

/** The payload of a profile change (ProtoChangeProfileAttachment): the new profile's [cid] as bytes. */
fun changeProfilePayload(cid: String): ByteArray = ProtoWriter().bytes(1, cidBytes(cid)).toByteArray()

/** Writes a protobuf message field by field; a field at its default (0, empty) is left out, as proto3 encoders do. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun lengthDelimited(field: Int, bytes: ByteArray) {
        raw((field shl 3 or 2).toLong())
        raw(bytes.size.toLong())
        out.write(bytes)
    }

    /** A varint field (uint32, int32: a negative int32 takes 10 bytes, as protobuf writes it). */
    fun varint(field: Int, value: Long) = apply {
        if (value != 0L) {
            raw((field shl 3).toLong())
            raw(value)
        }
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray) = apply { if (value.isNotEmpty()) lengthDelimited(field, value) }

    /** A message field: written even when empty, as a set message is. */
    fun message(field: Int, value: ByteArray) = apply { lengthDelimited(field, value) }

    fun toByteArray(): ByteArray = out.toByteArray()
}
