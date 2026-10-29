package com.jondude1.hermes.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jondude1.hermes.services.HermesAccessibilityService
import com.jondude1.hermes.services.HermesNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class AppEntry(val label: String, val pkg: String)

object InstalledApps {
    fun list(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The accessibility service's context is the most reliably allowed to start activities
        // when Hermes is not on screen (Android restricts background launches).
        val launcher: Context = HermesAccessibilityService.instance ?: ctx
        return runCatching { launcher.startActivity(intent) }.isSuccess
    }
}

/** Starts an activity from whichever context is allowed to, on the main thread. */
private suspend fun startFrom(ctx: Context, intent: Intent): Result<Unit> = withContext(Dispatchers.Main) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launcher: Context = HermesAccessibilityService.instance ?: ctx
    runCatching { launcher.startActivity(intent) }
}

private fun hasPerm(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private val http by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
}

object DeviceTools {
    fun all(): List<HermesTool> = listOf(
        OpenApp, SetAlarm, SetTimer, CreateCalendarEvent, FindContact, SendSms,
        ReadNotifications, GetDeviceState, WebFetch, OpenUrl, OpenSettings,
    )

    private object OpenApp : HermesTool {
        override val name = "open_app"
        override val group = "phone"
        override val description = "Open an installed app by name. Fuzzy-matches the app label."
        override val inputSchema = jobj(listOf("name"), "name" to jstr("App name, e.g. 'Chrome' or 'Home Assistant'"))
        override fun stepLabel(input: JSONObject) = "Opening ${input.optString("name")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val q = input.optString("name").trim().lowercase()
            val apps = InstalledApps.list(ctx.context)
            val app = apps.firstOrNull { it.label.lowercase() == q }
                ?: apps.firstOrNull { it.label.lowercase().contains(q) }
                ?: apps.firstOrNull { q.contains(it.label.lowercase()) }
                ?: return ToolResult("No installed app matches '$q'.", isError = true)
            val ok = withContext(Dispatchers.Main) { InstalledApps.launch(ctx.context, app.pkg) }
            return if (ok) ToolResult("Opened ${app.label}.")
            else ToolResult("Android blocked launching ${app.label}. Turn on Hermes screen control in Settings and try again.", isError = true)
        }
    }

    private object SetAlarm : HermesTool {
        override val name = "set_alarm"
        override val group = "phone"
        override val description = "Set a clock alarm for a time of day (24-hour clock)."
        override val inputSchema = jobj(
            listOf("hour", "minute"),
            "hour" to jint("Hour 0-23"),
            "minute" to jint("Minute 0-59"),
            "label" to jstr("Optional label"),
        )
        override fun stepLabel(input: JSONObject) =
            "Setting alarm for %d:%02d".format(input.optInt("hour"), input.optInt("minute"))
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val h = input.optInt("hour"); val m = input.optInt("minute")
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return startFrom(ctx.context, intent).fold(
                { ToolResult("Alarm set for %d:%02d.".format(h, m)) },
                { ToolResult("Couldn't set the alarm: ${it.message}", isError = true) },
            )
        }
    }

    private object SetTimer : HermesTool {
        override val name = "set_timer"
        override val group = "phone"
        override val description = "Start a countdown timer."
        override val inputSchema = jobj(
            listOf("seconds"),
            "seconds" to jint("Duration in seconds"),
            "label" to jstr("Optional label"),
        )
        override fun stepLabel(input: JSONObject) = "Starting a ${input.optInt("seconds") / 60} minute timer"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = input.optInt("seconds")
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, s)
                .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return startFrom(ctx.context, intent).fold(
                { ToolResult("Timer started for $s seconds.") },
                { ToolResult("Couldn't start the timer: ${it.message}", isError = true) },
            )
        }
    }

    private object CreateCalendarEvent : HermesTool {
        override val name = "create_calendar_event"
        override val group = "phone"
        override val description =
            "Open a pre-filled calendar event. The person must tap Save in the calendar. Times are local, ISO format like 2026-11-03T14:30."
        override val inputSchema = jobj(
            listOf("title", "start"),
            "title" to jstr("Event title"),
            "start" to jstr("Local start, ISO e.g. 2026-11-03T14:30"),
            "end" to jstr("Local end, ISO. Defaults to one hour after start."),
            "location" to jstr("Optional location"),
        )
        override fun stepLabel(input: JSONObject) = "Drafting event: ${input.optString("title")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val zone = ZoneId.systemDefault()
            val start = runCatching { LocalDateTime.parse(input.getString("start")) }.getOrNull()
                ?: return ToolResult("Start time must look like 2026-11-03T14:30.", isError = true)
            val end = runCatching { LocalDateTime.parse(input.optString("end")) }.getOrNull() ?: start.plusHours(1)
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, input.optString("title"))
                .putExtra(CalendarContract.Events.EVENT_LOCATION, input.optString("location"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
            return startFrom(ctx.context, intent).fold(
                { ToolResult("Opened a pre-filled event. The person still has to tap Save.") },
                { ToolResult("Couldn't open the calendar: ${it.message}", isError = true) },
            )
        }
    }

    private object FindContact : HermesTool {
        override val name = "find_contact"
        override val group = "phone"
        override val description = "Look up phone numbers for a contact by name."
        override val inputSchema = jobj(listOf("name"), "name" to jstr("Full or partial contact name"))
        override fun stepLabel(input: JSONObject) = "Finding ${input.optString("name")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
            if (!hasPerm(ctx.context, Manifest.permission.READ_CONTACTS)) {
                return@withContext ToolResult("Contacts permission isn't granted. Ask the person to allow it in Hermes Settings.", isError = true)
            }
            val rows = mutableListOf<String>()
            ctx.context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${input.optString("name")}%"),
                null,
            )?.use { c ->
                while (c.moveToNext() && rows.size < 6) rows.add("${c.getString(0)}: ${c.getString(1)}")
            }
            if (rows.isEmpty()) ToolResult("No contact matches '${input.optString("name")}'.")
            else ToolResult(rows.joinToString("\n"))
        }
    }

    private object SendSms : HermesTool {
        override val name = "send_sms"
        override val group = "phone"
        override val description =
            "Send a text message. Asks the person to approve before sending. Give either a phone number or a contact name."
        override val inputSchema = jobj(
            listOf("message"),
            "to" to jstr("Phone number or contact name"),
            "message" to jstr("Message text"),
        )
        override fun stepLabel(input: JSONObject) = "Texting ${input.optString("to")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val message = input.optString("message").trim()
            if (message.isEmpty()) return ToolResult("Message is empty.", isError = true)
            if (!hasPerm(ctx.context, Manifest.permission.SEND_SMS)) {
                return ToolResult("SMS permission isn't granted. Ask the person to allow it in Hermes Settings.", isError = true)
            }
            var to = input.optString("to").trim()
            var shownName = to
            if (to.isBlank()) return ToolResult("Who should I text?", isError = true)
            if (to.any { it.isLetter() }) {
                val found = FindContact.run(JSONObject().put("name", to), ctx)
                val first = found.text.lineSequence().firstOrNull { it.contains(':') }
                    ?: return ToolResult("No contact matches '$to'.", isError = true)
                shownName = first.substringBefore(':')
                to = first.substringAfter(':').trim()
            }
            val ok = ctx.controller.requestApproval(
                ctx.task,
                "Send a text to $shownName?",
                "\"$message\"",
                irreversible = true,
            )
            if (!ok) return ToolResult("The person declined. Nothing was sent.")
            val sms = ctx.context.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(message), null, null)
            return ToolResult("Sent to $shownName.")
        }
    }

    private object ReadNotifications : HermesTool {
        override val name = "read_notifications"
        override val group = "phone"
        override val description = "Read the most recent notifications on the phone."
        override val inputSchema = jobj(emptyList())
        override fun stepLabel(input: JSONObject) = "Reading notifications"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            if (!HermesNotificationListener.connected) {
                return ToolResult("Notification access is off. Ask the person to enable it in Hermes Settings.", isError = true)
            }
            val items = HermesNotificationListener.recent.take(20)
            return if (items.isEmpty()) ToolResult("No notifications.") else ToolResult(items.joinToString("\n"))
        }
    }

    private object GetDeviceState : HermesTool {
        override val name = "get_device_state"
        override val group = "phone"
        override val description = "Current time, battery level, charging state and ringer mode."
        override val inputSchema = jobj(emptyList())
        override fun stepLabel(input: JSONObject) = "Checking the phone"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val bm = ctx.context.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val am = ctx.context.getSystemService(AudioManager::class.java)
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE h:mm a, MMM d"))
            return ToolResult("time: $now\nbattery: $level%${if (charging) " (charging)" else ""}\nringer: $ringer")
        }
    }

    private object WebFetch : HermesTool {
        override val name = "web_fetch"
        override val group = "web"
        override val description = "Fetch a web page and return its readable text (first ~8000 characters)."
        override val inputSchema = jobj(listOf("url"), "url" to jstr("Full https URL"))
        override fun stepLabel(input: JSONObject) = "Reading ${input.optString("url").removePrefix("https://").take(40)}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
            val url = input.optString("url")
            if (!url.startsWith("https://")) return@withContext ToolResult("Only https URLs are allowed.", isError = true)
            val req = Request.Builder().url(url).header("User-Agent", "Hermes/1.0 (Android)").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext ToolResult("HTTP ${r.code}", isError = true)
                val raw = r.body?.string().orEmpty()
                val text = raw
                    .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("(?s)<[^>]+>"), " ")
                    .replace(Regex("&nbsp;|&amp;|&quot;|&#39;"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                ToolResult(text.take(8000).ifBlank { "(page had no readable text)" })
            }
        }
    }

    private object OpenUrl : HermesTool {
        override val name = "open_url"
        override val group = "phone"
        override val description = "Open a link in the browser."
        override val inputSchema = jobj(listOf("url"), "url" to jstr("Full URL"))
        override fun stepLabel(input: JSONObject) = "Opening a link"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val url = input.optString("url")
            if (!(url.startsWith("https://") || url.startsWith("http://"))) {
                return ToolResult("Only http and https links can be opened.", isError = true)
            }
            return startFrom(ctx.context, Intent(Intent.ACTION_VIEW, Uri.parse(url))).fold(
                { ToolResult("Opened.") },
                { ToolResult("Couldn't open the link: ${it.message}", isError = true) },
            )
        }
    }

    private object OpenSettings : HermesTool {
        override val name = "open_settings"
        override val group = "phone"
        override val description = "Open a page of Android Settings."
        override val inputSchema = jobj(
            listOf("page"),
            "page" to jenum("Which page", "wifi", "bluetooth", "display", "sound", "apps", "battery", "location", "accessibility", "notifications", "main"),
        )
        override fun stepLabel(input: JSONObject) = "Opening ${input.optString("page")} settings"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val action = when (input.optString("page")) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            return startFrom(ctx.context, Intent(action)).fold(
                { ToolResult("Opened.") },
                { ToolResult("Couldn't open settings: ${it.message}", isError = true) },
            )
        }
    }
}
