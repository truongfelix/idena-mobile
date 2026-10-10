package io.github.truongfelix.idenanode

import android.content.Context
import android.net.Uri
import java.io.IOException

/*
 * An ad's pictures, as the desktop sends them: the picture fits in 320 by 320 pixels and the thumbnail in 80 by 80,
 * proportions kept, never enlarged. Encoded as the Social images (WebP, the photo's metadata left out): storing on
 * IPFS is paid by the byte.
 */

/** The picture and the thumbnail made from the image at [uri]. Throws when it cannot be read. */
fun adPictures(context: Context, uri: Uri): Pair<ByteArray, ByteArray> {
    val source = decodeUpright(readImage(context, uri), AD_MEDIA_SIZE) ?: throw IOException("Android cannot read this image")
    return encodeWebp(scaled(source, AD_MEDIA_SIZE)) to encodeWebp(scaled(source, AD_THUMB_SIZE))
}

/** The thumbnail made from the image at [uri] ("Other thumbnail"). Throws when it cannot be read. */
fun adThumb(context: Context, uri: Uri): ByteArray {
    val source = decodeUpright(readImage(context, uri), AD_THUMB_SIZE) ?: throw IOException("Android cannot read this image")
    return encodeWebp(scaled(source, AD_THUMB_SIZE))
}
