package com.jondude1.hermes.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.jondude1.hermes.MainActivity

/**
 * Registers Hermes as a selectable "digital assistant". Once chosen in Settings, the power-button
 * long-press, corner swipe, or "assist" gesture opens Hermes already listening.
 */
class HermesVoiceService : VoiceInteractionService()

class HermesSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HermesSession(this)
}

private class HermesSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
        startAssistantActivity(intent)
        hide()
    }
}
