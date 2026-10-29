package com.jondude1.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jondude1.hermes.core.ErrorLog
import java.security.SecureRandom

/** User settings. Keys and tokens live in Keystore-backed encrypted storage. */
class Settings(ctx: Context) {
    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            ctx,
            "hermes_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // A broken Keystore entry (rare, usually after a restore) would otherwise crash every launch.
        ErrorLog.record("Encrypted settings couldn't open; secrets need to be entered again", it)
        ctx.deleteSharedPreferences("hermes_secure")
        EncryptedSharedPreferences.create(
            ctx,
            "hermes_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun str(key: String, def: String = "") = prefs.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = prefs.edit().putString(key, v).apply()
    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    // ---- the brain ----
    var apiKey by mutableStateOf(str("api_key")); private set
    var model by mutableStateOf(str("model", DEFAULT_MODEL)); private set
    var fastModel by mutableStateOf(str("fast_model", DEFAULT_FAST_MODEL)); private set
    var webSearch by mutableStateOf(prefs.getBoolean("web_search", true)); private set
    var maxSteps by mutableStateOf(prefs.getInt("max_steps", 25)); private set
    /** economy: Haiku only. balanced: Haiku unless the request looks hard. max: the main model always. */
    var brainMode by mutableStateOf(str("brain_mode", "balanced")); private set
    var monthlyBudget by mutableStateOf(prefs.getFloat("budget", 10f)); private set

    // ---- voice ----
    var speakReplies by mutableStateOf(prefs.getBoolean("speak", true)); private set
    var conversationMode by mutableStateOf(prefs.getBoolean("conversation", true)); private set

    // ---- home screen ----
    var immersive by mutableStateOf(prefs.getBoolean("immersive", true)); private set

    // ---- connections ----
    var haUrl by mutableStateOf(str("ha_url")); private set
    var haToken by mutableStateOf(str("ha_token")); private set
    var telegramToken by mutableStateOf(str("tg_token")); private set
    var telegramChats by mutableStateOf(str("tg_chats")); private set     // comma-separated chat ids
    var discordToken by mutableStateOf(str("dc_token")); private set
    var discordUsers by mutableStateOf(str("dc_users")); private set     // comma-separated user ids
    var remoteScreen by mutableStateOf(prefs.getBoolean("remote_screen", false)); private set
    var askKey by mutableStateOf(str("ask_key").ifBlank { newKey().also { putStr("ask_key", it) } }); private set

    fun updateApiKey(v: String) { apiKey = v.trim(); putStr("api_key", apiKey) }
    fun updateModel(v: String) { model = v.trim(); putStr("model", model) }
    fun updateFastModel(v: String) { fastModel = v.trim(); putStr("fast_model", fastModel) }
    fun updateWebSearch(v: Boolean) { webSearch = v; putBool("web_search", v) }
    fun updateMaxSteps(v: Int) { maxSteps = v.coerceIn(5, 100); prefs.edit().putInt("max_steps", maxSteps).apply() }
    fun updateBrainMode(v: String) { brainMode = v; putStr("brain_mode", v) }
    fun updateBudget(v: Float) { monthlyBudget = v.coerceIn(1f, 500f); prefs.edit().putFloat("budget", monthlyBudget).apply() }
    fun updateSpeak(v: Boolean) { speakReplies = v; putBool("speak", v) }
    fun updateConversation(v: Boolean) { conversationMode = v; putBool("conversation", v) }
    fun updateImmersive(v: Boolean) { immersive = v; putBool("immersive", v) }
    fun updateHaUrl(v: String) { haUrl = v.trim().trimEnd('/'); putStr("ha_url", haUrl) }
    fun updateHaToken(v: String) { haToken = v.trim(); putStr("ha_token", haToken) }
    fun updateTelegramToken(v: String) { telegramToken = v.trim(); putStr("tg_token", telegramToken) }
    fun updateTelegramChats(v: String) { telegramChats = v.trim(); putStr("tg_chats", telegramChats) }
    fun updateDiscordToken(v: String) { discordToken = v.trim(); putStr("dc_token", discordToken) }
    fun updateDiscordUsers(v: String) { discordUsers = v.trim(); putStr("dc_users", discordUsers) }
    fun updateRemoteScreen(v: Boolean) { remoteScreen = v; putBool("remote_screen", v) }
    fun rotateAskKey() { askKey = newKey(); putStr("ask_key", askKey) }

    fun idList(v: String): Set<String> = v.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun newKey(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_FAST_MODEL = "claude-haiku-5-5"
    }
}
