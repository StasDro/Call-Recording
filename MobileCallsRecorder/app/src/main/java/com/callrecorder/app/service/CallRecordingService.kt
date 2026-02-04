package com.callrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callrecorder.app.MainActivity
import com.callrecorder.app.R
import com.callrecorder.app.data.local.SettingsDataStore
import com.callrecorder.app.data.model.CallType
import com.callrecorder.app.data.model.Recording
import com.callrecorder.app.data.model.RecordingQuality
import com.callrecorder.app.data.repository.ContactsRepository
import com.callrecorder.app.data.repository.RecordingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class CallRecordingService : Service() {

    @Inject
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var contactsRepository: ContactsRepository

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFilePath: String? = null
    private var currentPhoneNumber: String? = null
    private var currentCallType: CallType = CallType.INCOMING
    private var recordingStartTime: Long = 0
    private var currentQuality: RecordingQuality = RecordingQuality.MEDIUM

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "CallRecordingService"
        private const val CHANNEL_ID = "call_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "com.callrecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.callrecorder.STOP_RECORDING"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CALL_TYPE = "call_type"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                val callTypeStr = intent.getStringExtra(EXTRA_CALL_TYPE)
                val callType = if (callTypeStr == "OUTGOING") CallType.OUTGOING else CallType.INCOMING
                startRecording(phoneNumber, callType)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
        }
        return START_STICKY
    }

    private fun startRecording(phoneNumber: String, callType: CallType) {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring start request")
            return
        }

        serviceScope.launch {
            try {
                val settings = settingsDataStore.settingsFlow.first()
                if (!settings.autoRecord) {
                    Log.d(TAG, "Auto-record is disabled")
                    return@launch
                }

                currentQuality = settings.recordingQuality
                currentPhoneNumber = phoneNumber
                currentCallType = callType
                currentFilePath = createOutputFile()

                withContext(Dispatchers.Main) {
                    startForeground(NOTIFICATION_ID, createNotification(phoneNumber))
                    initializeMediaRecorder()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
            }
        }
    }

    private fun initializeMediaRecorder() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                // Use VOICE_COMMUNICATION for call recording
                // Note: On many devices, this may only record the user's voice
                // Full call recording requires system-level permissions
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(currentQuality.sampleRate)
                setAudioChannels(currentQuality.channels)
                setAudioEncodingBitRate(currentQuality.bitRate)
                setOutputFile(currentFilePath)

                try {
                    prepare()
                    start()
                    isRecording = true
                    recordingStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Recording started: $currentFilePath")
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRecorder prepare/start failed", e)
                    // Try fallback with MIC source
                    tryFallbackRecording()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaRecorder", e)
            tryFallbackRecording()
        }
    }

    private fun tryFallbackRecording() {
        try {
            mediaRecorder?.release()
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                // Fallback to MIC - will only record user's voice
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(currentQuality.sampleRate)
                setAudioChannels(currentQuality.channels)
                setAudioEncodingBitRate(currentQuality.bitRate)
                setOutputFile(currentFilePath)

                prepare()
                start()
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                Log.d(TAG, "Fallback recording started with MIC source")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback recording also failed", e)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopSelf()
            return
        }

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            val duration = System.currentTimeMillis() - recordingStartTime

            // Save recording to database
            serviceScope.launch {
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
            }

            Log.d(TAG, "Recording stopped, duration: ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createOutputFile(): String {
        val recordingsDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(phoneNumber: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contactName = contactsRepository.getContactName(phoneNumber)
        val displayName = contactName ?: phoneNumber.ifEmpty { getString(R.string.unknown_number) }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText("$displayName - ${getString(R.string.recording_notification_text)}")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (isRecording) {
            stopRecording()
        }
    }
}
