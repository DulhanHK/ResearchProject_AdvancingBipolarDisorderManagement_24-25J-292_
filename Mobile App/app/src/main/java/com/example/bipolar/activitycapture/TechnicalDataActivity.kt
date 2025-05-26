package com.example.bipolar.activitycapture

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.bipolar.R
import com.example.bipolar.activitycapture.audio.AudioCaptureService
import com.example.bipolar.activitycapture.browser.BrowserMonitorService
import com.example.bipolar.activitycapture.isolate.StepCounterService
import com.example.bipolar.utils.NotificationUtils.initialize
import com.example.bipolar.utils.NotificationUtils.updateCombinedNotification

class TechnicalDataActivity : AppCompatActivity() {
    private var stepsText: TextView? = null
    private var statusText: TextView? = null
    private var timeMovingText: TextView? = null
    private var timeStationaryText: TextView? = null
    private var isolationStatusText: TextView? = null
    private var stepServiceStatusText: TextView? = null
    private var audioServiceStatusText: TextView? = null
    private var browserServiceStatusText: TextView? = null
    private var textEmotionText: TextView? = null
    private var audioEmotionText: TextView? = null
    private var isStepReceiverRegistered = false
    private var isAudioReceiverRegistered = false
    private var isBrowserReceiverRegistered = false
    private var mediaProjectionManager: MediaProjectionManager? = null

    private var lastMovementTime: Long = 0
    private val previousSteps = 0

