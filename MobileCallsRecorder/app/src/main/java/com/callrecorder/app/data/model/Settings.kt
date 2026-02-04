package com.callrecorder.app.data.model

data class AppSettings(
    val autoRecord: Boolean = true,
    val recordingQuality: RecordingQuality = RecordingQuality.MEDIUM,
    val showNotification: Boolean = true
)
