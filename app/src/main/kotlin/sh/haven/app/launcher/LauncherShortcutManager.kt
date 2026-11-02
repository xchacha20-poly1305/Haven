package sh.haven.app.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LauncherShortcuts"

/** Sealed spec for the pure selection policy — the unit-testable half of [LauncherShortcutManager]. */
sealed interface LauncherShortcut {
    val label: String
    /** Stable shortcut id; prefixes keep Android's pinned-shortcut re-resolution intact. */
    val shortcutId: String
}

/** A saved connection offered as "Connect <label>". Fires the direct-dial command (no drive-by confirm — app-issued intent). */
data class ConnectionShortcut(
    val profileId: String,
    override val label: String,
) : LauncherShortcut {
    override val shortcutId: String = "connection-$profileId"
}

/** A saved workspace offered as "Open <name>". */
data class WorkspaceShortcut(
    val workspaceId: String,
    override val label: String,
) : LauncherShortcut {
    override val shortcutId: String = "workspace-$workspaceId"
}

@Singleton
class LauncherShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        combine(connectionRepository.observeAll(), workspaceRepository.observeAll(), ::Pair)
            .distinctUntilChanged()
            .onEach { (connections, workspaces) -> rebuildShortcuts(connections, workspaces) }
            .launchIn(scope)
    }

    private fun rebuildShortcuts(connections: List<ConnectionProfile>, workspaces: List<WorkspaceProfile>) {
        val dynamicBudget = try {
            val maxPerActivity = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            val manifestCount = ShortcutManagerCompat
                .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST)
                .size
                .coerceAtLeast(MANIFEST_SHORTCUT_COUNT)
            maxDynamicShortcuts(maxPerActivity, manifestCount)
        } catch (e: Exception) {
            Log.w(TAG, "shortcut limit query failed: ${e.message}")
            MAX_SHORTCUTS - MANIFEST_SHORTCUT_COUNT
        }
        val shortcuts = selectShortcuts(connections, workspaces, maxShortcuts = dynamicBudget).map { spec ->
            when (spec) {
                is ConnectionShortcut -> ShortcutInfoCompat.Builder(context, spec.shortcutId)
                    .setShortLabel(spec.label)
                    .setLongLabel(spec.label)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(connectionLaunchIntent(spec.profileId))
                    .build()
                is WorkspaceShortcut -> ShortcutInfoCompat.Builder(context, spec.shortcutId)
                    .setShortLabel(spec.label)
                    .setLongLabel(spec.label)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(workspaceLaunchIntent(spec.workspaceId))
                    .build()
            }
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Log.d(TAG, "published ${shortcuts.size} launcher shortcut(s) (budget=$dynamicBudget)")
        } catch (e: Exception) {
            // Some launchers/ROMs throw IllegalStateException when called too
            // early or locked, or IllegalArgumentException if a custom cap is
            // exceeded (#775); never crash app startup over launcher shortcuts.
            Log.w(TAG, "setDynamicShortcuts failed: ${e.message}")
        }
    }

    private fun connectionLaunchIntent(profileId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_CONNECTION
            putExtra(EXTRA_CONNECTION_ID, profileId)
            // CLEAR_TOP + SINGLE_TOP so an already-open MainActivity
            // routes through onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun workspaceLaunchIntent(workspaceId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_WORKSPACE
            putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        const val ACTION_LAUNCH_CONNECTION = "sh.haven.action.LAUNCH_CONNECTION"
        const val EXTRA_CONNECTION_ID = "sh.haven.extra.CONNECTION_ID"
        const val ACTION_LAUNCH_WORKSPACE = "sh.haven.action.LAUNCH_WORKSPACE"
        const val EXTRA_WORKSPACE_ID = "sh.haven.extra.WORKSPACE_ID"

        /**
         * Upper bound on published dynamic shortcuts. Note that Android's
         * `ShortcutService.enforceMaxActivityShortcuts` throws
         * `IllegalArgumentException` when `manifestShortcuts + dynamicShortcuts`
         * exceeds `getMaxShortcutCountPerActivity()` (which is 5 on Android 8–9
         * and 15 on Android 10+, or lower on some OEM ROMs), so the runtime
         * budget is computed via [maxDynamicShortcuts] (#775).
         */
        internal const val MAX_SHORTCUTS = 5

        /**
         * Number of static `<shortcut>` entries declared in `res/xml/shortcuts.xml`
         * (`pull_backup_now`), used as a floor when querying manifest shortcuts.
         */
        internal const val MANIFEST_SHORTCUT_COUNT = 1

        /**
         * Computes the safe dynamic-shortcut count given the platform's
         * per-activity cap ([maxShortcutCountPerActivity]) and the number of
         * static manifest shortcuts ([manifestShortcutCount]) (#775).
         */
        internal fun maxDynamicShortcuts(
            maxShortcutCountPerActivity: Int,
            manifestShortcutCount: Int = MANIFEST_SHORTCUT_COUNT,
        ): Int = (maxShortcutCountPerActivity - manifestShortcutCount.coerceAtLeast(0))
            .coerceIn(0, MAX_SHORTCUTS)

        /**
         * The pure selection policy: dialable connections
         * (most-recently-connected first, never-connected keeping their
         * listing order), alternating with workspaces (most-recently-
         * [WorkspaceProfile.updatedAt] first), capped at [maxShortcuts].
         * Pure — unit-tested in LauncherShortcutManagerTest.
         */
        internal fun selectShortcuts(
            connections: List<ConnectionProfile>,
            workspaces: List<WorkspaceProfile>,
            maxShortcuts: Int = MAX_SHORTCUTS,
        ): List<LauncherShortcut> {
            val limit = maxShortcuts.coerceIn(0, MAX_SHORTCUTS)
            if (limit == 0) return emptyList()
            val recentConnections = connections.filter { it.isSupported && !it.isOpenai }
                .sortedByDescending { it.lastConnected ?: 0L }
            val recentWorkspaces = workspaces.sortedByDescending { it.updatedAt }
            val merged = interleave(
                recentConnections.map { ConnectionShortcut(it.id, it.label) },
                recentWorkspaces.map { WorkspaceShortcut(it.id, it.name) },
            )
            return merged.take(limit)
        }

        /**
         * Alternating merge [a0, b0, a1, b1, a2]: when one list runs out
         * the other continues into the freed slots, so a single busy
         * category can't empty the other but an unused category costs
         * nothing.
         */
        internal fun interleave(a: List<LauncherShortcut>, b: List<LauncherShortcut>): List<LauncherShortcut> =
            (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
    }
}
