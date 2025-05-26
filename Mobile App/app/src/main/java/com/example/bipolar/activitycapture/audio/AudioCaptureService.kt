package com.example.bipolar.activitycapture.audio

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.bipolar.utils.NotificationUtils
import com.example.bipolar.utils.NotificationUtils.getNotification
import com.example.bipolar.utils.NotificationUtils.initialize
import com.example.bipolar.utils.NotificationUtils.updateCombinedNotification
import com.google.firebase.auth.FirebaseAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull // Or the specific parse method you need
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class AudioCaptureService : Service() {
    var firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var fullAudioOutputStream: FileOutputStream? = null
    private var fullAudioFile: File? = null
    private var fullAudioSamples: Long = 0
    private var chunkOutputStream: FileOutputStream? = null
    private var chunkFile: File? = null
    private var chunkSamples: Long = 0
    private var chunkStartTime: Long = 0
    private var isMusicPlaying = false
    private var lastSoundTime: Long = 0
    private val client = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun saveEmotionToPrefs(emotion: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_AUDIO_EMOTION, emotion)
        editor.apply()
        Log.d(TAG, "Saved audio_emotion: $emotion")
        updateNotification()
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences("BipolaDisorderPrefs", MODE_PRIVATE)
        val steps = prefs.getInt("steps", -1)
        val textEmotion = prefs.getString("text_emotion", "Unknown")!!
        val audioEmotion = prefs.getString("audio_emotion", "Unknown")!!
        updateCombinedNotification(this, steps, textEmotion, audioEmotion)
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        initialize(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra("resultCode") || !intent.hasExtra("data")) {
            Log.e(TAG, "Missing MediaProjection data")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = getNotification(this, 0, "Unknown", "Unknown")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationUtils.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setupAudioCapture(intent)
        } else {
            Log.e(TAG, "Requires Android 10+")
            stopSelf()
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioCapture(intent: Intent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            stopSelf()
            return
        }

        try {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = intent.getParcelableExtra<Intent>("data")
            mediaProjection = mediaProjectionManager!!.getMediaProjection(resultCode, data!!)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection")
                stopSelf()
                return
            }

            val config = AudioPlaybackCaptureConfiguration.Builder(
                mediaProjection!!
            )
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(BUFFER_SIZE)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing capture: " + e.message)
            stopSelf()
        }
    }

    private fun startRecording() {
        try {
            audioRecord!!.startRecording()
            isRunning = true
            recordingThread = Thread {
                val buffer =
                    ShortArray(BUFFER_SIZE)
                val byteBuffer =
                    ByteBuffer.allocateDirect(BUFFER_SIZE * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)

                while (!Thread.interrupted()) {
                    val bytesRead =
                        audioRecord!!.read(buffer, 0, BUFFER_SIZE)
                    if (bytesRead > 0) {
                        val amplitude = calculateAmplitude(buffer, bytesRead)
                        byteBuffer.clear()
                        for (i in 0 until bytesRead) {
                            byteBuffer.putShort(buffer[i])
                        }
                        val audioData = ByteArray(bytesRead * 2)
                        byteBuffer.rewind()
                        byteBuffer[audioData]

                        if (amplitude > SILENCE_THRESHOLD) {
                            lastSoundTime = System.currentTimeMillis()
                            if (!isMusicPlaying) {
                                isMusicPlaying = true
                                initFullAudioFile()
                                Log.d(
                                    TAG,
                                    "Playback started"
                                )
                            }

                            try {
                                fullAudioOutputStream!!.write(audioData)
                                fullAudioSamples += bytesRead.toLong()
                            } catch (e: IOException) {
                                Log.e(
                                    TAG,
                                    "Full audio write failed: " + e.message
                                )
                            }

                            try {
                                if (chunkOutputStream == null) {
                                    chunkFile = createChunkFile()
                                    chunkOutputStream = FileOutputStream(chunkFile)
                                    writeWavHeader(
                                        chunkOutputStream!!,
                                        CHUNK_SIZE
                                    )
                                    chunkStartTime = System.currentTimeMillis()
                                }

                                chunkOutputStream!!.write(audioData)
                                chunkSamples += bytesRead.toLong()
                                val elapsed =
                                    System.currentTimeMillis() - chunkStartTime
                                if (elapsed >= CHUNK_DURATION_MS || chunkSamples >= CHUNK_SIZE) {
                                    updateWavHeader(chunkOutputStream!!, chunkFile, chunkSamples)
                                    chunkOutputStream!!.close()
                                    Log.d(
                                        TAG,
                                        "Saved chunk: " + chunkFile!!.absolutePath
                                    )
                                    makeEmotionPredictionRequest(chunkFile!!)
                                    chunkOutputStream = null
                                    chunkSamples = 0
                                }
                            } catch (e: IOException) {
                                Log.e(
                                    TAG,
                                    "Chunk write failed: " + e.message
                                )
                            }
                        } else if (isMusicPlaying && (System.currentTimeMillis() - lastSoundTime >= SILENCE_TIMEOUT_MS)) {
                            isMusicPlaying = false
                            finalizeFiles()
                            Log.d(TAG, "Playback ended")
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Audio read error: $bytesRead"
                        )
                    }
                }
                if (isMusicPlaying) {
                    finalizeFiles()
                }
            }
            recordingThread!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed: " + e.message)
            stopSelf()
        }
    }

    private fun makeEmotionPredictionRequest(audioFile: File) {
        val url =
//            "http://192.168.1.183:5000/api/v1/predict_audio" // Replace with your Flask server IP
            "https://bipolar-backend-75820532432.asia-south1.run.app/api/v1/predict_audio"
        val userId = firebaseAuth.currentUser!!.uid // Replace with dynamic user ID if needed

        val requestBody: RequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("userID", userId)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API request failed: " + e.message)
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body!!.string()
                    Log.d(
                        TAG,
                        "Emotion prediction response: $responseBody"
                    )
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val emotion = jsonResponse.optString("emotion", "Unknown")
                        saveEmotionToPrefs(emotion)
                        sendEmotionBroadcast(emotion)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing emotion response: " + e.message)
                        sendEmotionBroadcast("Parse Error")
                    }
                } else {
                    Log.e(
                        TAG,
                        "API request unsuccessful: " + response.code + " " + response.message
                    )
                    Log.e(
                        TAG, "Response body: " + response.body!!
                            .string()
                    )
                }
                response.close()
            }
        })
    }

    private fun sendEmotionBroadcast(emotion: String) {
        val intent = Intent(BROADCAST_ACTION)
            .putExtra("emotion", emotion)
        sendBroadcast(intent)
        Log.d(
            TAG,
            "Broadcast sent with emotion: $emotion"
        )
    }

    private fun initFullAudioFile() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val audioDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (audioDir != null && !audioDir.exists()) audioDir.mkdirs()
            fullAudioFile = File(audioDir, "Playback_$timestamp.wav")
            fullAudioOutputStream = FileOutputStream(fullAudioFile)
            writeWavHeader(fullAudioOutputStream!!, 0)
            fullAudioSamples = 0
        } catch (e: IOException) {
            Log.e(TAG, "Full audio init error: " + e.message)
        }
    }

    private fun createChunkFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return File(audioDir, "Chunk_$timestamp.wav")
    }

    private fun finalizeFiles() {
        try {
            if (fullAudioOutputStream != null) {
                updateWavHeader(fullAudioOutputStream!!, fullAudioFile, fullAudioSamples)
                fullAudioOutputStream!!.close()
                fullAudioOutputStream = null
                Log.d(TAG, "Saved full audio: " + fullAudioFile!!.absolutePath)
            }
            if (chunkOutputStream != null) {
                updateWavHeader(chunkOutputStream!!, chunkFile, chunkSamples)
                chunkOutputStream!!.close()
                chunkOutputStream = null
                Log.d(TAG, "Saved final chunk: " + chunkFile!!.absolutePath)
                makeEmotionPredictionRequest(chunkFile!!)
            }
        } catch (e: IOException) {
            Log.e(TAG, "File finalization error: " + e.message)
        }
    }

    private fun calculateAmplitude(buffer: ShortArray, length: Int): Double {
        var sum: Long = 0
        for (i in 0 until length) {
            sum += (buffer[i] * buffer[i]).toLong()
        }
        return sqrt(sum / length.toDouble()) / 32768.0
    }

    @Throws(IOException::class)
    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Int) {
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2
        val totalDataLen = totalAudioLen * 2

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + totalDataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray())
        header.putInt(totalDataLen)

        header.flip()
        out.write(header.array())
    }

    private fun updateWavHeader(out: FileOutputStream, file: File?, samples: Long) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val totalDataLen = samples.toInt() * 2
                raf.seek(4)
                raf.writeInt(Integer.reverseBytes(36 + totalDataLen))
                raf.seek(40)
                raf.writeInt(Integer.reverseBytes(totalDataLen))
            }
        } catch (e: IOException) {
            Log.e(TAG, "WAV header update failed: " + e.message)
        }
    }

    //    private Notification createNotification() {
    //        Intent notificationIntent = new Intent(this, MainActivity.class);
    //        PendingIntent pendingIntent = PendingIntent.getActivity(
    //                this, 0, notificationIntent,
    //                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    //        );
    //
    //        return new NotificationCompat.Builder(this, "AudioCaptureChannel")
    //                .setContentTitle("Capturing Audio Playback")
    //                .setContentText("Service is running...")
    //                .setSmallIcon(R.drawable.ic_notification)
    //                .setContentIntent(pendingIntent)
    //                .setOngoing(true)
    //                .build();
    //    }
    //
    //    private void createNotificationChannel() {
    //        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    //            String channelName = "Audio Capture Service";
    //            NotificationChannel channel = new NotificationChannel("AudioCaptureChannel", channelName, NotificationManager.IMPORTANCE_LOW);
    //            NotificationManager manager = getSystemService(NotificationManager.class);
    //            if (manager != null) manager.createNotificationChannel(channel);
    //        }
    //    }
    override fun onDestroy() {
        super.onDestroy()
        if (audioRecord != null) {
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
        }
        if (recordingThread != null) {
            recordingThread!!.interrupt()
            recordingThread = null
        }
        if (mediaProjection != null) {
            mediaProjection!!.stop()
            mediaProjection = null
        }
        isRunning = false
        finalizeFiles()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        var isRunning: Boolean = false
        const val BROADCAST_ACTION: String = "com.g292.bipoladisorder.AUDIO_UPDATE"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        private const val CHUNK_DURATION_MS = 60000 // 1 minute
        private const val CHUNK_SIZE = SAMPLE_RATE * CHUNK_DURATION_MS / 1000
        private const val SILENCE_THRESHOLD = 0.001
        private const val SILENCE_TIMEOUT_MS = 5000

        private const val PREFS_NAME = "BipolaDisorderPrefs"
        private const val KEY_AUDIO_EMOTION = "audio_emotion"
    }
}
