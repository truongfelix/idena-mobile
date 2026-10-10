package io.github.truongfelix.idenanode

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The ads made on this phone ([AdDraft]), one folder each under [root] (files/ads/): ad.json, thumb.webp and
 * media.webp. Each file is written next to it first, then renamed over it, so that a write cut short leaves the last
 * one whole. The calls touch files: use them off the main thread.
 */
class AdDraftStore(private val root: File) {
    private fun dir(id: String): File {
        // The id names a folder: letters, digits and hyphens only.
        require(Regex("^[A-Za-z0-9-]{1,64}$").matches(id)) { "bad ad id" }
        return File(root, id)
    }

    /** The ads, the newest first; a folder that cannot be read is left out. */
    fun list(): List<AdDraft> = root.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { runCatching { load(it.name) }.getOrNull() }
        .sortedByDescending { it.created }

    fun load(id: String): AdDraft {
        val json = JSONObject(File(dir(id), "ad.json").readText())
        return AdDraft(
            id = id,
            title = json.optString("title"),
            desc = json.optString("desc"),
            url = json.optString("url"),
            created = json.optLong("created"),
            cid = json.optString("cid"),
            contract = json.optString("contract"),
        )
    }

    /** Saves [draft]; a picture given replaces the saved one, null keeps it. */
    fun save(draft: AdDraft, thumb: ByteArray? = null, media: ByteArray? = null) = synchronized(this) {
        val dir = dir(draft.id).also { it.mkdirs() }
        thumb?.let { write(File(dir, THUMB), it) }
        media?.let { write(File(dir, MEDIA), it) }
        val json = JSONObject()
            .put("title", draft.title)
            .put("desc", draft.desc)
            .put("url", draft.url)
            .put("created", draft.created)
            .put("cid", draft.cid)
            .put("contract", draft.contract)
        write(File(dir, "ad.json"), json.toString().toByteArray())
    }

    fun thumb(id: String): ByteArray? = File(dir(id), THUMB).takeIf { it.exists() }?.readBytes()

    fun media(id: String): ByteArray? = File(dir(id), MEDIA).takeIf { it.exists() }?.readBytes()

    fun delete(id: String) = synchronized(this) { dir(id).deleteRecursively() }

    private fun write(file: File, bytes: ByteArray) {
        val next = File(file.parentFile, file.name + ".tmp")
        next.writeBytes(bytes)
        if (!next.renameTo(file)) throw java.io.IOException("cannot save ${file.name}")
    }

    companion object {
        private const val THUMB = "thumb.webp"
        private const val MEDIA = "media.webp"

        /** A new draft's id. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}
