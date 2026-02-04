package com.callrecorder.app.data.local

import androidx.room.*
import com.callrecorder.app.data.model.Recording
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startTime DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): Recording?

    @Query("SELECT * FROM recordings WHERE phoneNumber = :phoneNumber ORDER BY startTime DESC")
    fun getRecordingsByPhoneNumber(phoneNumber: String): Flow<List<Recording>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun getRecordingsCount(): Int

    @Query("SELECT SUM(fileSize) FROM recordings")
    suspend fun getTotalFileSize(): Long?
}
