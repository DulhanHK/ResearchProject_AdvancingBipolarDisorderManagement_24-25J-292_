package com.example.bipolar.activitycapture.isolate

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.bipolar.utils.NotificationUtils
import com.example.bipolar.utils.NotificationUtils.getNotification
import com.example.bipolar.utils.NotificationUtils.initialize
import com.example.bipolar.utils.NotificationUtils.updateCombinedNotification
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class StepCounterService : Service(), SensorEventListener {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val FIRESTORE_UPDATE_INTERVAL = 30000
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var stepCount = 0
    private var initialStepCount = -1
    private var timeMoving = 0
    private var timeStationary = 0
    private var previousStepCountForTime = 0
    private var totalSteps = 0
    private var totalTimeMoving = 0
    private var totalTimeStationary = 0
    private var lastFirestoreUpdateTime: Long = 0
    private var previousSteps = 0
    private var previousTimeMoving = 0
    private var previousTimeStationary = 0

    private val lastMovementTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable: Runnable = object : Runnable {
        override fun run() {
            val currentStepCount = stepCount
            val stepsThisSecond = currentStepCount - previousStepCountForTime

            if (stepsThisSecond > 0) {
                timeMoving++
            } else {
                timeStationary++
            }
            previousStepCountForTime = currentStepCount

            //            updateNotification();
            sendUpdateBroadcast()
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            sensorManager!!.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        }

        initialize(this)
        val notification = getNotification(this, stepCount, "Unknown", "Unknown")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationUtils.NOTIFICATION_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
        handler.post(timerRunnable)
    }

    //    @SuppressLint("ForegroundServiceType")
    //    private void startForegroundWithProperType() {
    //        Notification notification = getNotification(); //        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    //            // Android 14+ requires explicit permissions
    //            startForeground(1, notification, FOREGROUND_SERVICE_TYPE_HEALTH);
    //        } else {
    //            // For Android 10-13
    //            startForeground(1, notification);
    //        }
    //    }
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager!!.unregisterListener(this)
        handler.removeCallbacks(timerRunnable)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            if (initialStepCount == -1) {
                initialStepCount = event.values[0].toInt()
            }
            stepCount = event.values[0].toInt() - initialStepCount
            updateNotification()
            // Save steps to SharedPreferences
            val prefs = getSharedPreferences("BipolaDisorderPrefs", MODE_PRIVATE)
            prefs.edit().putInt("steps", stepCount).apply()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}


    //    private void createNotificationChannel() {
    //        NotificationChannel channel = new NotificationChannel(
    //                CHANNEL_ID, "Step Counter Service", NotificationManager.IMPORTANCE_LOW);
    //        NotificationManager manager = getSystemService(NotificationManager.class);
    //        manager.createNotificationChannel(channel);
    //    }
    //
    //    private Notification getNotification() {
    //        Intent intent = new Intent(this, TechnicalDataActivity.class);
    //        PendingIntent pendingIntent = PendingIntent.getActivity(
    //                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    //
    //        return new NotificationCompat.Builder(this, CHANNEL_ID)
    //                .setContentTitle("Step Counter Running")
    //                .setContentText("Steps: " + stepCount + ", Moving: " + timeMoving + "s, Stationary: " + timeStationary + "s")
    //                .setSmallIcon(R.drawable.ic_notification)
    //                .setContentIntent(pendingIntent)
    //                .setOngoing(true)
    //                .build();
    //    }
    //
    //    private void updateNotification() {
    //        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    //        handler.post(() -> manager.notify(1, getNotification()));
    //    }
    private fun sendUpdateBroadcast() {
        val intent = Intent(BROADCAST_ACTION)
            .putExtra("steps", stepCount)
            .putExtra("timeMoving", timeMoving)
            .putExtra("timeStationary", timeStationary)
        sendBroadcast(intent)
        calTimes()
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences("BipolaDisorderPrefs", MODE_PRIVATE)
        val textEmotion = prefs.getString("text_emotion", "Unknown")!!
        val audioEmotion = prefs.getString("audio_emotion", "Unknown")!!
        updateCombinedNotification(this, stepCount, textEmotion, audioEmotion)
    }

    private fun calTimes() {
        val currentSteps = stepCount
        val currentTimeMoving = timeMoving
        val currentTimeStationary = timeStationary

        val deltaSteps = currentSteps - previousSteps
        val deltaTimeMoving = currentTimeMoving - previousTimeMoving
        val deltaTimeStationary = currentTimeStationary - previousTimeStationary

        totalSteps += deltaSteps
        totalTimeMoving += deltaTimeMoving
        totalTimeStationary += deltaTimeStationary

        previousSteps = currentSteps
        previousTimeMoving = currentTimeMoving
        previousTimeStationary = currentTimeStationary

        scheduleFirestoreUpdate()
    }

    private fun scheduleFirestoreUpdate() {
        val currentTime = System.currentTimeMillis()
        Log.d("FirestoreUpdate", "Scheduled Firestore update at $currentTime")
        if (currentTime - lastFirestoreUpdateTime >= FIRESTORE_UPDATE_INTERVAL) {
            lastFirestoreUpdateTime = currentTime
            saveDataToFirestore()
        }
    }

    private fun saveDataToFirestore() {
        Log.d("update calls", "updated calls")
        if (auth.currentUser == null) return

        Log.d("update running", "updated running")
        val data: MutableMap<String, Any> = HashMap()
        data["steps"] = totalSteps
        data["timeMoving"] = totalTimeMoving
        data["timeStationary"] = totalTimeStationary
        data["timestamp"] = Date()
        data["isolated"] =
            (System.currentTimeMillis() - lastMovementTime) > ISOLATION_THRESHOLD

        db.collection("users").document(auth.uid!!).collection("activityData").add(data)
            .addOnSuccessListener { documentReference: DocumentReference? ->
                totalSteps = 0
                totalTimeMoving = 0
                totalTimeStationary = 0
            }
    }

    companion object {
        const val BROADCAST_ACTION: String = "com.g292.bipoladisorder.STEP_UPDATE"
        private const val CHANNEL_ID = "StepCounterChannel"
        private const val FOREGROUND_SERVICE_TYPE_HEALTH =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH //


        private const val ISOLATION_THRESHOLD = (30 * 60 * 1000 // 30 minutes
                ).toLong()
    }
}