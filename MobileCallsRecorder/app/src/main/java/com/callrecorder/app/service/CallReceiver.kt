package com.callrecorder.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.UUID

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private const val WORK_NAME = "call_recording_work"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var isIncoming = false
        private var savedNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                // Outgoing call
                savedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                isIncoming = false
                Log.d(TAG, "Outgoing call to: $savedNumber")
            }

            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                val state = when (stateStr) {
                    TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    else -> TelephonyManager.CALL_STATE_IDLE
                }

                onCallStateChanged(context, state, number)
            }
        }
    }

    private fun onCallStateChanged(context: Context, state: Int, number: String?) {
        if (lastState == state) {
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call started
                isIncoming = true
                savedNumber = number
                Log.d(TAG, "Incoming call from: $savedNumber")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call answered
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    // Incoming call was answered
                    Log.d(TAG, "Incoming call answered: $savedNumber")
                    startRecording(context, savedNumber, true)
                } else {
                    // Outgoing call started
                    Log.d(TAG, "Outgoing call started: $savedNumber")
                    startRecording(context, savedNumber, false)
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended
                when (lastState) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Missed incoming call
                        Log.d(TAG, "Missed call from: $savedNumber")
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call ended
                        Log.d(TAG, "Call ended: $savedNumber")
                        stopRecording(context)
                    }
                }
                savedNumber = null
                isIncoming = false
            }
        }

        lastState = state
    }

    private fun startRecording(context: Context, phoneNumber: String?, isIncoming: Boolean) {
        try {
            // Use WorkManager to bypass Android 12+ background service restrictions
            val inputData = Data.Builder()
                .putString(CallRecordingWorker.KEY_ACTION, CallRecordingWorker.ACTION_START)
                .putString(CallRecordingWorker.KEY_PHONE_NUMBER, phoneNumber ?: "")
                .putString(
                    CallRecordingWorker.KEY_CALL_TYPE,
                    if (isIncoming) "INCOMING" else "OUTGOING"
                )
                .build()

            val workRequest = OneTimeWorkRequestBuilder<CallRecordingWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            Log.d(TAG, "Recording work enqueued via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue recording work", e)
        }
    }

    private fun stopRecording(context: Context) {
        try {
            // First, enqueue a stop action to properly finalize the recording
            val inputData = Data.Builder()
                .putString(CallRecordingWorker.KEY_ACTION, CallRecordingWorker.ACTION_STOP)
                .build()

            val stopRequest = OneTimeWorkRequestBuilder<CallRecordingWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(stopRequest)

            // Then cancel the ongoing work
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

            Log.d(TAG, "Recording work cancelled via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording work", e)
        }
    }
}
