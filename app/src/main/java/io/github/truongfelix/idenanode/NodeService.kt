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
import java.text.NumberFormat
import java.util.concurrent.Executors
import kotlin.concurrent.thread

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
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
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
        startInForeground(notification("Starting the node…"))
        if (!started) {
            started = true
            acquireLocks()
            executor.execute { runNode() }
        }
        if (intent?.action == ACTION_ROUTER_SEARCH) searchExecutor.execute { RouterSearch.run(this) }
        return START_STICKY
    }

    private fun runNode() {
        val dir = NodeSettings.nodeDir(this)
        dir.mkdirs()
        NodeSettings.startErrorFile(this).delete()
        // The screens count the wait for a first peer from here.
        runCatching { NodeSettings.startedAtFile(this).writeText(System.currentTimeMillis().toString()) }
        // The screen compares them with the user's choices: a change applies from the next start.
        val options = NodeSettings.options(this)
        NodeSettings.setRunningOptions(this, options)
        val error = Mobile.start(dir.absolutePath, NodeSettings.nodeConfig(this, options))
        if (error.isNotEmpty()) {
            Log.e(TAG, "Node start failed: $error")
            NodeSettings.startErrorFile(this).writeText(error)
            NodeSettings.setSyncEnabled(this, false)
            handler.post { stopNode() }
            return
        }
        handler.post(updateNotification)
        handler.postDelayed(routerUpkeep, ROUTER_UPKEEP_FIRST_MS)
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

    /** Refreshes the notification with the sync progress while the node runs. */
    private val updateNotification: Runnable = object : Runnable {
        override fun run() {
            executor.execute {
                val text = try {
                    val status = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(this@NodeService)).status()
                    val format = NumberFormat.getIntegerInstance()
                    val block = if (status.syncing && status.highestBlock > 0) {
                        "Syncing ${format.format(status.currentBlock)} / ${format.format(status.highestBlock)}"
                    } else {
                        "Block ${format.format(status.currentBlock)}"
                    }
                    "$block · ${status.peers} peers"
                } catch (e: Exception) {
                    "Node is starting…"
                }
                handler.post {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    private fun stopNode() {
        handler.removeCallbacks(updateNotification)
        handler.removeCallbacks(routerUpkeep)
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
        val channel = NotificationChannel(CHANNEL_ID, "Node", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows the node while it syncs"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            .setSmallIcon(R.drawable.ic_node)
            .setContentTitle("Idena node")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "IdenaNode"
        private const val CHANNEL_ID = "node"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 15_000L
        private const val ROUTER_UPKEEP_FIRST_MS = 60_000L
        private const val ROUTER_UPKEEP_INTERVAL_MS = 5 * 60_000L
        const val ACTION_STOP = "io.github.truongfelix.idenanode.STOP"
        const val ACTION_ROUTER_SEARCH = "io.github.truongfelix.idenanode.ROUTER_SEARCH"

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
         * Restarts the node, for a setting it reads at its start: ends the node process with sync left
         * on, waits until the process is gone, then starts the node in a new one.
         */
        fun restart(context: Context) {
            val app = context.applicationContext
            thread(name = "node-restart") {
                // A stop sent with no node process would start one just to stop nothing.
                if (nodeProcessRuns(app)) app.startService(Intent(app, NodeService::class.java).setAction(ACTION_STOP))
                val deadline = SystemClock.elapsedRealtime() + RESTART_TIMEOUT_MS
                while (nodeProcessRuns(app) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
                start(app)
            }
        }

        private const val RESTART_TIMEOUT_MS = 15_000L

        private fun nodeProcessRuns(context: Context): Boolean =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.any { it.processName == context.packageName + ":node" } == true
    }
}
