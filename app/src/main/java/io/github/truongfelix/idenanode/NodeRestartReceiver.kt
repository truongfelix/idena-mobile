package io.github.truongfelix.idenanode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the node for its watchdog (NodeWatch.kt), in the app's main process: the node's process, which found its
 * node stopped, cannot restart itself, as the restart ends it ([NodeService.restart]).
 */
class NodeRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        NodeService.restart(context) { pending.finish() }
    }
}
