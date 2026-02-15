package com.callrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.callrecorder.app.MainActivity
import com.callrecorder.app.R
import com.callrecorder.app.data.local.SettingsDataStore
import com.callrecorder.app.data.model.CallType
import com.callrecorder.app.data.model.Recording
import com.callrecorder.app.data.model.RecordingQuality
import com.callrecorder.app.data.repository.ContactsRepository
import com.callrecorder.app.data.repository.RecordingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@HiltWorker
class CallRecordingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val contactsRepository: ContactsRepository,
    private val settingsDataStore: SettingsDataStore
) : CoroutineWorker(context, params) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private var recordingStartTime: Long = 0
    private var currentPhoneNumber: String? = null
    private var currentCallType: CallType = CallType.INCOMING
    private var currentQuality: RecordingQuality = RecordingQuality.MEDIUM

    companion object {
        private const val TAG = "CallRecordingWorker"
        private const val CHANNEL_ID = "call_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val KEY_PHONE_NUMBER = "phone_number"
        const val KEY_CALL_TYPE = "call_type"
        const val KEY_ACTION = "action"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
    }

    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: ACTION_START

        return try {
            when (action) {
                ACTION_START -> {
                    val phoneNumber = inputData.getString(KEY_PHONE_NUMBER) ?: ""
                    val callTypeStr = inputData.getString(KEY_CALL_TYPE) ?: "INCOMING"
                    val callType = if (callTypeStr == "OUTGOING") CallType.OUTGOING else CallType.INCOMING

                    Log.d(TAG, "Worker started for recording: $phoneNumber")

                    // Set foreground info for long-running task
                    setForeground(createForegroundInfo(phoneNumber))

                    startRecording(phoneNumber, callType)

                    // Keep worker alive - it will be stopped by stop action
                    // WorkManager allows up to 10 minutes for expedited work
                    delay(Long.MAX_VALUE)
                    Result.success()
                }
                ACTION_STOP -> {
                    Log.d(TAG, "Worker stopping recording")
                    stopRecording()
                    Result.success()
                }
                else -> Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            stopRecording()
            Result.failure()
        }
    }

    private suspend fun startRecording(phoneNumber: String, callType: CallType) {
        try {
            val settings = settingsDataStore.settingsFlow.first()
            if (!settings.autoRecord) {
                Log.d(TAG, "Auto-record is disabled")
                return
            }

            currentQuality = settings.recordingQuality
            currentPhoneNumber = phoneNumber
            currentCallType = callType
            currentFilePath = createOutputFile()

            withContext(Dispatchers.Main) {
                initializeMediaRecorder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            throw e
        }
    }

    private fun initializeMediaRecorder() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                Log.d(TAG, "Initializing MediaRecorder with VOICE_COMMUNICATION source")
                Log.d(TAG, "Quality: ${currentQuality.sampleRate}Hz, ${currentQuality.channels}ch, ${currentQuality.bitRate}bps")
                Log.d(TAG, "Output file: $currentFilePath")

                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    Log.d(TAG, "Audio source set: VOICE_COMMUNICATION")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set VOICE_COMMUNICATION source", e)
                    throw e
                }

                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(currentQuality.sampleRate)
                setAudioChannels(currentQuality.channels)
                setAudioEncodingBitRate(currentQuality.bitRate)
                setOutputFile(currentFilePath)

                try {
                    Log.d(TAG, "Calling prepare()...")
                    prepare()
                    Log.d(TAG, "Prepare successful, calling start()...")
                    start()
                    recordingStartTime = System.currentTimeMillis()
                    Log.d(TAG, "✓ Recording started successfully: $currentFilePath")
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRecorder prepare/start failed", e)
                    Log.e(TAG, "Error details: ${e.javaClass.simpleName}: ${e.message}")
                    tryFallbackRecording()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaRecorder", e)
            tryFallbackRecording()
        }
    }

    private fun tryFallbackRecording() {
        Log.w(TAG, "Attempting fallback recording with MIC source")
        try {
            mediaRecorder?.release()
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                Log.d(TAG, "Setting audio source to MIC")
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(currentQuality.sampleRate)
                setAudioChannels(currentQuality.channels)
                setAudioEncodingBitRate(currentQuality.bitRate)
                setOutputFile(currentFilePath)

                Log.d(TAG, "Fallback: calling prepare()...")
                prepare()
                Log.d(TAG, "Fallback: calling start()...")
                start()
                recordingStartTime = System.currentTimeMillis()
                Log.d(TAG, "✓ Fallback recording started successfully with MIC source")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback recording also failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private suspend fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            val duration = System.currentTimeMillis() - recordingStartTime

            // Save recording to database
            try {
                val file = File(currentFilePath ?: "")
                val contactName = currentPhoneNumber?.let {
                    contactsRepository.getContactName(it)
                }

                val recording = Recording(
                    phoneNumber = currentPhoneNumber ?: "Unknown",
                    contactName = contactName,
                    callType = currentCallType,
                    startTime = recordingStartTime,
                    duration = duration,
                    filePath = currentFilePath ?: "",
                    fileSize = if (file.exists()) file.length() else 0,
                    quality = currentQuality
                )

                recordingRepository.insertRecording(recording)
                Log.d(TAG, "Recording saved to database: ${recording.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving recording to database", e)
            }

            Log.d(TAG, "Recording stopped, duration: ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    private fun createOutputFile(): String {
        val recordingsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "CallRecordings"
        )
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val phoneFormatted = currentPhoneNumber?.replace(Regex("[^+\\d]"), "") ?: "unknown"
        val fileName = "call_${phoneFormatted}_$timestamp.m4a"

        return File(recordingsDir, fileName).absolutePath
    }

    private fun createForegroundInfo(phoneNumber: String): ForegroundInfo {
        createNotificationChannel()

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contactName = contactsRepository.getContactName(phoneNumber)
        val displayName = contactName ?: phoneNumber.ifEmpty { context.getString(R.string.unknown_number) }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recording_notification_title))
            .setContentText("$displayName - ${context.getString(R.string.recording_notification_text)}")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
