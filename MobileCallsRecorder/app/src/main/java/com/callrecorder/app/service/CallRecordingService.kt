package com.callrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
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

    // Use NonCancellable scope for saving - will not be cancelled on onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioManager: AudioManager? = null
    private var wasSpeakerOn = false

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                val callTypeStr = intent.getStringExtra(EXTRA_CALL_TYPE)
                val callType = if (callTypeStr == "OUTGOING") CallType.OUTGOING else CallType.INCOMING
                Log.d(TAG, "Service started in background mode (no foreground)")
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
                    stopSelf()
                    return@launch
                }

                currentQuality = settings.recordingQuality
                currentPhoneNumber = phoneNumber.ifEmpty { null }
                currentCallType = callType
                currentFilePath = createOutputFile()

                withContext(Dispatchers.Main) {
                    enableSpeakerForRecording()
                    initializeMediaRecorder()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                stopSelf()
            }
        }
    }

    // Enable speaker so MIC captures both sides of the call
    private fun enableSpeakerForRecording() {
        try {
            wasSpeakerOn = audioManager?.isSpeakerphoneOn ?: false
            if (!wasSpeakerOn) {
                audioManager?.isSpeakerphoneOn = true
                Log.d(TAG, "Speaker enabled for recording both sides")
            } else {
                Log.d(TAG, "Speaker was already on")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable speaker", e)
        }
    }

    private fun restoreSpeaker() {
        try {
            if (!wasSpeakerOn) {
                audioManager?.isSpeakerphoneOn = false
                Log.d(TAG, "Speaker restored to original state (off)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore speaker", e)
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
                Log.d(TAG, "Initializing MediaRecorder with VOICE_CALL source")
                Log.d(TAG, "Quality: ${currentQuality.sampleRate}Hz, ${currentQuality.channels}ch, ${currentQuality.bitRate}bps")
                Log.d(TAG, "Output file: $currentFilePath")

                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    Log.d(TAG, "Audio source set: VOICE_CALL")
                } catch (e: Exception) {
                    Log.e(TAG, "VOICE_CALL blocked, trying VOICE_COMMUNICATION", e)
                    try {
                        setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        Log.d(TAG, "Audio source set: VOICE_COMMUNICATION")
                    } catch (e2: Exception) {
                        Log.e(TAG, "VOICE_COMMUNICATION blocked, using MIC", e2)
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        Log.d(TAG, "Audio source set: MIC (speaker is on, will capture both sides)")
                    }
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
                    isRecording = true
                    recordingStartTime = System.currentTimeMillis()
                    Log.d(TAG, "✓ Recording started successfully: $currentFilePath")
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRecorder start failed: ${e.message}", e)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaRecorder", e)
            stopSelf()
        }
    }

    private fun stopRecording() {
        restoreSpeaker()

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
            val filePath = currentFilePath ?: ""
            val callType = currentCallType
            val startTime = recordingStartTime
            val quality = currentQuality
            val phoneNumber = currentPhoneNumber

            Log.d(TAG, "Recording stopped, duration: ${duration}ms, saving to DB...")

            // Use runBlocking to save BEFORE stopSelf() cancels the scope
            runBlocking {
                try {
                    // Try to get phone number from CallLog if not available
                    val resolvedNumber = if (phoneNumber.isNullOrEmpty()) {
                        getLastCallNumber()
                    } else {
                        phoneNumber
                    }

                    val contactName = resolvedNumber?.let {
                        contactsRepository.getContactName(it)
                    }

                    val file = File(filePath)
                    val recording = Recording(
                        phoneNumber = resolvedNumber ?: "Unknown",
                        contactName = contactName,
                        callType = callType,
                        startTime = startTime,
                        duration = duration,
                        filePath = filePath,
                        fileSize = if (file.exists()) file.length() else 0,
                        quality = quality
                    )

                    recordingRepository.insertRecording(recording)
                    Log.d(TAG, "✓ Recording saved to database. Number: $resolvedNumber, Contact: $contactName")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving recording to database", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // Read the last call from CallLog to get phone number
    private fun getLastCallNumber(): String? {
        return try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    Log.d(TAG, "Got number from CallLog: $number")
                    number
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read CallLog", e)
            null
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
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val displayName = phoneNumber.ifEmpty { getString(R.string.unknown_number) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText("$displayName - ${getString(R.string.recording_notification_text)}")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
