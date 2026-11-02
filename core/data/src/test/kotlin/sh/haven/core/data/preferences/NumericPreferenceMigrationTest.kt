package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NumericPreferenceMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `reopening an already broken store repairs types before publishing preferences`() = runTest {
        val file = folder.newFile("legacy.preferences_pb")
        val writerJob = SupervisorJob()
        val writer = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + writerJob)) { file }
        writer.edit {
            it[intPreferencesKey("desktop_touchpad_sensitivity")] = 2
            it[intPreferencesKey("toolbar_key_scale")] = 1
            it[intPreferencesKey("update_check_last_run_ms")] = 0
            it[longPreferencesKey("terminal_font_size")] = 18L
            it[stringPreferencesKey("theme")] = "dark"
        }
        writerJob.cancelAndJoin()

        val readerJob = SupervisorJob()
        val reader = PreferenceDataStoreFactory.create(
            migrations = listOf(NumericPreferenceMigration),
            scope = CoroutineScope(Dispatchers.IO + readerJob),
        ) { file }
        try {
            val preferences = reader.data.first()
            assertEquals(2f, preferences[floatPreferencesKey("desktop_touchpad_sensitivity")])
            assertEquals(1f, preferences[floatPreferencesKey("toolbar_key_scale")])
            assertEquals(0L, preferences[longPreferencesKey("update_check_last_run_ms")])
            assertEquals(18, preferences[intPreferencesKey("terminal_font_size")])
            assertEquals("dark", preferences[stringPreferencesKey("theme")])
            assertFalse(NumericPreferenceMigration.shouldMigrate(preferences))
        } finally {
            readerJob.cancelAndJoin()
        }

        // Verify the repaired types were persisted, rather than fixed only in memory.
        val reopenJob = SupervisorJob()
        val reopened = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + reopenJob)) { file }
        try {
            assertFalse(NumericPreferenceMigration.shouldMigrate(reopened.data.first()))
        } finally {
            reopenJob.cancelAndJoin()
        }
    }

    @Test
    fun `all float getters accept legacy integer values without relying on startup migration`() = runTest {
        val preferences = preferencesOf(
            intPreferencesKey("desktop_touchpad_sensitivity") to 2,
            intPreferencesKey("toolbar_key_scale") to 1,
            intPreferencesKey("terminal_background_opacity") to 1,
            intPreferencesKey("mail_font_scale") to 1,
            intPreferencesKey("app_window_default_scale") to 2,
        )
        val store = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(preferences)
        }
        val repository = UserPreferencesRepository(mockk(relaxed = true), store)

        assertEquals(2f, repository.desktopTouchpadSensitivity.first())
        assertEquals(1f, repository.toolbarKeyScale.first())
        assertEquals(1f, repository.terminalBackgroundOpacity.first())
        assertEquals(1f, repository.mailFontScale.first())
        assertEquals(2f, repository.appWindowDefaultScale.first())
    }
}
