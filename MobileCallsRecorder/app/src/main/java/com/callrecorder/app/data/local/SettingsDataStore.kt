package com.callrecorder.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.callrecorder.app.data.model.AppSettings
import com.callrecorder.app.data.model.RecordingQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val AUTO_RECORD = booleanPreferencesKey("auto_record")
        val RECORDING_QUALITY = stringPreferencesKey("recording_quality")
        val SHOW_NOTIFICATION = booleanPreferencesKey("show_notification")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            autoRecord = preferences[PreferencesKeys.AUTO_RECORD] ?: true,
            recordingQuality = preferences[PreferencesKeys.RECORDING_QUALITY]?.let {
                try { RecordingQuality.valueOf(it) } catch (e: Exception) { RecordingQuality.MEDIUM }
            } ?: RecordingQuality.MEDIUM,
            showNotification = preferences[PreferencesKeys.SHOW_NOTIFICATION] ?: true
        )
    }

    suspend fun updateAutoRecord(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_RECORD] = enabled
        }
    }

    suspend fun updateRecordingQuality(quality: RecordingQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECORDING_QUALITY] = quality.name
        }
    }

    suspend fun updateShowNotification(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_NOTIFICATION] = enabled
        }
    }
}
