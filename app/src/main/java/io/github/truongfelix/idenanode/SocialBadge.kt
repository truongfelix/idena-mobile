package io.github.truongfelix.idenanode

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File

/*
 * Social news on the app's icon. Android shows a badge on an app's icon for its notifications, so while the app is
 * not on screen the node's service ([NodeService], its own process) reads the new blocks every few minutes and
 * posts a notification counting the activity on the identity's posts not seen yet: the count of the Social tab's
 * Notifications, of the kinds that notify. It sounds only for activity it did not announce before. Opening the Social tab
 * removes it; with "Scan blocks" off, nothing is read.
 */

/**
 * The activity not seen yet after reading the blocks after [base] up to the node's head (nothing is saved: the
 * app's files stay the app's): the scan read further, and the news on [me]'s posts after [seenThrough] of the
 * [notify] kinds, newest first.
 */
fun backgroundNews(base: SocialCache, source: SocialSource, me: String, seenThrough: Long, notify: Set<NotifyKind>): Pair<SocialCache, List<ActivityItem>> {
    val next = checkNewBlocks(base, source) {}
    return next to newActivity(socialActivity(next.feed(), me), seenThrough, notify)
}

/** What the notification shows after a check: [count] news, a sound when [alert]; [alertedThrough] is the newest block announced. */
data class BadgeUpdate(val count: Int, val alert: Boolean, val alertedThrough: Long)

/** The notification for [news], sounding only when one is after [alertedThrough] (the newest block announced); null without news. */
fun badgeUpdate(news: List<ActivityItem>, alertedThrough: Long?): BadgeUpdate? {
    if (news.isEmpty()) return null
    val newest = news.maxOf { it.height }
    val alert = alertedThrough == null || newest > alertedThrough
    return BadgeUpdate(news.size, alert, maxOf(newest, alertedThrough ?: newest))
}

/** The notification of the Social news (see the top of this file). */
object SocialBadge {
    private const val CHANNEL_ID = "social"
    private const val NOTIFICATION_ID = 2

    /** The extra of the intent that opens the app on the Social tab's Notifications. */
    const val EXTRA_SHOW_ACTIVITY = "showSocialActivity"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Social", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "New likes, replies and tips on your posts, also counted on the app's icon"
        channel.setShowBadge(true)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, text: String, update: BadgeUpdate) {
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).putExtra(EXTRA_SHOW_ACTIVITY, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_idena)
            .setContentTitle("Idena Social")
            .setContentText(text)
            .setNumber(update.count)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(!update.alert)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    /** Whether the app is on screen: it shows its news itself then. */
    private fun appShown(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .any { it.processName == context.packageName && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }

    private fun alertedFile(context: Context) = File(context.filesDir, "social/badge_alerted")

    // The scan as the last check left it, kept in the node's process only.
    private var scanned: SocialCache? = null

    /** One check of the node's service: reads the new blocks and shows, updates or removes the notification. */
    @Synchronized
    fun check(context: Context) {
        val notify = SocialStore.notifyKinds(context)
        if (!SocialStore.isScanning(context) || notify.isEmpty()) {
            clear(context)
            return
        }
        if (appShown(context)) return
        val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
        if ((rpc.call("bcn_syncing") as JSONObject).optBoolean("syncing") || rpc.ceremony().period != "None") return
        // Before the first scan in the app nothing is known of the posts, and nothing is news.
        val seenThrough = SocialStore.seenThrough(context) ?: return
        val saved = SocialStore.load(context)
        val base = listOfNotNull(scanned, saved).maxByOrNull { it.high } ?: return
        val me = (rpc.call("dna_getCoinbaseAddr") as String).lowercase()
        val (next, news) = backgroundNews(base, NodeSocialSource(rpc), me, seenThrough, notify)
        scanned = next
        val alerted = runCatching { alertedFile(context).readText().trim().toLong() }.getOrNull()
        val update = badgeUpdate(news, alerted)
        if (update == null) {
            clear(context)
            return
        }
        val names = peopleNames(SocialStore.people(context))
        post(context, noticeText(news) { displayName(it, names) }, update)
        if (update.alertedThrough != alerted) runCatching { alertedFile(context).writeText(update.alertedThrough.toString()) }
    }
}
