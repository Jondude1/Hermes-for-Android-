package com.jondude1.hermes.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jondude1.hermes.HermesApp

/** After a reboot, wakes Hermes so the messaging bots reconnect. Scheduled bots survive on their own. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            (context.applicationContext as HermesApp).controller
        }
    }
}
