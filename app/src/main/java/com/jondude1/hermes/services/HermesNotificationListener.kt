package com.jondude1.hermes.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps a short in-memory list of recent notifications so the agent can answer "what did I miss?". */
class HermesNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications.sortedBy { it.postTime }.forEach { add(it) } }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = add(sbn)

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        recent.add(0, "$app: $title - $text".take(220))
        while (recent.size > 40) recent.removeAt(recent.size - 1)
    }

    companion object {
        @Volatile var connected = false
        val recent = CopyOnWriteArrayList<String>()
    }
}
