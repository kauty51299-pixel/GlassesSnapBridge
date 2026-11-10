package com.sunny.glassesbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Private callback target; receiving a callback does not mean a Snap was sent. */
class SnapShareResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.sunny.glassesbridge.SNAP_SHARE_RESULT") {
            context.getSharedPreferences("snap_sharing", Context.MODE_PRIVATE).edit()
                .putLong("last_callback_at", System.currentTimeMillis()).apply()
        }
    }
}
