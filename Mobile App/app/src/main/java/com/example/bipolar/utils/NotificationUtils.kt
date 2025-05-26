package com.example.bipolar.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.bipolar.HomeActivity
import com.example.bipolar.R

object NotificationUtils {
    private const val CHANNEL_ID = "CombinedServiceChannel"
    const val NOTIFICATION_ID: Int = 100
    private var notificationManager: NotificationManager? = null
    private var notificationBuilder: NotificationCompat.Builder? = null

    @JvmStatic
    fun initialize(context: Context) {
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(context)
        val intent = Intent(context, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Bipolar Disorder Monitor")
            .setContentText("Services are running...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Combined Service Channel", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager!!.createNotificationChannel(channel)
        }
    }

    @JvmStatic
    fun updateCombinedNotification(
        context: Context?,
        steps: Int,
        textEmotion: String?,
        audioEmotion: String?
    ) {
        val contentText = "Steps: " + (if (steps >= 0) steps else "N/A") +
                " | Text Emotion: " + (textEmotion ?: "N/A") +
                " | Audio Emotion: " + (audioEmotion ?: "N/A")
        notificationBuilder!!.setContentText(contentText)
        val notification = notificationBuilder!!.build()
        notificationManager!!.notify(NOTIFICATION_ID, notification)
    }

    @JvmStatic
    fun getNotification(
        context: Context,
        steps: Int,
        textEmotion: String?,
        audioEmotion: String?
    ): Notification {
        if (notificationBuilder == null) {
            initialize(context)
        }
        updateCombinedNotification(context, steps, textEmotion, audioEmotion)
        return notificationBuilder!!.build()
    }
}