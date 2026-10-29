package com.jondude1.hermes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.core.ErrorLog

class HermesApp : Application() {
    lateinit var controller: AgentController
        private set

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_AGENT, "Agent tasks", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Messages from Hermes", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_GATEWAY, "Telegram and Discord connection", NotificationManager.IMPORTANCE_MIN))
        controller = AgentController(this, Settings(this))
    }

    companion object {
        const val CHANNEL_AGENT = "agent"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_GATEWAY = "gateway"
    }
}
