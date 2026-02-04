package com.callrecorder.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.callrecorder.app.ui.navigation.AppNavigation
import com.callrecorder.app.ui.theme.CallRecorderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private var permissionsGranted by mutableStateOf(false)
    private var showPermissionRationale by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        permissionsGranted = allGranted
        if (!allGranted) {
            showPermissionRationale = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkPermissions()

        setContent {
            CallRecorderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsGranted) {
                        AppNavigation()
                    } else {
                        PermissionRequestScreen(
                            showRationale = showPermissionRationale,
                            onRequestPermissions = { requestPermissions() }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        permissionsGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }
}

@Composable
private fun PermissionRequestScreen(
    showRationale: Boolean,
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (showRationale) {
                "This app needs the following permissions to function:\n\n" +
                        "• Phone: To detect incoming and outgoing calls\n" +
                        "• Microphone: To record call audio\n" +
                        "• Contacts: To display caller names\n" +
                        "• Storage: To save recordings\n" +
                        "• Notifications: To show recording status\n\n" +
                        "Please grant these permissions in Settings."
            } else {
                "To record calls, the app needs access to:\n\n" +
                        "• Phone state (detect calls)\n" +
                        "• Microphone (record audio)\n" +
                        "• Contacts (show caller names)\n" +
                        "• Storage (save recordings)\n" +
                        "• Notifications (show status)"
            },
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showRationale) "Open Settings" else "Grant Permissions")
        }
    }
}
