package com.jondude1.hermes.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.UUID

enum class OrbState { Idle, Listening, Thinking, Acting, Speaking }

enum class TaskStatus { Running, WaitingForYou, Done, Failed, Stopped }

class TaskStep(val label: String, val at: Long = System.currentTimeMillis())

/** One thing the user asked for. Shown in the task tray. */
class AgentTask(val title: String) {
    val id: String = UUID.randomUUID().toString()
    var status by mutableStateOf(TaskStatus.Running)
    var result by mutableStateOf("")
    val steps = mutableStateListOf<TaskStep>()
    val startedAt = System.currentTimeMillis()
    var usedScreen = false
    var job: Job? = null
}

// ---- Generative UI -------------------------------------------------------------------------
// The agent never sends layout. It sends a small, validated spec; the app renders it with its own
// components. Unknown kinds fall back to "info".

class CardItem(
    val label: String,
    val detail: String = "",
    val value: String = "",
    val done: Boolean = false,
)

class CardAction(val id: String, val label: String, val style: String = "quiet")

class AgentCard(
    val id: String,
    val kind: String, // info | list | choice | metric | steps
    val title: String,
    val body: String,
    val items: List<CardItem>,
    val actions: List<CardAction>,
) {
    var chosen by mutableStateOf<String?>(null)
}

sealed interface FeedItem { val key: String }
class UserSaid(val text: String) : FeedItem { override val key: String = UUID.randomUUID().toString() }
class AgentSaid(val text: String, val who: String? = null) : FeedItem { override val key: String = UUID.randomUUID().toString() }
class CardShown(val card: AgentCard) : FeedItem { override val key: String get() = card.id }

class Approval(
    val title: String,
    val detail: String,
    val irreversible: Boolean,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)
