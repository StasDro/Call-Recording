package com.callrecorder.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.callrecorder.app.data.local.SettingsDataStore
import com.callrecorder.app.data.model.AppSettings
import com.callrecorder.app.data.model.RecordingQuality
import com.callrecorder.app.data.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val recordingsCount: Int = 0,
    val totalStorageSize: Long = 0L,
    val isLoading: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        loadStorageInfo()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
            }
        }
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            try {
                val count = recordingRepository.getRecordingsCount()
                val size = recordingRepository.getTotalFileSize()
                _uiState.update {
                    it.copy(recordingsCount = count, totalStorageSize = size)
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun updateAutoRecord(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.updateAutoRecord(enabled)
        }
    }

    fun updateRecordingQuality(quality: RecordingQuality) {
        viewModelScope.launch {
            settingsDataStore.updateRecordingQuality(quality)
        }
    }

    fun updateShowNotification(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.updateShowNotification(enabled)
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
