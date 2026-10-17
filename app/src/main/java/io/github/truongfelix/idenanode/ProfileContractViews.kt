package io.github.truongfelix.idenanode

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A profile's picture from IPFS ([cid]) in a rounded square of [size], with the robot of [address] as a small badge
 * in its corner (a picture can be anyone's; the robot is the address's); the robot alone without a picture, while it
 * loads, or when the file is not a still WebP of at most [MAX_PICTURE_BYTES] ([isShowablePicture]).
 */
@Composable
fun ProfilePicture(address: String, cid: String?, size: Dp, modifier: Modifier = Modifier) {
    val picture = cid?.let { rememberPicture(it) }
    if (picture == null) {
        Avatar(address, size, modifier)
        return
    }
    Box(modifier.size(size)) {
        Image(
            bitmap = picture,
            contentDescription = "Profile picture",
            modifier = Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentScale = ContentScale.Crop,
        )
        Avatar(address, size / 3, Modifier.align(Alignment.BottomEnd))
    }
}

/** The pictures to show by lowercase address: those of the profiles in good standing ([ContractProfile.shownPicture]). */
val LocalPictures = compositionLocalOf<Map<String, String>> { emptyMap() }

/** The pictures to show of [profiles], by lowercase address. */
fun shownPictures(profiles: Collection<ContractProfile>): Map<String, String> =
    profiles.mapNotNull { p -> p.shownPicture?.let { p.address to it } }.toMap()

/** A person's avatar: its profile picture ([LocalPictures]) with the robot as a badge, else the robot. */
@Composable
fun PersonAvatar(address: String, size: Dp, modifier: Modifier = Modifier) =
    ProfilePicture(address, LocalPictures.current[address.lowercase()], size, modifier)

/** The picture [cid] decoded, or null while it loads or when it cannot be shown. */
@Composable
private fun rememberPicture(cid: String): ImageBitmap? {
    val (fetched, _) = rememberIpfs(cid)
    val bytes = (fetched as? Fetched.Loaded)?.bytes?.takeIf(::isShowablePicture) ?: return null
    val key = "picture:$cid"
    val bitmap by produceState(images.get(key), key) {
        if (value == null) value = withContext(Dispatchers.Default) { decodeScaled(bytes, 256) }?.also { images.put(key, it) }
    }
    return bitmap
}

/**
 * What a profile adds from the profiles contract, under the address, as the desktop shows it: the claimed name when
 * the title does not show it (a name given on this phone there, [localName], or the name hidden), the bio, and on
 * another's profile who vouches for an account without an identity ("You vouch for them" when it is [me]). Out of
 * good standing the name and bio show only to the account itself ([isMe]), marked hidden. [names] gives the
 * voucher's name.
 */
@Composable
fun ContractProfileLines(profile: ContractProfile?, names: Map<String, String>, me: String?, isMe: Boolean, localName: Boolean) {
    val body = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (profile == null) return
    val shown = profile.inStanding || isMe
    if (shown && profile.name != null && (localName || !profile.inStanding)) {
        val hidden = if (profile.inStanding) "" else " · hidden: not validated, nor vouched for"
        Text("@${profile.name}$hidden", style = body, fontWeight = FontWeight.Medium)
    }
    if (shown) profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, style = body) }
    // The account's own voucher shows with its End button (MyVouching).
    if (isMe) return
    profile.shownVoucher?.let { voucher ->
        val who = if (voucher == me?.lowercase()) "You vouch for them" else "Vouched for by ${displayName(voucher, names)}"
        val note = if (profile.inStanding) "" else " (their voucher is not validated now)"
        Text("$who$note", style = body, color = muted)
    }
}
