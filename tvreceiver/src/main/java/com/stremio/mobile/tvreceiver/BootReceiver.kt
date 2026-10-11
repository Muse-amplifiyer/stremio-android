package com.stremio.mobile.tvreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Keep the control server alive across reboots so the phone can always reach the stick. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
