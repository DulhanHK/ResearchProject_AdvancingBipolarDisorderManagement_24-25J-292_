package com.example.bipolar.activitycapture

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.bipolar.activitycapture.TechnicalDataActivity
import com.example.bipolar.activitycapture.audio.AudioCaptureService
import com.example.bipolar.activitycapture.browser.BrowserMonitorService
import com.example.bipolar.activitycapture.isolate.StepCounterService

//import com.g292.bipoladisorder.utils.NotificationUtils;
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d(TAG, "Boot completed, checking permissions...")

            // Define all required permissions (excluding MediaProjection for now)
            val requiredPermissions = requiredPermissions

            // Check if all required permissions (except MediaProjection) are granted
            var allPermissionsGranted = true
            for (permission in requiredPermissions) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(
                        TAG,
                        "Permission not granted: $permission"
                    )
                    allPermissionsGranted = false
                    break
                }
            }

            // Check if accessibility service is enabled for BrowserMonitorService
            val isAccessibilityEnabled = isAccessibilityServiceEnabled(context)

            // Start services that don't require MediaProjection
            if (allPermissionsGranted && isAccessibilityEnabled) {
                Log.d(
                    TAG,
                    "All permissions granted and accessibility enabled, starting services..."
                )
                startServices(context)
            }

            // Always launch TechnicalDataActivity after boot to handle MediaProjection and other user interactions
            Log.d(
                TAG,
                "Launching TechnicalDataActivity to handle MediaProjection and confirm services..."
            )
            val activityIntent = Intent(
                context,
                TechnicalDataActivity::class.java
            )
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(activityIntent)
        }
    }

    private val requiredPermissions: Array<String>
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.FOREGROUND_SERVICE_HEALTH,
                    Manifest.permission.BODY_SENSORS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.RECORD_AUDIO
                )
            } else {
                arrayOf(
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
        val expectedComponentName = ComponentName(context, BrowserMonitorService::class.java)
        for (service in enabledServices) {
            if (service.id == expectedComponentName.flattenToShortString()) {
                Log.d(TAG, "Accessibility service is enabled")
                return true
            }
        }
        Log.w(TAG, "Accessibility service not enabled")
        return false
    }

    private fun startServices(context: Context) {
        // Initialize NotificationUtils
//        NotificationUtils.initialize(context);

        // Start StepCounterService

        val stepServiceIntent = Intent(
            context,
            StepCounterService::class.java
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(stepServiceIntent)
        } else {
            context.startService(stepServiceIntent)
        }
        Log.d(TAG, "StepCounterService started")

        // AudioCaptureService requires MediaProjection, which cannot be started without user interaction
        if (AudioCaptureService.isRunning) {
            Log.d(TAG, "AudioCaptureService already running")
        } else {
            Log.d(
                TAG,
                "AudioCaptureService requires user interaction for MediaProjection, skipping..."
            )
        }

        // BrowserMonitorService (Accessibility Service) should already be running if enabled
        if (isAccessibilityServiceEnabled(context)) {
            Log.d(TAG, "BrowserMonitorService is running")
        } else {
            Log.w(TAG, "BrowserMonitorService not enabled")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}