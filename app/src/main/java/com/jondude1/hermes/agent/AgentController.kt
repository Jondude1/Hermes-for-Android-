package com.jondude1.hermes.agent

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jondude1.hermes.HermesApp
import com.jondude1.hermes.MainActivity
import com.jondude1.hermes.R
import com.jondude1.hermes.Settings
import com.jondude1.hermes.connect.Gateways
import com.jondude1.hermes.connect.HomeAssistant
import com.jondude1.hermes.connect.Mcp
import com.jondude1.hermes.core.Activity
import com.jondude1.hermes.core.ErrorLog
import com.jondude1.hermes.core.JsonFile
import com.jondude1.hermes.core.Memory
import com.jondude1.hermes.core.Persona
import com.jondude1.hermes.core.Personas
import com.jondude1.hermes.core.Schedule
import com.jondude1.hermes.core.Schedules
import com.jondude1.hermes.core.Skills
import com.jondude1.hermes.core.Usage
import com.jondude1.hermes.core.objects
import com.jondude1.hermes.services.AgentRunService
import com.jondude1.hermes.services.HermesAccessibilityService
import com.jondude1.hermes.voice.VoiceIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The one mind. Every way in (the home screen, voice, Telegram, Discord, scheduled bots, other apps)
 * comes through here and shares the same memory, skills, personas and connections. Each source
 * keeps its own conversation thread; a shared activity log keeps them aware of each other.
 *
 * Runs on the main dispatcher so Compose state is only touched from one thread; network and blocking
 * work hop to IO inside their own calls.
 */
