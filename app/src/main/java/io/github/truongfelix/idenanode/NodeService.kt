package io.github.truongfelix.idenanode

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.truongfelix.idenanode.go.mobile.Mobile
import java.io.File
import java.text.NumberFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The node's notification: its block and peers, what it says it does while it syncs, the step of its start while
 * only its first RPC answers ([startPhase] not null), or that it does not answer, after [PEER_GRACE_MS].
 */
fun notificationText(status: NodeStatus?, startPhase: StartPhase?, runningForMs: Long?): String {
    if (status == null) {
        return when {
            startPhase != null -> startLabel(startPhase, runningForMs)
            runningForMs == null || runningForMs < PEER_GRACE_MS -> StartPhase.Starting.label
            else -> t(R.string.status_no_answer)
        }
    }
    val format = NumberFormat.getIntegerInstance()
    val block = when {
        status.syncing && status.message.isNotEmpty() -> status.message
        status.syncing && status.highestBlock > 0 -> t(R.string.node_notification_syncing, format.format(status.currentBlock), format.format(status.highestBlock))
        else -> t(R.string.node_notification_block, format.format(status.currentBlock))
    }
    return tp(R.plurals.node_notification_peers, status.peers, block, status.peers)
}

/**
 * Runs the node in the `:node` process while sync is switched on, as a foreground service: it keeps
 * running when the user leaves the app or locks the phone. Stopping ends the process, which is how
 * the node stops (the desktop app ends the node process the same way); the node's databases recover
 * from their journals at the next start.
 */
