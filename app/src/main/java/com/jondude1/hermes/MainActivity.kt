package com.jondude1.hermes

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.agent.OrbState
import com.jondude1.hermes.ui.HomeScreen
import com.jondude1.hermes.ui.HostActions
import com.jondude1.hermes.ui.HermesTheme
import com.jondude1.hermes.update.Updater
import kotlinx.coroutines.launch

/**
 * Home screen, assistant target and launcher icon in one. Assistant gestures arrive here with
 * EXTRA_LISTEN set and open the mic straight away.
 */
class MainActivity : ComponentActivity() {
    private val controller: AgentController get() = (application as HermesApp).controller
    private var listenAfterGrant = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        controller.resumeTick++
        if (listenAfterGrant && grants[Manifest.permission.RECORD_AUDIO] == true) controller.voice.startListening()
        listenAfterGrant = false
    }

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.resumeTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyBars(controller.settings.immersive)

        setContent {
            HermesTheme {
                HomeScreen(
                    c = controller,
                    onMic = ::onMic,
                    host = HostActions(
                        requestPermissions = { permissions.launch(it) },
                        requestRole = ::requestRole,
                        setImmersive = ::applyBars,
                    ),
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        controller.onVisibility(true)
        lifecycleScope.launch { Updater.check() }
    }

    override fun onStop() {
        controller.onVisibility(false)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars(controller.settings.immersive)
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun onMic() {
        if (controller.orb == OrbState.Listening) {
            controller.toggleListening()
            return
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            controller.voice.startListening()
        } else {
            listenAfterGrant = true
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        val wantsMic = i.getBooleanExtra(EXTRA_LISTEN, false) ||
            i.action == Intent.ACTION_ASSIST || i.action == Intent.ACTION_VOICE_COMMAND
        if (wantsMic) {
            i.removeExtra(EXTRA_LISTEN)
            window.decorView.post { onMic() }
        }
    }

    private fun requestRole(role: String) {
        val rm = getSystemService(RoleManager::class.java)
        val launched = runCatching {
            if (rm.isRoleAvailable(role) && !rm.isRoleHeld(role)) {
                roleRequest.launch(rm.createRequestRoleIntent(role))
                true
            } else false
        }.getOrDefault(false)
        if (!launched && role == RoleManager.ROLE_ASSISTANT) {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        } else if (!launched && role == RoleManager.ROLE_HOME) {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }
    }

    private fun applyBars(immersive: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_LISTEN = "com.jondude1.hermes.LISTEN"
    }
}
