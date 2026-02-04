package com.callrecorder.app.viewmodel

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.callrecorder.app.data.model.Recording
import com.callrecorder.app.data.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class RecordingsUiState(
    val recordings: List<Recording> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentlyPlayingId: Long? = null,
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
    val playbackDuration: Long = 0L,
    val playbackPosition: Long = 0L
)

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingsUiState())
    val uiState: StateFlow<RecordingsUiState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    init {
        loadRecordings()
    }

    private fun loadRecordings() {
        viewModelScope.launch {
            recordingRepository.getAllRecordings()
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect { recordings ->
                    _uiState.update {
                        it.copy(recordings = recordings, isLoading = false, error = null)
                    }
                }
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                if (_uiState.value.currentlyPlayingId == recording.id) {
                    stopPlayback()
                }
                recordingRepository.deleteRecording(recording)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete recording: ${e.message}") }
            }
        }
    }

    fun playRecording(recording: Recording) {
        // If already playing this recording, pause it
        if (_uiState.value.currentlyPlayingId == recording.id && _uiState.value.isPlaying) {
            pausePlayback()
            return
        }

        // If playing a different recording, stop it first
        if (_uiState.value.currentlyPlayingId != recording.id) {
            stopPlayback()
        }

        val file = File(recording.filePath)
        if (!file.exists()) {
            _uiState.update { it.copy(error = "Recording file not found") }
            return
        }

        try {
            if (mediaPlayer == null || _uiState.value.currentlyPlayingId != recording.id) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(recording.filePath)
                    prepare()
                    setOnCompletionListener {
                        stopPlayback()
                    }
                }
            }

            mediaPlayer?.start()
            _uiState.update {
                it.copy(
                    currentlyPlayingId = recording.id,
                    isPlaying = true,
                    playbackDuration = mediaPlayer?.duration?.toLong() ?: 0L
                )
            }

            // Start progress tracking
            trackProgress()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to play recording: ${e.message}") }
        }
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _uiState.update {
            it.copy(
                currentlyPlayingId = null,
                isPlaying = false,
                playbackProgress = 0f,
                playbackPosition = 0L
            )
        }
    }

    fun seekTo(position: Float) {
        val duration = mediaPlayer?.duration ?: return
        val seekPosition = (position * duration).toInt()
        mediaPlayer?.seekTo(seekPosition)
        _uiState.update {
            it.copy(
                playbackProgress = position,
                playbackPosition = seekPosition.toLong()
            )
        }
    }

    private fun trackProgress() {
        viewModelScope.launch {
            while (_uiState.value.isPlaying && mediaPlayer != null) {
                try {
                    val position = mediaPlayer?.currentPosition ?: 0
                    val duration = mediaPlayer?.duration ?: 1
                    _uiState.update {
                        it.copy(
                            playbackProgress = position.toFloat() / duration,
                            playbackPosition = position.toLong()
                        )
                    }
                    kotlinx.coroutines.delay(100)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
