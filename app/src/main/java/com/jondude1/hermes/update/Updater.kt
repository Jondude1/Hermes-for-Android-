package com.jondude1.hermes.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.jondude1.hermes.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Checks the public repo's latest GitHub release and installs its APK. Builds are signed with one
 * permanent key, so Android accepts each one as an update to the last. OkHttp follows GitHub's
 * redirect to its CDN on its own.
 */
object Updater {
    private const val OWNER = "Jondude1"
    private const val REPO = "Hermes-for-Android-"
    private const val CHECK_EVERY_MS = 30 * 60 * 1000L

    class Release(val tag: String, val code: Int, val apkUrl: String)

    var available by mutableStateOf<Release?>(null)
        private set
    var dismissedTag by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(-1f) // -1 idle, 0..1 downloading
        private set
    var status by mutableStateOf("")
        private set

    private var lastCheck = 0L
    private val http = OkHttpClient()

    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        if (force) status = "Checking…"

        val found: Result<Release?> = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("GitHub answered ${r.code}")
                    val json = JSONObject(r.body?.string().orEmpty())
                    val tag = json.getString("tag_name")
                    val code = tag.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
                    val assets = json.getJSONArray("assets")
                    var apk: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
                    }
                    if (apk != null && code > BuildConfig.VERSION_CODE) Release(tag, code, apk) else null
                }
            }
        }
        found.onSuccess {
            available = it
            status = if (it == null) "You're on the latest version." else "Update ${it.tag} is ready."
        }.onFailure {
            if (force) status = "Couldn't check for updates."
        }
    }

    suspend fun downloadAndInstall(ctx: Context) {
        val release = available ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            status = "Allow installs from Hermes, then tap Update again."
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        progress = 0f
        status = "Downloading…"
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "Hermes-update.apk")
                http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) error("Download failed (${r.code})")
                    val body = r.body ?: error("Empty download")
                    val total = body.contentLength().toFloat()
                    var done = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) progress = (done / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                out
            }
        }
        progress = -1f
        file.onSuccess { apk ->
            status = "Installing…"
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { status = it.message ?: "Update failed." }
    }
}
