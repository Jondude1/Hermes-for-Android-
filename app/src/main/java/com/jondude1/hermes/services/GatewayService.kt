package com.jondude1.hermes.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.jondude1.hermes.HermesApp
import com.jondude1.hermes.MainActivity
import com.jondude1.hermes.R

/**
 * Keeps the process alive so the Telegram and Discord bots stay connected while the phone is in a
 * pocket. Runs only while one of them is set up.
 */
class GatewayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, HermesApp.CHANNEL_GATEWAY)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes is reachable")
            .setContentText("Listening for Telegram and Discord messages")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching { startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        // Make sure the controller (and with it the bots) exists if Android restarted the service.
        (application as HermesApp).controller
        return START_STICKY
    }
}
