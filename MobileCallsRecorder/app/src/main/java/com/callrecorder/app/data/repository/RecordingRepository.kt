package com.callrecorder.app.data.repository

import com.callrecorder.app.data.local.RecordingDao
import com.callrecorder.app.data.model.Recording
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val recordingDao: RecordingDao
) {
    fun getAllRecordings(): Flow<List<Recording>> = recordingDao.getAllRecordings()

    fun getRecordingsByPhoneNumber(phoneNumber: String): Flow<List<Recording>> =
        recordingDao.getRecordingsByPhoneNumber(phoneNumber)

    suspend fun getRecordingById(id: Long): Recording? = recordingDao.getRecordingById(id)

    suspend fun insertRecording(recording: Recording): Long = recordingDao.insert(recording)

    suspend fun updateRecording(recording: Recording) = recordingDao.update(recording)

    suspend fun deleteRecording(recording: Recording) {
        // Delete the file first
        val file = File(recording.filePath)
        if (file.exists()) {
            file.delete()
        }
        // Then delete from database
        recordingDao.delete(recording)
    }

    suspend fun deleteRecordingById(id: Long) {
        val recording = recordingDao.getRecordingById(id)
        recording?.let { deleteRecording(it) }
    }

    suspend fun deleteAllRecordings() {
        recordingDao.deleteAll()
    }

    suspend fun getRecordingsCount(): Int = recordingDao.getRecordingsCount()

    suspend fun getTotalFileSize(): Long = recordingDao.getTotalFileSize() ?: 0L
}