class AgentController(val app: Application, val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine by lazy { AgentEngine(this) }

    val memory = Memory(app).also { it.seedIfNew() }
    val skills = Skills(app)
    val personas = Personas(app)
    val schedules = Schedules(app)
    val mcp = Mcp(app)
    val home = HomeAssistant(settings)
    val usage = Usage(app)
    val activity = Activity(app)
    val voice = VoiceIO(app, this)
    val gateways = Gateways(this)

    val tasks = mutableStateListOf<AgentTask>()
    val feed = mutableStateListOf<FeedItem>()

    var orb by mutableStateOf(OrbState.Idle)
    var level by mutableFloatStateOf(0f)        // mic level 0..1, drives the sphere while listening
    var partial by mutableStateOf("")           // live transcript
    var statusLine by mutableStateOf("")        // what the agent is doing right now
    var approval by mutableStateOf<Approval?>(null)
    var resumeTick by mutableIntStateOf(0)      // bumped on resume so Settings re-checks permissions
    var inConversation by mutableStateOf(false) // hands-free voice back-and-forth
        private set
    var appVisible = true
        private set

    val overBudget: Boolean get() = usage.monthCost >= settings.monthlyBudget

    private val sessions = HashMap<String, Session>()
    private val locks = HashMap<String, Mutex>()
    private val pendingChoices = LinkedHashMap<String, CompletableDeferred<String>>()
    private val approvalLock = Mutex()

    init {
        restoreFeed()
        schedules.bookAll()
        scope.launch { runCatching { mcp.refreshAll() } }
        gateways.startIfConfigured()
    }

    // ---- input from the home screen and voice ------------------------------------------------

    fun submit(text: String, viaVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        partial = ""

        // A pending ask_user question takes the reply instead of starting a new task.
        val pending = pendingChoices.entries.firstOrNull()
        if (pending != null) {
            pendingChoices.remove(pending.key)
            addFeed(UserSaid(t))
            pending.value.complete(t)
            return
        }

        addFeed(UserSaid(t))
        if (settings.apiKey.isBlank()) {
            addFeed(AgentSaid("Add your Anthropic API key in Settings and I'm ready."))
            return
        }
        if (viaVoice && endsConversation(t)) {
            inConversation = false
            addFeed(AgentSaid("Okay."))
            return
        }
        voice.stopSpeaking()
        if (viaVoice && settings.conversationMode) inConversation = true

        val channel = if (viaVoice) Channel.Voice else Channel.App
        val persona = personas.active
        val task = AgentTask(t.take(60))
        tasks.add(0, task)
        keepAlive()
        task.job = scope.launch {
            val reply = runGuarded(task, MAIN_SESSION, channel, persona, t)
            if (task.status != TaskStatus.Stopped) addFeed(AgentSaid(reply, persona.takeIf { it.id != Personas.DEFAULT_ID }?.name))
            if (task.status == TaskStatus.Done && viaVoice && settings.speakReplies) {
                voice.speak(reply, persona.voicePitch, persona.voiceRate)
            } else if (viaVoice) {
                inConversation = false
            }
        }
    }

    /** Called by VoiceIO when a spoken reply finishes. Keeps a hands-free conversation going. */
    fun onSpokenReplyDone() {
        if (inConversation && settings.conversationMode && appVisible && tasks.none { it.status == TaskStatus.Running }) {
            voice.startListening()
        }
    }

    fun endConversation() {
        inConversation = false
        voice.stopListening()
        voice.stopSpeaking()
    }

    private fun endsConversation(t: String): Boolean {
        val s = t.lowercase().trim().trimEnd('.', '!')
        return s in setOf("stop", "that's all", "that is all", "thanks", "thank you", "never mind", "nevermind", "goodbye", "bye", "we're done")
    }

    fun toggleListening() {
        if (orb == OrbState.Listening) {
            voice.stopListening()
            inConversation = false
        } else voice.startListening()
    }

    fun stopTask(task: AgentTask) {
        task.job?.cancel()
    }

    fun stopAll() {
        inConversation = false
        tasks.filter { it.status == TaskStatus.Running || it.status == TaskStatus.WaitingForYou }.forEach { it.job?.cancel() }
    }

    fun clearFinished() {
        tasks.removeAll { it.status != TaskStatus.Running && it.status != TaskStatus.WaitingForYou }
    }

    /** Starts a fresh conversation thread on the home screen. Memory and skills are untouched. */
    fun newConversation() {
        stopAll()
        session(MAIN_SESSION).apply { history.clear(); summary = "" }
        saveSession(MAIN_SESSION)
        feed.clear()
        saveFeed()
    }

    fun selectPersona(id: String) {
        personas.select(id)
        addFeed(AgentSaid("${personas.active.name} here.", personas.active.name.takeIf { id != Personas.DEFAULT_ID }))
    }

    // ---- other ways in ------------------------------------------------------------------------

    /** Telegram, Discord and other apps. Returns the reply text to send back. */
    suspend fun runRemote(channel: Channel, sessionKey: String, text: String): String = withContext(Dispatchers.Main) {
        if (settings.apiKey.isBlank()) return@withContext "Hermes isn't set up yet: no API key on the phone."
        val task = AgentTask("[${channel.label}] ${text.take(48)}")
        tasks.add(0, task)
        keepAlive()
        task.job = currentCoroutineContext()[Job]
        runGuarded(task, sessionKey, channel, personas.active, text)
    }

    /** A scheduled bot's run. Delivers the result the way the schedule asks, and returns it. */
    suspend fun runScheduled(s: Schedule): String = withContext(Dispatchers.Main) {
        val persona = personas.all.firstOrNull { it.id == s.persona } ?: personas.all.first()
        val task = AgentTask("⏱ ${s.name}")
        tasks.add(0, task)
        keepAlive()
        // Scheduled bots start fresh each time; yesterday's run isn't worth paying to re-read.
        val key = "schedule-${s.id}"
        session(key).apply { history.clear(); summary = "" }
        val reply = runGuarded(task, key, Channel.Schedule, persona, s.prompt)
        if (task.status == TaskStatus.Done) {
            deliver(reply, s.deliver, title = s.name)
            addFeed(AgentSaid("${s.name}: $reply", persona.name))
        }
        reply
    }

    /** A subagent: fresh context, cheap model, reports back to the run that started it. */
    suspend fun runSubagent(parent: AgentTask, brief: String, persona: Persona?): String {
        val p = persona ?: personas.all.first()
        val sub = AgentTask("Subagent: ${brief.take(40)}")
        val session = Session("sub-" + UUID.randomUUID().toString().take(6))
        noteStep(parent, "Subagent working: ${brief.take(36)}")
        val reply = engine.run(RunSpec(sub, brief, Channel.Subagent, p, session, depth = 1))
        noteStep(parent, "Subagent reported back")
        return reply
    }

    /** One place every run goes through: queuing per thread, error messages, saving, logging. */
    private suspend fun runGuarded(task: AgentTask, key: String, channel: Channel, persona: Persona, text: String): String {
        val lock = locks.getOrPut(key) { Mutex() }
        if (lock.isLocked) noteStep(task, "Waiting for the previous request")
        return lock.withLock {
            val session = session(key)
            try {
                val reply = engine.run(RunSpec(task, text, channel, persona, session))
                finish(task, TaskStatus.Done, reply)
                activity.add(key, channel.label, text, reply)
                reply
            } catch (e: CancellationException) {
                finish(task, TaskStatus.Stopped, "Stopped.")
                throw e
            } catch (e: ApiError) {
                ErrorLog.record("API error ${e.code} on '${task.title}'", e)
                finish(task, TaskStatus.Failed, e.message ?: "Claude couldn't be reached.")
                e.message ?: "Claude couldn't be reached."
            } catch (e: Throwable) {
                ErrorLog.record("Run failed: '${task.title}'", e)
                val msg = "Something went wrong: ${e.message ?: e.javaClass.simpleName}. It's in the error log in Settings."
                finish(task, TaskStatus.Failed, msg)
                msg
            } finally {
                saveSession(key)
            }
        }
    }

    // ---- progress from the engine ------------------------------------------------------------

    fun setThinking(task: AgentTask) {
        orb = OrbState.Thinking
        statusLine = "Thinking"
        syncCapsule(statusLine)
    }

    fun noteStep(task: AgentTask, label: String) {
        task.steps.add(TaskStep(label))
        orb = OrbState.Acting
        statusLine = label
        syncCapsule(label)
    }

    fun afterTool(task: AgentTask) {
        orb = OrbState.Thinking
    }

    private fun finish(task: AgentTask, status: TaskStatus, text: String) {
        task.status = status
        task.result = text
        val stillRunning = tasks.any { it.status == TaskStatus.Running || it.status == TaskStatus.WaitingForYou }
        if (!stillRunning) {
            statusLine = ""
            HermesAccessibilityService.instance?.capsule?.hide()
            runCatching { app.stopService(Intent(app, AgentRunService::class.java)) }
            if (orb != OrbState.Speaking) orb = OrbState.Idle
        }
        if (task.usedScreen && !appVisible && !stillRunning) bringToFront(goHomeFirst = true)
        while (tasks.size > 30) tasks.removeAt(tasks.lastIndex)
    }

    private fun keepAlive() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, AgentRunService::class.java)) }
    }

    // ---- generative UI -----------------------------------------------------------------------

    fun pushCard(card: AgentCard) = addFeed(CardShown(card))

    suspend fun ask(task: AgentTask, question: String, options: List<String>): String {
        val actions = options.take(4).mapIndexed { i, o -> CardAction("opt$i", o.take(40), if (i == 0) "primary" else "quiet") }
        val card = AgentCard(UUID.randomUUID().toString(), "choice", question.take(140), "", emptyList(), actions)
        val answer = CompletableDeferred<String>()
        pendingChoices[card.id] = answer
        addFeed(CardShown(card))
        task.status = TaskStatus.WaitingForYou
        statusLine = "Waiting for you"
        syncCapsule("Waiting for you")
        if (!appVisible) bringToFront()
        return try {
            answer.await()
        } finally {
            pendingChoices.remove(card.id)
            task.status = TaskStatus.Running
        }
    }

    fun onCardAction(card: AgentCard, action: CardAction) {
        card.chosen = action.label
        val pending = pendingChoices.remove(card.id)
        if (pending != null) pending.complete(action.label) else submit(action.label)
    }

    // ---- approvals ---------------------------------------------------------------------------

    /** Shows the approval sheet (and a notification if the app isn't open). Declines after 10 minutes. */
    suspend fun requestApproval(task: AgentTask, title: String, detail: String, irreversible: Boolean): Boolean =
        approvalLock.withLock {
            val a = Approval(title.take(100), detail.take(400), irreversible)
            approval = a
            task.status = TaskStatus.WaitingForYou
            statusLine = "Waiting for your OK"
            val capsule = HermesAccessibilityService.instance?.capsule
            if (!appVisible) {
                capsule?.showApproval(a.title, a.detail) { ok -> resolveApproval(a, ok) }
                notify("Hermes needs your OK", a.title, APPROVAL_NOTIFICATION, highPriority = true)
            }
            try {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { a.result.await() } ?: false
            } finally {
                approval = null
                task.status = TaskStatus.Running
                capsule?.hideApproval()
                cancelNotification(APPROVAL_NOTIFICATION)
            }
        }

    fun resolveApproval(a: Approval, ok: Boolean) {
        a.result.complete(ok)
    }

    // ---- reaching Jon ------------------------------------------------------------------------

    /** Sends a message out through the requested channels. Returns the ones that worked. */
    suspend fun deliver(message: String, via: List<String>, title: String = "Hermes"): List<String> {
        val sent = mutableListOf<String>()
        for (v in via.distinct()) {
            val ok = runCatching {
                when (v) {
                    "notify" -> notify(title, message, newNotificationId(), highPriority = true)
                    "speak" -> { withContext(Dispatchers.Main) { voice.speak(message) }; true }
                    "telegram" -> gateways.telegram.broadcast("$title: $message")
                    "discord" -> gateways.discord.broadcast("**$title**: $message")
                    else -> false
                }
            }.getOrElse { ErrorLog.record("Couldn't deliver via $v", it); false }
            if (ok) sent.add(v)
        }
        return sent
    }

    private fun notify(title: String, text: String, id: Int, highPriority: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val open = PendingIntent.getActivity(
            app, id, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, if (highPriority) HermesApp.CHANNEL_ALERTS else HermesApp.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(title.take(60))
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(id, n)
        return true
    }

    private fun cancelNotification(id: Int) {
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    private var notificationSeq = 100
    private fun newNotificationId(): Int = ++notificationSeq

    // ---- app visibility and the capsule ------------------------------------------------------

    fun onVisibility(visible: Boolean) {
        appVisible = visible
        resumeTick++
        if (visible) {
            HermesAccessibilityService.instance?.capsule?.hide()
        } else {
            if (inConversation) endConversation()
            syncCapsule(statusLine.ifBlank { "Working" })
        }
    }

    private fun syncCapsule(text: String) {
        val svc = HermesAccessibilityService.instance ?: return
        val active = tasks.any { it.status == TaskStatus.Running || it.status == TaskStatus.WaitingForYou }
        if (!appVisible && active) svc.capsule.show(text, onStop = { stopAll() }, onOpen = { bringToFront() }) else svc.capsule.hide()
    }

    fun bringToFront(goHomeFirst: Boolean = false) {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val svc = HermesAccessibilityService.instance
        val ok = runCatching { (svc ?: app).startActivity(intent) }.isSuccess
        if (!ok && goHomeFirst) svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    // ---- sessions and the feed, saved across restarts ----------------------------------------

    private fun session(key: String): Session = sessions.getOrPut(key) {
        Session(key).also { s ->
            val o = JsonFile(app, "session-$key.json").readObject()
            s.summary = o.optString("summary")
            o.optJSONArray("history")?.objects()?.let { s.history.addAll(it) }
        }
    }

    private fun saveSession(key: String) {
        val s = sessions[key] ?: return
        if (key.startsWith("sub-")) return
        val arr = JSONArray()
        s.history.forEach { arr.put(it) }
        JsonFile(app, "session-$key.json").write(JSONObject().put("summary", s.summary).put("history", arr).toString())
        if (key == MAIN_SESSION) saveFeed()
    }

    private fun addFeed(item: FeedItem) {
        feed.add(item)
        while (feed.size > MAX_FEED) feed.removeAt(0)
        if (item !is CardShown) saveFeed()
    }

    private fun saveFeed() {
        val arr = JSONArray()
        feed.takeLast(40).forEach {
            when (it) {
                is UserSaid -> arr.put(JSONObject().put("u", it.text))
                is AgentSaid -> arr.put(JSONObject().put("a", it.text).put("who", it.who ?: ""))
                is CardShown -> Unit
            }
        }
        JsonFile(app, "feed.json").write(arr.toString())
    }

    private fun restoreFeed() {
        JsonFile(app, "feed.json").readArray().objects().forEach {
            when {
                it.has("u") -> feed.add(UserSaid(it.optString("u")))
                it.has("a") -> feed.add(AgentSaid(it.optString("a"), it.optString("who").ifBlank { null }))
            }
        }
    }

    companion object {
        const val MAIN_SESSION = "main"
        private const val MAX_FEED = 80
        private const val APPROVAL_NOTIFICATION = 7
        private const val APPROVAL_TIMEOUT_MS = 10 * 60_000L
    }
}
