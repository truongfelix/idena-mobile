package io.github.truongfelix.idenanode

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/*
 * The picture search of the flip editor, asked only once the user agreed to it: DuckDuckGo, Openverse and Wikimedia
 * Commons, as idena-desktop's main/image-search.js asks them. Only thumbnails are fetched, each from its search's
 * own servers (DuckDuckGo's image proxy, Openverse's API, Wikimedia's thumbnail servers), never from the sites the
 * pictures come from. Each source sees the phone's address and the words searched.
 */

/** A source of the picture search. */
enum class PictureSource(val label: String) {
    DuckDuckGo("DuckDuckGo"),
    Openverse("Openverse"),
    Wikimedia("Wikimedia"),
}

/** The hosts each source's thumbnails come from. */
private val THUMBNAIL_HOSTS = mapOf(
    PictureSource.DuckDuckGo to setOf("external-content.duckduckgo.com"),
    PictureSource.Openverse to setOf("api.openverse.org"),
    PictureSource.Wikimedia to setOf("thumb.wikimedia.org", "upload.wikimedia.org"),
)

/** The source whose servers give the thumbnail [url] (HTTPS only), or null. */
fun thumbnailSource(url: String): PictureSource? {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return null
    if (parsed.protocol != "https" || parsed.userInfo != null) return null
    return THUMBNAIL_HOSTS.entries.firstOrNull { parsed.host in it.value }?.key
}

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

/** Openverse's search for [query]: 20 results, the most it gives without an account. */
fun openverseUrl(query: String): String = "https://api.openverse.org/v1/images/?q=${encode(query)}&page_size=20"

/** The thumbnails of Openverse's results ([json]): its own copies, not the pictures on their sites. */
fun openverseThumbnails(json: String): List<String> {
    val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
    return (0 until results.length())
        .mapNotNull { results.optJSONObject(it)?.optString("thumbnail") }
        .filter { it.length <= 2048 && thumbnailSource(it) == PictureSource.Openverse }
        .distinct()
}

/** Wikimedia Commons' search for [query]: 30 files, each with a thumbnail 320 pixels wide. */
fun wikimediaUrl(query: String): String =
    "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=${encode(query)}" +
        "&gsrnamespace=6&gsrlimit=30&prop=imageinfo&iiprop=url&iiurlwidth=320"

/** The thumbnails of Wikimedia's results ([json]), in the search's order. */
fun wikimediaThumbnails(json: String): List<String> {
    val pages = runCatching { JSONObject(json).optJSONObject("query")?.optJSONObject("pages") }.getOrNull() ?: return emptyList()
    return pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }
        .sortedBy { it.optInt("index", Int.MAX_VALUE) }
        .mapNotNull { it.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("thumburl") }
        .filter { it.length <= 2048 && thumbnailSource(it) == PictureSource.Wikimedia }
        .distinct()
        .toList()
}

/**
 * The line under the search box: the sources still searching, else "No pictures found." when none came, then what
 * each source that failed said. Null when there is nothing to say.
 */
fun pictureSearchStatus(pending: Set<PictureSource>, failed: Map<PictureSource, String>, found: Int): String? {
    val lines = mutableListOf<String>()
    if (pending.isNotEmpty()) lines += "Searching ${PictureSource.entries.filter { it in pending }.joinToString(", ") { it.label }}…"
    else if (found == 0) lines += "No pictures found."
    PictureSource.entries.forEach { source -> failed[source]?.let { lines += "${source.label}: $it" } }
    return lines.joinToString("\n").ifEmpty { null }
}

/** What a failed request means for the user. */
fun pictureSearchError(error: Throwable): String = when (error) {
    is SocketTimeoutException -> "no answer in time."
    is UnknownHostException -> "not reachable (is the phone online?)."
    else -> error.message ?: "no answer."
}

/** The requests of the picture search (see the top of this file). */
object PictureSearch {
    private const val BROWSER_AGENT = "Mozilla/5.0 (Idena image search)"
    private const val MAX_PAGE = 512 * 1024
    private const val MAX_RESULTS = 2 * 1024 * 1024
    private const val MAX_THUMBNAIL = 1024 * 1024
    private val DUCK_HOSTS = setOf("duckduckgo.com")
    private val OPENVERSE_HOSTS = setOf("api.openverse.org")
    private val WIKIMEDIA_HOSTS = setOf("commons.wikimedia.org")

    /** How the app names itself to Openverse and Wikimedia: Wikimedia refuses a request without a client name and a contact. */
    private fun appAgent(appVersion: String) = "IdenaMobile/$appVersion (https://github.com/truongfelix)"

    /** The thumbnails [source] finds for [query] (trimmed to 160 characters); throws when it does not answer. */
    fun search(source: PictureSource, query: String, appVersion: String): List<String> {
        val q = query.trim().take(160)
        if (q.isEmpty()) return emptyList()
        return when (source) {
            PictureSource.DuckDuckGo -> {
                val landing = duckLandingUrl(q)
                val page = String(get(landing, DUCK_HOSTS, MAX_PAGE, BROWSER_AGENT), Charsets.UTF_8)
                val token = duckToken(page) ?: throw IOException("gave no search token.")
                duckThumbnails(String(get(duckResultsUrl(q, token), DUCK_HOSTS, MAX_RESULTS, BROWSER_AGENT, referer = landing), Charsets.UTF_8))
            }
            // A query Openverse has not seen takes it 12 to 16 s.
            PictureSource.Openverse ->
                openverseThumbnails(String(get(openverseUrl(q), OPENVERSE_HOSTS, MAX_RESULTS, appAgent(appVersion), readTimeoutMs = 25_000), Charsets.UTF_8))
            PictureSource.Wikimedia ->
                wikimediaThumbnails(String(get(wikimediaUrl(q), WIKIMEDIA_HOSTS, MAX_RESULTS, appAgent(appVersion), readTimeoutMs = 15_000), Charsets.UTF_8))
        }
    }

    /** A thumbnail found by [search], from its source's servers only. */
    fun thumbnail(url: String, appVersion: String): ByteArray {
        val source = thumbnailSource(url) ?: throw IOException("not a thumbnail of the picture search")
        val agent = if (source == PictureSource.DuckDuckGo) BROWSER_AGENT else appAgent(appVersion)
        return get(url, THUMBNAIL_HOSTS.getValue(source), MAX_THUMBNAIL, agent)
    }

    /** GET [url], only over HTTPS and only from [hosts], at most [max] bytes; no redirect is followed. */
    private fun get(url: String, hosts: Set<String>, max: Int, agent: String, referer: String? = null, readTimeoutMs: Int = 10_000): ByteArray {
        val parsed = URL(url)
        if (parsed.protocol != "https" || parsed.host !in hosts) throw IOException("not an address of the picture search")
        val connection = parsed.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", agent)
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        referer?.let { connection.setRequestProperty("Referer", it) }
        try {
            val code = connection.responseCode
            // DuckDuckGo refuses an address for a while after a few quick searches.
            if (code == 403 || code == 429) throw IOException("refuses searches from this connection for now; try again in a few minutes.")
            if (code !in 200..299) throw IOException("answered $code.")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > max) throw IOException("its answer is too large.")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
