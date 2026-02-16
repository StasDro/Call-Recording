package com.callrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callrecorder.app.MainActivity
import com.callrecorder.app.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that keeps the app "visible" to Android system.
 * This allows CallReceiver to start CallRecordingService even when app is in background.
 *
 * The service runs continuously while auto-record is enabled and shows a persistent notification.
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "call_monitoring_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_MONITORING = "com.callrecorder.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.callrecorder.STOP_MONITORING"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "MonitoringService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                Log.d(TAG, "Starting monitoring service in foreground")
                startForeground(NOTIFICATION_ID, createNotification())
            }
            ACTION_STOP_MONITORING -> {
                Log.d(TAG, "Stopping monitoring service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        // START_STICKY ensures service restarts if killed by system
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitoring_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.monitoring_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MonitoringService destroyed")
    }
}
