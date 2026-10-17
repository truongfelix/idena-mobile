package io.github.truongfelix.idenanode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * New versions (ruling Q-49): once the person turned it on, the app asks GitHub for the phone app's releases once a
 * day (from the node's service, [NodeService]) and when it opens, and tells of a release newer than the installed
 * build with a notification; the release's page shows its note and Download opens it in the browser. Android
 * installs it over the app only when it is signed with the same key. Off, nothing is sent. Every phone release is a
 * pre-release so far, so the list is read, not `releases/latest`, which skips them.
 */

/** A release of the phone app: its [version] (the tag without its "v"), [note] and [page] on GitHub. */
data class AppRelease(val version: String, val note: String, val page: String)

/** A version as "0.2.0-beta.22": the numbers, then the pre-release ("beta", 22), none for a stable release. */
data class AppVersion(val numbers: List<Int>, val label: String?, val build: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        // A stable release comes after the pre-releases of its numbers.
        if (label == null || other.label == null) return (label == null).compareTo(other.label == null)
        return compareValuesBy(this, other, { it.label }, { it.build })
    }

    companion object {
        /** [text] ("v0.2.0-beta.22", "0.2.0"), or null when it is not a version. */
        fun parse(text: String): AppVersion? {
            val core = text.trim().removePrefix("v").split('-', limit = 2)
            val numbers = core[0].split('.').map { it.toIntOrNull() ?: return null }
            val pre = core.getOrNull(1)?.split('.', limit = 2) ?: return AppVersion(numbers, null, 0)
            return AppVersion(numbers, pre[0], pre.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }
}

/** The phone app's releases on GitHub; a release's page is this and its tag, whatever the answer says. */
const val RELEASES_PAGE = "https://github.com/truongfelix/idena-mobile/releases"

/** The newest release of GitHub's [releases] list that is newer than [installed]; drafts are left out. */
fun newerRelease(releases: JSONArray, installed: String): AppRelease? {
    val current = AppVersion.parse(installed) ?: return null
    return (0 until releases.length()).map { releases.getJSONObject(it) }
        .filter { !it.optBoolean("draft") }
        .filter { it.optString("tag_name").matches(Regex("[0-9A-Za-z.-]{1,40}")) }
        .mapNotNull { r -> AppVersion.parse(r.optString("tag_name"))?.let { it to r } }
        .filter { it.first > current }
        .maxByOrNull { it.first }
        ?.second?.let {
            val tag = it.getString("tag_name")
            AppRelease(tag.removePrefix("v"), it.optString("body"), "$RELEASES_PAGE/tag/$tag")
        }
}

/** Whether to ask GitHub now: only when turned on, and at least [gapMs] after the last time. */
fun releaseCheckDue(on: Boolean, lastCheckMs: Long?, nowMs: Long, gapMs: Long): Boolean =
    on && (lastCheckMs == null || nowMs - lastCheckMs >= gapMs || nowMs < lastCheckMs)

/** A release note's Markdown as plain text: headings, emphasis and code marks taken out, list items as "•". */
fun plainNote(markdown: String): String = markdown.replace("\r\n", "\n").lines().joinToString("\n") { line ->
    line.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^(\\s*)[-*]\\s+"), "$1• ").replace("**", "").replace("`", "")
}.trim()

/** The setting, what was found and when, in small files: the node's process and the app's both read them. */
object ReleaseCheck {
    private const val API = "https://api.github.com/repos/truongfelix/idena-mobile/releases?per_page=10"
    private const val HOST = "api.github.com"
    private const val MAX_BYTES = 1 shl 20
    private const val CHANNEL_ID = "releases"
    private const val NOTIFICATION_ID = 3

    /** The extra of the intent that opens the app on the release's page. */
    const val EXTRA_SHOW_RELEASE = "showRelease"

    /** From the node's service: once a day. */
    const val DAILY_MS = 24 * 60 * 60_000L

    /** When the app opens: not more than once an hour. */
    const val OPEN_GAP_MS = 60 * 60_000L

    private fun dir(context: Context) = File(context.filesDir, "releases").also { it.mkdirs() }
    private fun settingFile(context: Context) = File(dir(context), "check")
    private fun checkedFile(context: Context) = File(dir(context), "checked_at")
    private fun foundFile(context: Context) = File(dir(context), "found.json")
    private fun notifiedFile(context: Context) = File(dir(context), "notified")

    /** On, off, or null when the person was not asked yet. */
    fun setting(context: Context): Boolean? = runCatching { settingFile(context).readText().trim() == "on" }.getOrNull()

    fun set(context: Context, on: Boolean) {
        settingFile(context).writeText(if (on) "on" else "off")
        if (!on) {
            foundFile(context).delete()
            cancel(context)
        }
    }

    fun installed(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The release found newer than the installed build, while the check is on. */
    fun ready(context: Context): AppRelease? {
        if (setting(context) != true) return null
        val json = runCatching { JSONObject(foundFile(context).readText()) }.getOrNull() ?: return null
        val release = AppRelease(json.getString("version"), json.optString("note"), json.optString("page"))
        val current = AppVersion.parse(installed(context)) ?: return null
        return release.takeIf { AppVersion.parse(it.version)?.let { v -> v > current } == true }
    }

    /** Asks GitHub when due ([releaseCheckDue]) and notifies a new release once; the release ready, if any. */
    fun runIfDue(context: Context, gapMs: Long): AppRelease? {
        val last = runCatching { checkedFile(context).readText().trim().toLong() }.getOrNull()
        val now = System.currentTimeMillis()
        if (!releaseCheckDue(setting(context) == true, last, now, gapMs)) return ready(context)
        checkedFile(context).writeText("$now")
        val release = newerRelease(JSONArray(String(fetch(installed(context)), Charsets.UTF_8)), installed(context))
        if (release == null) {
            foundFile(context).delete()
            return null
        }
        foundFile(context).writeText(JSONObject().put("version", release.version).put("note", release.note).put("page", release.page).toString())
        val notified = runCatching { notifiedFile(context).readText().trim() }.getOrNull()
        if (notified != release.version) {
            notifiedFile(context).writeText(release.version)
            notify(context, release)
        }
        return release
    }

    /** The releases from GitHub, over HTTPS from its API host only, at most [MAX_BYTES]; no redirect is followed. */
    private fun fetch(appVersion: String): ByteArray {
        val connection = URL(API).openConnection() as HttpURLConnection
        check(connection.url.host == HOST)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "IdenaMobile/$appVersion (https://github.com/truongfelix)")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub answered $code")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("the release list is too large")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun notify(context: Context, release: AppRelease) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, t(R.string.release_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = t(R.string.release_channel_description)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).putExtra(EXTRA_SHOW_RELEASE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_idena)
            .setContentTitle(t(R.string.release_ready, release.version))
            .setContentText(t(R.string.release_ready_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
