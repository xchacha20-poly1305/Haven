package sh.haven.core.data.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey

/** Numeric settings have declared types; JSON numbers do not preserve those types. */
internal object NumericPreferenceKeys {
    val terminalFontSizeKey = intPreferencesKey("terminal_font_size")
    // Horizontal margin around the terminal grid in dp (#745). Shrinking the
    // measured width makes termlib recompute columns and resize the PTY.
    val terminalHorizontalPaddingKey = intPreferencesKey("terminal_horizontal_padding")
    // Mail message-list pinch-zoom factor, multiplied onto the terminal font size.
    val mailFontScaleKey = floatPreferencesKey("mail_font_scale")
    val terminalScrollbackRowsKey = intPreferencesKey("terminal_scrollback_rows")
    val prootIdleTimeoutMinutesKey = intPreferencesKey("proot_idle_timeout_minutes")
    val rdpDesktopWidthKey = intPreferencesKey("rdp_desktop_width")
    val rdpDesktopHeightKey = intPreferencesKey("rdp_desktop_height")
    val reticulumPortKey = intPreferencesKey("reticulum_port")
    val terminalBackgroundOpacityKey = floatPreferencesKey("terminal_background_opacity")
    val toolbarRowsKey = intPreferencesKey("toolbar_rows")
    val toolbarMinButtonWidthKey = intPreferencesKey("toolbar_min_button_width")
    val toolbarKeyScaleKey = floatPreferencesKey("toolbar_key_scale")
    val appWindowDefaultScaleKey = floatPreferencesKey("app_window_default_scale")
    val backupAutoPullIntervalMinutesKey = intPreferencesKey("backup_auto_pull_interval_minutes")
    // Trackpad-mode speed multiplier (#696). 1f = the 1:1 finger→cursor ratio.
    val desktopTouchpadSensitivityKey = floatPreferencesKey("desktop_touchpad_sensitivity")
    val updateCheckLastRunKey = longPreferencesKey("update_check_last_run_ms")
    val lastMediaServerPortKey = intPreferencesKey("last_media_server_port")

    private val floatKeys = listOf(
        mailFontScaleKey,
        terminalBackgroundOpacityKey,
        toolbarKeyScaleKey,
        appWindowDefaultScaleKey,
        desktopTouchpadSensitivityKey,
    ).associateBy { it.name }

    private val intKeys = listOf(
        terminalFontSizeKey,
        terminalHorizontalPaddingKey,
        terminalScrollbackRowsKey,
        prootIdleTimeoutMinutesKey,
        rdpDesktopWidthKey,
        rdpDesktopHeightKey,
        reticulumPortKey,
        toolbarRowsKey,
        toolbarMinButtonWidthKey,
        backupAutoPullIntervalMinutesKey,
        lastMediaServerPortKey,
    ).associateBy { it.name }

    private val longKeys = listOf(
        updateCheckLastRunKey,
    ).associateBy { it.name }

    fun restore(preferences: MutablePreferences, name: String, value: Number): Boolean {
        floatKeys[name]?.let { preferences[it] = value.toFloat(); return true }
        intKeys[name]?.let { preferences[it] = value.toInt(); return true }
        longKeys[name]?.let { preferences[it] = value.toLong(); return true }
        return false
    }

    fun needsMigration(preferences: Preferences): Boolean = preferences.asMap().any { (key, value) ->
        value is Number && when (key.name) {
            in floatKeys -> value !is Float
            in intKeys -> value !is Int
            in longKeys -> value !is Long
            else -> false
        }
    }

    fun normalize(preferences: Preferences): Preferences = preferences.toMutablePreferences().apply {
        preferences.asMap().forEach { (key, value) ->
            if (value is Number) restore(this, key.name, value)
        }
    }
}

/** Repairs settings written with JSON-inferred types by older backup importers. */
internal object NumericPreferenceMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        NumericPreferenceKeys.needsMigration(currentData)

    override suspend fun migrate(currentData: Preferences): Preferences =
        NumericPreferenceKeys.normalize(currentData)

    override suspend fun cleanUp() = Unit
}

/** Also tolerates a mismatched value written while this DataStore is already open. */
internal fun Preferences.floatOrNull(key: Preferences.Key<Float>): Float? =
    (asMap()[key] as? Number)?.toFloat()

internal fun Preferences.longOrNull(key: Preferences.Key<Long>): Long? =
    (asMap()[key] as? Number)?.toLong()
