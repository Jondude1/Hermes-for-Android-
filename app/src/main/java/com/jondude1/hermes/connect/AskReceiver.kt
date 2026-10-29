package com.jondude1.hermes.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jondude1.hermes.HermesApp
import com.jondude1.hermes.agent.Channel
import com.jondude1.hermes.core.ErrorLog
import kotlinx.coroutines.launch

/**
 * The "Ask Hermes" API for Jon's other apps (DeskGlass, DriveDeck, Orbit...). They send a broadcast:
 *
 *   action  com.jondude1.hermes.ASK
 *   package com.jondude1.hermes
 *   extras  key        the Ask key from Hermes Settings (required)
 *           prompt     what to do or ask
 *           source     the calling app's name, e.g. "DriveDeck"
 *           reply_package / reply_action   where to send the answer
 *           request_id echoed back so the caller can match replies
 *
 * Hermes runs it as one more channel of the same mind and broadcasts back an intent with
 * reply_action to reply_package, carrying request_id, ok (boolean) and reply (text).
 */
class AskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ASK) return
        val app = context.applicationContext as HermesApp
        val c = app.controller
        val replyPkg = intent.getStringExtra("reply_package").orEmpty()
        val replyAction = intent.getStringExtra("reply_action").orEmpty()
        val requestId = intent.getStringExtra("request_id").orEmpty()
        val source = intent.getStringExtra("source").orEmpty().take(30).ifBlank { "another app" }

        fun reply(ok: Boolean, text: String) {
            if (replyPkg.isBlank() || replyAction.isBlank()) return
            runCatching {
                context.sendBroadcast(
                    Intent(replyAction).setPackage(replyPkg)
                        .putExtra("request_id", requestId).putExtra("ok", ok).putExtra("reply", text)
                )
            }.onFailure { ErrorLog.record("Couldn't reply to $replyPkg", it) }
        }

        val key = intent.getStringExtra("key").orEmpty()
        if (key.isBlank() || key != c.settings.askKey) {
            ErrorLog.record("Rejected an Ask from $source: wrong or missing key")
            reply(false, "Hermes refused: the Ask key is wrong.")
            return
        }
        val prompt = intent.getStringExtra("prompt").orEmpty().trim()
        if (prompt.isEmpty()) {
            reply(false, "No prompt given.")
            return
        }
        c.gateways.scope.launch {
            val text = runCatching {
                c.runRemote(Channel.OtherApp, "app-${source.lowercase()}", "[from $source] $prompt")
            }.getOrElse { "Hermes failed: ${it.message}" }
            reply(true, text)
        }
    }

    companion object {
        const val ACTION_ASK = "com.jondude1.hermes.ASK"
    }
}
