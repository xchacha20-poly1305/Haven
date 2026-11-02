package sh.haven.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.haven.app.navigation.DebugNavEvents
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import javax.inject.Inject

@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {

    @Inject lateinit var connectionRepository: ConnectionRepository

    private fun handleCreateProfile(intent: Intent) {
        val label = intent.getStringExtra("label")
        if (label.isNullOrBlank()) {
            Log.e(TAG, "CREATE_PROFILE: 'label' extra is required")
            return
        }

        val connectionType = intent.getStringExtra("connectionType") ?: "SSH"
        val host = intent.getStringExtra("host") ?: ""
        val port = if (intent.hasExtra("port")) intent.getIntExtra("port", 22) else 22
        val username = intent.getStringExtra("username") ?: ""

        val profile = ConnectionProfile(
            label = label,
            connectionType = connectionType,
            host = host,
            port = port,
            username = username,
            // Password auth — debug-only path used by ADB-driven integration tests.
            // Never shipped in release builds (this whole file is src/debug/).
            sshPassword = intent.getStringExtra("sshPassword"),
            // Transport toggles. Needed so ADB-driven tests can reproduce
            // issues that only manifest over mosh or Eternal Terminal,
            // rather than being limited to plain SSH. Added for Haven#73.
            useMosh = intent.getBooleanExtra("useMosh", false),
            useEternalTerminal = intent.getBooleanExtra("useEternalTerminal", false),
            // Session manager for the terminal (tmux/zellij/screen/null).
            // Null means "run a plain shell" which is what we want for
            // terminal-state regression tests that care about the exact
            // raw byte stream rather than a multiplexer re-render.
            sessionManager = intent.getStringExtra("sessionManager"),
            destinationHash = intent.getStringExtra("destinationHash"),
            reticulumHost = intent.getStringExtra("reticulumHost") ?: "127.0.0.1",
            reticulumPort = if (intent.hasExtra("reticulumPort"))
                intent.getIntExtra("reticulumPort", 37428) else 37428,
            reticulumNetworkName = intent.getStringExtra("reticulumNetworkName"),
            reticulumPassphrase = intent.getStringExtra("reticulumPassphrase"),
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                connectionRepository.save(profile)
                Log.i(TAG, "CREATE_PROFILE: saved id=${profile.id} label='$label' type=$connectionType")
            } catch (e: Exception) {
                Log.e(TAG, "CREATE_PROFILE: failed to save profile", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleListProfiles() {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val profiles = connectionRepository.getAll()
                if (profiles.isEmpty()) {
                    Log.i(TAG, "LIST_PROFILES: no profiles found")
                } else {
                    Log.i(TAG, "LIST_PROFILES: ${profiles.size} profile(s)")
                    for (p in profiles) {
                        Log.i(TAG, "  id=${p.id} label='${p.label}' type=${p.connectionType} " +
                            "host=${p.host} port=${p.port} user=${p.username}" +
                            if (p.isReticulum) " destHash=${p.destinationHash} " +
                                "retHost=${p.reticulumHost} retPort=${p.reticulumPort}" else "")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LIST_PROFILES: failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleNavigate(intent: Intent) {
        val screen = intent.getStringExtra("screen")
        if (screen.isNullOrBlank()) {
            Log.e(TAG, "NAVIGATE: 'screen' extra is required " +
                "(connections, terminal, desktop, keys, sftp, settings)")
            return
        }

        val route = screen.lowercase()
        val validRoutes = setOf("connections", "terminal", "desktop", "keys", "sftp", "settings")
        if (route !in validRoutes) {
            Log.e(TAG, "NAVIGATE: unknown screen '$screen'. Valid: $validRoutes")
            return
        }

        Log.i(TAG, "NAVIGATE: requesting navigation to '$route'")
        DebugNavEvents.emit(route)
    }

    private lateinit var context: Context

    override fun onReceive(ctx: Context, intent: Intent) {
        context = ctx
        val action = intent.action ?: return
        Log.d(TAG, "Received action: $action")

        when (action) {
            ACTION_CREATE_PROFILE -> handleCreateProfile(intent)
            ACTION_LIST_PROFILES -> handleListProfiles()
            ACTION_NAVIGATE -> handleNavigate(intent)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    companion object {
        private const val TAG = "DebugReceiver"
        private const val ACTION_CREATE_PROFILE = "sh.haven.app.DEBUG_CREATE_PROFILE"
        private const val ACTION_LIST_PROFILES = "sh.haven.app.DEBUG_LIST_PROFILES"
        private const val ACTION_NAVIGATE = "sh.haven.app.DEBUG_NAVIGATE"
    }
}
