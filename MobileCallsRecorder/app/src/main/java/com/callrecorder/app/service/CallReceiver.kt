package com.callrecorder.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
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
        val intent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START_RECORDING
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, phoneNumber ?: "")
            putExtra(
                CallRecordingService.EXTRA_CALL_TYPE,
                if (isIncoming) "INCOMING" else "OUTGOING"
            )
        }

        context.startForegroundService(intent)
    }

    private fun stopRecording(context: Context) {
        val intent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP_RECORDING
        }

        context.startService(intent)
    }
}
