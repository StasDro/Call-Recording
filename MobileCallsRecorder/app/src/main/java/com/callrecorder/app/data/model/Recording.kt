package com.callrecorder.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val contactName: String? = null,
    val callType: CallType,
    val startTime: Long,
    val duration: Long = 0,
    val filePath: String,
    val fileSize: Long = 0,
    val quality: RecordingQuality = RecordingQuality.MEDIUM
)

enum class CallType {
    INCOMING,
    OUTGOING
}

enum class RecordingQuality(
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Int,
    val displayName: String
) {
    LOW(8000, 1, 16000, "Low (8 kHz)"),
    MEDIUM(16000, 1, 32000, "Medium (16 kHz)"),
    HIGH(44100, 2, 128000, "High (44.1 kHz)")
}