class NodeService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val routerExecutor = Executors.newSingleThreadExecutor()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val socialExecutor = Executors.newSingleThreadExecutor()
    // The notification's reads: the node's start runs on [executor] until the node runs.
    private val statusExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    // A read of a node that takes connections but does not answer waits for its timeouts: one read at a time.
    private val reading = AtomicBoolean(false)
    // The watchdog ([NodeWatch]), from the node's start; [freezeHandled] once it found the node stopped, until it
    // answers again; [restarting] once it asked the app for the restart that ends this process.
    private var watch: NodeWatch? = null
    @Volatile private var freezeHandled = false
    @Volatile private var restarting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNode()
            return START_NOT_STICKY
        }
        // A null intent is Android restarting the service after ending its process. Without a key the
        // node would create a random one that the app cannot back up: it does not start.
        if (!NodeSettings.isSyncEnabled(this) || !NodeSettings.hasKey(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(notification(StartPhase.Starting.label))
        if (!started) {
            started = true
            acquireLocks()
            watch = NodeWatch(SystemClock.uptimeMillis())
            executor.execute { runNode() }
            handler.post(updateNotification)
        }
        if (intent?.action == ACTION_ROUTER_SEARCH) searchExecutor.execute { RouterSearch.run(this) }
        if (intent?.action == ACTION_REPORT) {
            thread(name = "node-report") {
                runCatching { NodeReports.write(this, "Made from the app.", NodeReports.collectGoroutines(this)) }
                    .onFailure { Log.w(TAG, "Node report: $it") }
            }
        }
        return START_STICKY
    }

    private fun runNode() {
        val dir = NodeSettings.nodeDir(this)
        dir.mkdirs()
        NodeSettings.startErrorFile(this).delete()
        trimLog(nodeLogFile(this))
        // The screens count the wait for a first peer from here.
        runCatching { NodeSettings.startedAtFile(this).writeText(System.currentTimeMillis().toString()) }
        // The screen compares them with the user's choices: a change applies from the next start.
        val options = NodeSettings.options(this)
        NodeSettings.setRunningOptions(this, options)
        val error = startNode(dir, NodeSettings.nodeConfig(this, options))
        if (error.isNotEmpty()) {
            Log.e(TAG, "Node start failed: $error")
            NodeSettings.startErrorFile(this).writeText(error)
            NodeSettings.setSyncEnabled(this, false)
            handler.post { stopNode() }
            return
        }
        handler.postDelayed(routerUpkeep, ROUTER_UPKEEP_FIRST_MS)
        handler.postDelayed(socialCheck, SOCIAL_CHECK_FIRST_MS)
        handler.postDelayed(releaseCheck, RELEASE_CHECK_FIRST_MS)
    }

    /** Starts the node with its key ([NodeKeys]); the error, empty once the node runs. */
    private fun startNode(dir: File, config: String): String {
        val key = try {
            NodeKeys.files(this).read() ?: return t(R.string.node_no_key)
        } catch (e: Exception) {
            return t(R.string.node_key_not_read, "${e.message ?: e}")
        }
        return try {
            Mobile.start(dir.absolutePath, config, key)
        } finally {
            key.fill(0)
        }
    }

    /** The Social news on the app's icon while the app is not on screen ([SocialBadge]). */
    private val socialCheck: Runnable = object : Runnable {
        override fun run() {
            socialExecutor.execute {
                runCatching { SocialBadge.check(this@NodeService) }.onFailure { Log.w(TAG, "Social check: $it") }
            }
            handler.postDelayed(this, SOCIAL_CHECK_INTERVAL_MS)
        }
    }

    /** New versions, once turned on: GitHub asked once a day ([ReleaseCheck]). */
    private val releaseCheck: Runnable = object : Runnable {
        override fun run() {
            socialExecutor.execute {
                runCatching { ReleaseCheck.runIfDue(this@NodeService, ReleaseCheck.DAILY_MS) }.onFailure { Log.w(TAG, "Release check: $it") }
            }
            handler.postDelayed(this, RELEASE_CHECK_INTERVAL_MS)
        }
    }

    /** Keeps the router opening the user made in the app ([RouterPortUpkeep]) while the node runs. */
    private val routerUpkeep: Runnable = object : Runnable {
        override fun run() {
            routerExecutor.execute {
                runCatching { RouterPortUpkeep.run(this@NodeService) }.onFailure { Log.w(TAG, "Router port upkeep: $it") }
            }
            handler.postDelayed(this, ROUTER_UPKEEP_INTERVAL_MS)
        }
    }

    /**
     * Refreshes the notification with the start's step, then the sync progress, from the node's start on; its reads
     * are the watchdog's ([NodeWatch]).
     */
    private val updateNotification: Runnable = object : Runnable {
        override fun run() {
            if (reading.compareAndSet(false, true)) {
                statusExecutor.execute {
                    try {
                        read()
                    } finally {
                        reading.set(false)
                    }
                }
            }
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }

        private fun read() {
            Txt.refresh(this@NodeService)
            val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(this@NodeService))
            val status = runCatching { rpc.status() }.getOrNull()
            val startPhase = if (status == null) readStartPhase(this@NodeService, rpc) else null
            val now = SystemClock.uptimeMillis()
            val answered = status != null || startPhase != null
            watch?.let { if (answered) it.answered(now) else it.failed() }
            if (answered && !restarting) freezeHandled = false
            val silentFor = watch?.takeIf { !freezeHandled && it.frozen(now) }?.silentFor(now)
            if (silentFor != null) {
                freezeHandled = true
                thread(name = "node-freeze") { handleFreeze(silentFor) }
            }
            val text = if (restarting) t(R.string.node_restarting_frozen) else notificationText(status, startPhase, NodeSettings.runningForMs(this@NodeService))
            handler.post {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    /**
     * The node did not answer for [silentFor] ms: writes a node report, then has the app restart the node
     * ([NodeRestartReceiver]: this process ends with it), unless it restarted it [MAX_RESTARTS] times lately.
     */
    private fun handleFreeze(silentFor: Long) {
        val now = System.currentTimeMillis()
        val allowed = restartAllowed(now, NodeReports.restarts(this))
        val minutes = silentFor / 60_000
        val why = "The node had not answered for $minutes min. " +
            if (allowed) "The app restarted it." else "The app did not restart it: it had restarted it $MAX_RESTARTS times in ${RESTART_WINDOW_MS / 3_600_000} h."
        Log.w(TAG, why)
        runCatching { NodeReports.write(this, why, NodeReports.collectGoroutines(this), now) }.onFailure { Log.w(TAG, "Node report: $it") }
        if (allowed) {
            restarting = true
            runCatching { NodeReports.recordRestart(this, now) }
            sendBroadcast(Intent(this, NodeRestartReceiver::class.java))
        }
    }

    private fun stopNode() {
        handler.removeCallbacks(updateNotification)
        handler.removeCallbacks(routerUpkeep)
        handler.removeCallbacks(socialCheck)
        handler.removeCallbacks(releaseCheck)
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (started) {
            // The node cannot be stopped inside its process: end the process. The next start runs in
            // a new one.
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdenaNode:sync")
            .apply { acquire() }
        // Keeps Wi-Fi awake with the screen off; mobile data does not need a lock.
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IdenaNode:sync")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // The channel of 0.1.74 and earlier counted the node's notification on the app's icon; a channel's badge
        // cannot be changed once made.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(CHANNEL_ID, t(R.string.node_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = t(R.string.node_channel_description)
        // The icon counts the Social news only.
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        SocialBadge.createChannel(this)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_idena)
            .setContentTitle(t(R.string.node_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, t(R.string.node_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "IdenaNode"
        private const val CHANNEL_ID = "node_status"
        private const val OLD_CHANNEL_ID = "node"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 15_000L
        private const val ROUTER_UPKEEP_FIRST_MS = 60_000L
        private const val ROUTER_UPKEEP_INTERVAL_MS = 5 * 60_000L
        private const val SOCIAL_CHECK_FIRST_MS = 3 * 60_000L
        private const val SOCIAL_CHECK_INTERVAL_MS = 10 * 60_000L
        private const val RELEASE_CHECK_FIRST_MS = 5 * 60_000L
        private const val RELEASE_CHECK_INTERVAL_MS = 60 * 60_000L
        const val ACTION_STOP = "io.github.truongfelix.idenanode.STOP"
        const val ACTION_ROUTER_SEARCH = "io.github.truongfelix.idenanode.ROUTER_SEARCH"
        const val ACTION_REPORT = "io.github.truongfelix.idenanode.REPORT"

        /** Asks the running node's process for a node report ([NodeReports]); nothing without a node process. */
        fun makeReport(context: Context) {
            if (!nodeProcessRuns(context)) return
            context.startService(Intent(context, NodeService::class.java).setAction(ACTION_REPORT))
        }

        /** Asks the running node's process to look for the router of this Wi-Fi ([RouterSearch]). */
        fun searchRouter(context: Context) {
            if (!NodeSettings.isSyncEnabled(context) || !NodeSettings.hasKey(context)) return
            ContextCompat.startForegroundService(context, Intent(context, NodeService::class.java).setAction(ACTION_ROUTER_SEARCH))
        }

        /** Starts the node; does nothing without a key (the screen keeps the switch off then). */
        fun start(context: Context) {
            if (!NodeSettings.hasKey(context)) return
            NodeSettings.apiKey(context)
            NodeSettings.setSyncEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, NodeService::class.java))
        }

        fun stop(context: Context) {
            NodeSettings.setSyncEnabled(context, false)
            context.startService(Intent(context, NodeService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Restarts the node, for a setting it reads at its start or a node that stopped answering: ends the node
         * process with sync left on, waits until the process is gone (ends it if its stop did not), then starts the
         * node in a new one; [done] runs after.
         */
        fun restart(context: Context, done: () -> Unit = {}) {
            val app = context.applicationContext
            thread(name = "node-restart") {
                try {
                    // A stop sent with no node process would start one just to stop nothing.
                    if (nodeProcessRuns(app)) app.startService(Intent(app, NodeService::class.java).setAction(ACTION_STOP))
                    val deadline = SystemClock.elapsedRealtime() + RESTART_TIMEOUT_MS
                    while (nodeProcessRuns(app) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
                    nodeProcessPid(app)?.let {
                        Log.w(TAG, "The node process did not stop: ending it")
                        Process.killProcess(it)
                        Thread.sleep(1_000)
                    }
                    // From the background, Android lets the app start the node only while its battery
                    // optimization is off (the Node card says so otherwise).
                    runCatching { start(app) }.onFailure { Log.w(TAG, "Node restart: $it") }
                } finally {
                    done()
                }
            }
        }

        private const val RESTART_TIMEOUT_MS = 15_000L

        private fun nodeProcessRuns(context: Context): Boolean = nodeProcessPid(context) != null

        private fun nodeProcessPid(context: Context): Int? =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.processName == context.packageName + ":node" }?.pid
    }
}
