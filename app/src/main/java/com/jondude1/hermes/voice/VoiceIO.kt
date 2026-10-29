package com.jondude1.hermes.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.agent.OrbState
import java.util.Locale

/** Speech in via the system recognizer, speech out via Android TTS. Main thread only. */
class VoiceIO(private val ctx: Context, private val controller: AgentController) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            controller.statusLine = "Speech recognition isn't available on this phone."
            return
        }
        stopSpeaking()
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                controller.orb = OrbState.Listening
                controller.partial = ""
                controller.statusLine = "Listening"
            }

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB runs roughly -2..10; fold it into 0..1 for the orb
                controller.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                controller.partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                done()
                if (text.isNotBlank()) controller.submit(text, viaVoice = true)
            }

            override fun onError(error: Int) {
                done()
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // Silence ends a hands-free conversation instead of listening forever.
                    controller.endConversation()
                } else {
                    controller.endConversation()
                    controller.statusLine = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Hermes needs the microphone permission."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Try again."
                        else -> "Couldn't hear that. Try again."
                    }
                }
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        )
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    private fun done() {
        controller.level = 0f
        controller.partial = ""
        if (controller.orb == OrbState.Listening) controller.orb = OrbState.Idle
        if (controller.statusLine == "Listening") controller.statusLine = ""
    }

    fun speak(text: String, pitch: Float = 1f, rate: Float = 1f) {
        val clean = text.replace(Regex("[*_#`>|]"), "").replace(Regex("https?://\\S+"), "a link").take(3500)
        val engine = tts ?: TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }.also { tts = it }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { controller.orb = OrbState.Speaking } }
            override fun onDone(utteranceId: String?) { main.post { endSpeaking(); controller.onSpokenReplyDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { endSpeaking() } }
        })
        // The engine may still be initializing on first use; retry briefly instead of dropping the reply.
        if (!ttsReady) {
            main.postDelayed({ if (ttsReady) engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hermes") }, 700)
        } else {
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hermes")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        endSpeaking()
    }

    private fun endSpeaking() {
        if (controller.orb == OrbState.Speaking) controller.orb = OrbState.Idle
    }
}