    private val stepReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (StepCounterService.BROADCAST_ACTION == intent.action) {
                val currentSteps = intent.getIntExtra("steps", 0)
                val currentTimeMoving = intent.getIntExtra("timeMoving", 0)
                val currentTimeStationary = intent.getIntExtra("timeStationary", 0)

                val deltaSteps = currentSteps - previousSteps

                stepsText!!.text = "Steps: $currentSteps"
                statusText!!.text = if (deltaSteps > 0) "Status: Moving" else "Status: Stationary"
                timeMovingText!!.text = "Moving Time: " + currentTimeMoving + "s"
                timeStationaryText!!.text = "Stationary Time: " + currentTimeStationary + "s"

                if (deltaSteps > 0) {
                    lastMovementTime = System.currentTimeMillis()
                }
                updateIsolationStatus()
                // Update notification with the latest data
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val textEmotion = prefs.getString(KEY_TEXT_EMOTION, "Unknown")
                val audioEmotion = prefs.getString(KEY_AUDIO_EMOTION, "Unknown")
                updateCombinedNotification(context, currentSteps, textEmotion, audioEmotion)
            }
        }
    }
    private val audioReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioCaptureService.BROADCAST_ACTION == intent.action) {
                val emotion = intent.getStringExtra("emotion")
                audioEmotionText!!.text = "Audio Emotion: $emotion"
                audioServiceStatusText!!.text = "Audio Service: Running"

                //                saveEmotionToPrefs(KEY_AUDIO_EMOTION, emotion);

                // Save to SharedPreferences
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_AUDIO_EMOTION, emotion).apply()

                // Update notification
                val steps = prefs.getInt("steps", -1)
                val textEmotion = prefs.getString(KEY_TEXT_EMOTION, "Unknown")
                updateCombinedNotification(context, steps, textEmotion, emotion)
            }
        }
    }

    private val browserReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BrowserMonitorService.BROADCAST_ACTION == intent.action) {
                val emotion = intent.getStringExtra("emotion")
                textEmotionText!!.text = "Text Emotion: $emotion"
                browserServiceStatusText!!.text = "Browser Service: Running"

                //                saveEmotionToPrefs(KEY_TEXT_EMOTION, emotion);

                // Save to SharedPreferences
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_TEXT_EMOTION, emotion).apply()

                // Update notification
                val steps = prefs.getInt("steps", -1)
                val audioEmotion = prefs.getString(KEY_AUDIO_EMOTION, "Unknown")
                updateCombinedNotification(context, steps, emotion, audioEmotion)
            }
        }
    }

    //    private void saveEmotionToPrefs(String key, String emotion) {
    //        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    //        SharedPreferences.Editor editor = prefs.edit();
    //        editor.putString(key, emotion);
    //        editor.apply();
    //        Log.d(TAG, "Saved " + key + ": " + emotion);
    //    }
    private fun loadEmotionFromPrefs(key: String, defaultValue: String): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val emotion = prefs.getString(key, defaultValue)
        Log.d(TAG, "Loaded $key: $emotion")
        return emotion
    }

    private fun updateIsolationStatus() {
        val timeSinceLastMovement = System.currentTimeMillis() - lastMovementTime
        val isIsolated = timeSinceLastMovement > ISOLATION_THRESHOLD
        isolationStatusText!!.text =
            if (isIsolated) "Isolated Status: Isolated" else "Isolated Status: Not Isolated"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_technical_data)

        stepsText = findViewById(R.id.steps_text)
        statusText = findViewById(R.id.status_text)
        timeMovingText = findViewById(R.id.time_moving_text)
        timeStationaryText = findViewById(R.id.time_stationary_text)
        isolationStatusText = findViewById(R.id.isolation_status_text)
        stepServiceStatusText = findViewById(R.id.step_service_status_text)
        audioServiceStatusText = findViewById(R.id.audio_service_status_text)
        browserServiceStatusText = findViewById(R.id.browser_service_status_text)
        textEmotionText = findViewById(R.id.text_emotion_text)
        audioEmotionText = findViewById(R.id.audio_emotion_text)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val lastTextEmotion = loadEmotionFromPrefs(KEY_TEXT_EMOTION, "Unknown")
        val lastAudioEmotion = loadEmotionFromPrefs(KEY_AUDIO_EMOTION, "Unknown")
        textEmotionText?.setText("Text Emotion: $lastTextEmotion")
        audioEmotionText?.setText("Audio Emotion: $lastAudioEmotion")

        // Initialize NotificationUtils
        initialize(this)

        // Start with notification permission
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Requesting notification permission")
                ActivityCompat.requestPermissions(
                    this,
                    NOTIFICATION_PERMISSIONS,
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(
                    TAG,
                    "Notification permission already granted, proceeding to step permissions"
                )
                requestStepPermissions()
            }
        } else {
            // For pre-Android 13, no runtime permission needed
            requestStepPermissions()
        }
    }

    private fun updateServiceStatuses() {
        stepServiceStatusText!!.text = "Step Service: Not Running"
        audioServiceStatusText!!.text =
            "Audio Service: " + (if (AudioCaptureService.isRunning) "Running" else "Not Running")
        browserServiceStatusText!!.text =
            "Browser Service: " + (if (isAccessibilityServiceEnabled) "Running" else "Not Running")
    }

    private fun requestStepPermissions() {
        val missingPermissions = ArrayList<String>()
        for (permission in STEP_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missingPermissions.add(permission)
                Log.d(
                    TAG,
                    "Missing step permission: $permission"
                )
            }
        }

        if (!missingPermissions.isEmpty()) {
            Log.d(
                TAG,
                "Requesting step permissions: $missingPermissions"
            )
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray<String>(),
                STEP_PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d(TAG, "All step permissions granted, starting step counter service")
            startStepCounterService()
            requestAudioPermissions()
        }
    }

    private fun requestAudioPermissions() {
        val missingPermissions = ArrayList<String>()
        for (permission in AUDIO_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missingPermissions.add(permission)
                Log.d(
                    TAG,
                    "Missing audio permission: $permission"
                )
            }
        }

        if (!missingPermissions.isEmpty()) {
            Log.d(
                TAG,
                "Requesting audio permissions: $missingPermissions"
            )
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray<String>(),
                AUDIO_PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d(TAG, "All audio permissions granted, checking overlay")
            checkOverlayAndStartAudioCapture()
        }
    }

    private fun checkOverlayAndStartAudioCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(
                this
            )
        ) {
            Log.d(TAG, "Overlay permission required")
            Toast.makeText(
                this,
                "Overlay permission required for audio capture",
                Toast.LENGTH_SHORT
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "Overlay permission granted, starting audio capture")
            startAudioCapture()
        }
    }

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!AudioCaptureService.isRunning) {
                Log.d(TAG, "Requesting media projection for audio capture")
                Toast.makeText(
                    this,
                    "Requesting media projection for audio capture",
                    Toast.LENGTH_SHORT
                ).show()
                val captureIntent = mediaProjectionManager!!.createScreenCaptureIntent()
                startActivityForResult(captureIntent, MEDIA_PROJECTION_REQUEST_CODE)
            } else {
                Log.d(TAG, "Audio capture service already running")
                Toast.makeText(this, "Audio capture service already running", Toast.LENGTH_SHORT)
                    .show()
                checkAccessibilityAndStartBrowserMonitor()
            }
        } else {
            Log.e(TAG, "Audio capture requires Android 10 or higher")
            Toast.makeText(this, "Audio capture requires Android 10 or higher", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun checkAccessibilityAndStartBrowserMonitor() {
        if (!isAccessibilityServiceEnabled) {
            Log.d(TAG, "BrowserMonitorService not enabled, requesting accessibility permission")
            Toast.makeText(
                this,
                "Please enable Browser Monitor Service in Accessibility settings",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "BrowserMonitorService enabled")
            Toast.makeText(this, "Browser monitor service enabled", Toast.LENGTH_SHORT).show()
        }
    }


    private val isAccessibilityServiceEnabled: Boolean
        get() {
            val accessibilityManager =
                getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val expectedComponentName = ComponentName(
                this,
                BrowserMonitorService::class.java
            )
            Log.d("accessibility", enabledServices.toString())
            for (service in enabledServices) {
                Log.d("accessibility service id", service.id.toString())
                Log.d("accessibility service class id", BrowserMonitorService::class.java.name)
                val expectedId = expectedComponentName.flattenToShortString()

                if (service.id == expectedId) {
                    return true
                }
            }
            return false
        }

    private fun startStepCounterService() {
        val serviceIntent = Intent(
            this,
            StepCounterService::class.java
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d(TAG, "Step counter service started")
        Toast.makeText(this, "Step counter service started", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Media projection granted, starting audio service")
                val serviceIntent = Intent(
                    this,
                    AudioCaptureService::class.java
                )
                serviceIntent.putExtra("resultCode", resultCode)
                serviceIntent.putExtra("data", data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "Audio capture service started", Toast.LENGTH_SHORT).show()
                checkAccessibilityAndStartBrowserMonitor()
            } else {
                Log.e(TAG, "Media projection permission denied")
                Toast.makeText(this, "Media projection permission denied", Toast.LENGTH_SHORT)
                    .show()
            }
        } else if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Overlay permission granted, proceeding to audio capture")
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                    startAudioCapture()
                } else {
                    Log.e(TAG, "Overlay permission denied")
                    Toast.makeText(
                        this,
                        "Overlay permission denied, audio capture unavailable",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else if (requestCode == ACCESSIBILITY_PERMISSION_REQUEST_CODE) {
            if (isAccessibilityServiceEnabled) {
                Log.d(TAG, "BrowserMonitorService enabled")
                Toast.makeText(this, "Browser monitor service enabled", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "BrowserMonitorService not enabled")
                Toast.makeText(
                    this,
                    "Please enable Browser Monitor Service in Accessibility settings",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            var allGranted = true
            var shouldShowRationale = false

            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            this,
                            permissions[i]
                        )
                    ) {
                        shouldShowRationale = true
                    }
                    Log.e(TAG, "Notification permission denied: " + permissions[i])
                }
            }

            if (allGranted) {
                Log.d(TAG, "Notification permission granted")
                requestStepPermissions()
            } else if (shouldShowRationale) {
                Log.d(TAG, "Showing rationale for notification permission")
                showPermissionRationale("Notification permission is required to show service status.")
            } else {
                Log.e(TAG, "Notification permission permanently denied")
                Toast.makeText(
                    this,
                    "Notification permission denied. Enable in settings.",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            }
        } else if (requestCode == STEP_PERMISSION_REQUEST_CODE) {
            var allGranted = true
            var shouldShowRationale = false

            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            this,
                            permissions[i]
                        )
                    ) {
                        shouldShowRationale = true
                    }
                    Log.e(TAG, "Step permission denied: " + permissions[i])
                }
            }

            if (allGranted) {
                Log.d(TAG, "All step permissions granted")
                startStepCounterService()
                requestAudioPermissions()
            } else if (shouldShowRationale) {
                Log.d(TAG, "Showing rationale for step permissions")
                showPermissionRationale("Step counter permissions are required for isolation detection.")
            } else {
                Log.e(TAG, "Step permissions permanently denied")
                Toast.makeText(
                    this,
                    "Step counter permissions denied. Enable in settings.",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            }
        } else if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            var allGranted = true
            var shouldShowRationale = false

            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            this,
                            permissions[i]
                        )
                    ) {
                        shouldShowRationale = true
                    }
                    Log.e(TAG, "Audio permission denied: " + permissions[i])
                }
            }

            if (allGranted) {
                Log.d(TAG, "All audio permissions granted")
                checkOverlayAndStartAudioCapture()
            } else if (shouldShowRationale) {
                Log.d(TAG, "Showing rationale for audio permissions")
                showPermissionRationale("Audio permissions are required for emotion detection.")
            } else {
                Log.e(TAG, "Audio permissions permanently denied")
                Toast.makeText(
                    this,
                    "Audio permissions denied. Enable in settings.",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            }
        }
    }

    private fun showPermissionRationale(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton(
                "Grant"
            ) { dialog: DialogInterface?, which: Int ->
                if (message.contains("Step")) {
                    requestStepPermissions()
                } else {
                    requestAudioPermissions()
                }
            }
            .setNegativeButton(
                "Exit"
            ) { dialog: DialogInterface?, which: Int -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()

        val lastTextEmotion = loadEmotionFromPrefs(KEY_TEXT_EMOTION, "Unknown")
        val lastAudioEmotion = loadEmotionFromPrefs(KEY_AUDIO_EMOTION, "Unknown")
        textEmotionText!!.text = "Text Emotion: $lastTextEmotion"
        audioEmotionText!!.text = "Audio Emotion: $lastAudioEmotion"

        if (!isStepReceiverRegistered) {
            val filter = IntentFilter(StepCounterService.BROADCAST_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stepReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(stepReceiver, filter)
            }
            isStepReceiverRegistered = true
        }
        if (!isAudioReceiverRegistered) {
            val filter = IntentFilter(AudioCaptureService.BROADCAST_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(audioReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(audioReceiver, filter)
            }
            isAudioReceiverRegistered = true
        }
        if (!isBrowserReceiverRegistered) {
            val filter = IntentFilter(BrowserMonitorService.BROADCAST_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(browserReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(browserReceiver, filter)
            }
            isBrowserReceiverRegistered = true
        }
        updateServiceStatuses()
    }

    override fun onPause() {
        super.onPause()
        if (isStepReceiverRegistered) {
            unregisterReceiver(stepReceiver)
            isStepReceiverRegistered = false
        }
        if (isAudioReceiverRegistered) {
            unregisterReceiver(audioReceiver)
            isAudioReceiverRegistered = false
        }
        if (isBrowserReceiverRegistered) {
            unregisterReceiver(browserReceiver)
            isBrowserReceiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "TechnicalDataActivity"
        private const val STEP_PERMISSION_REQUEST_CODE = 100
        private const val AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val MEDIA_PROJECTION_REQUEST_CODE = 102
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 103
        private const val ACCESSIBILITY_PERMISSION_REQUEST_CODE = 104
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 105

        private const val PREFS_NAME = "BipolaDisorderPrefs"
        private const val KEY_TEXT_EMOTION = "text_emotion"
        private const val KEY_AUDIO_EMOTION = "audio_emotion"

        private val STEP_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) arrayOf(
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.FOREGROUND_SERVICE_HEALTH,
                Manifest.permission.BODY_SENSORS
            ) else arrayOf(
                Manifest.permission.ACTIVITY_RECOGNITION
            )

        private val AUDIO_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,  //                    "android.permission.CAPTURE_VIDEO_OUTPUT"
            ) else arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )

        private const val ISOLATION_THRESHOLD = (30 * 60 * 1000 // 30 minutes
                ).toLong()
        private val NOTIFICATION_PERMISSIONS = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}
