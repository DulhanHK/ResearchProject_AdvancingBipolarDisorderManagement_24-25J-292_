package com.example.bipolar

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class CameraEmotionActivity : AppCompatActivity() {

    private lateinit var tflite: Interpreter
    private lateinit var previewView: PreviewView
    private lateinit var emotionTextView: TextView
    private lateinit var emotionImageView: ImageView

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_camera_emotion)

        previewView = findViewById(R.id.previewView)
        emotionTextView = findViewById(R.id.emotionTextView)
        emotionImageView = findViewById(R.id.ivPreview)

        findViewById<android.widget.Button>(R.id.backBTN).setOnClickListener {
            finish()
        }

        tflite = Interpreter(loadModelFile())
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, EmotionAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("CameraEmotionActivity", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    inner class EmotionAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())
                runOnUiThread {
                    emotionImageView.setImageBitmap(rotatedBitmap)
                }

                val emotion = runModel(rotatedBitmap)
                runOnUiThread {
                    emotionTextView.text = emotion
                }
            }

            imageProxy.close()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun runModel(image: Bitmap): String {
        val inputBuffer = convertBitmapToByteBuffer(image)
        val outputBuffer = Array(1) { FloatArray(7) }
        tflite.run(inputBuffer, outputBuffer)

        val emotions = arrayOf("Angry", "Disgust", "Fear", "Happy", "Sad", "Surprise", "Neutral")
        val maxIndex = outputBuffer[0].indices.maxByOrNull { outputBuffer[0][it] } ?: -1

        val detectedEmotion = if (maxIndex != -1) emotions[maxIndex] else "Unknown"

        saveEmotionToFirebase(detectedEmotion)

        // 🔥 Call recommendation after saving emotion
        fetchLatestVideoMoodAndCallAPI()

        return detectedEmotion
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * 48 * 48 * 1 * 4).order(ByteOrder.nativeOrder())
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        for (i in 0 until 48) {
            for (j in 0 until 48) {
                val pixel = resizedBitmap.getPixel(j, i)
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                buffer.putFloat(gray)
            }
        }
        return buffer
    }

    private fun loadModelFile(): ByteBuffer {
        val assetFileDescriptor = assets.openFd("emotion_recognition_model1.tflite")
        val inputStream = assetFileDescriptor.createInputStream()
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun saveEmotionToFirebase(emotion: String) {
        val user = FirebaseAuth.getInstance().currentUser

        if (user != null) {
            val db = FirebaseFirestore.getInstance()
            val emotionData = hashMapOf(
                "emotion" to emotion,
                "timestamp" to Timestamp.now()
            )

            db.collection("users")
                .document(user.uid)
                .collection("Video_emotions")
                .add(emotionData)
                .addOnSuccessListener {
                    Log.d("Firebase", "Emotion saved")
                }
                .addOnFailureListener {
                    Log.e("Firebase", "Error saving emotion", it)
                }
        } else {
            Log.e("Firebase", "User not logged in")
        }
    }

    private fun fetchLatestVideoMoodAndCallAPI() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val db = FirebaseFirestore.getInstance()
            db.collection("users")
                .document(user.uid)
                .collection("Video_emotions")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener { documents ->
                    if (!documents.isEmpty) {
                        val emotion = documents.first().getString("emotion")
                        emotion?.let {
                            Log.d("Recommendation", "Latest Emotion: $it")
                            callRecommendationAPI(it)
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("Recommendation", "Failed to fetch emotion", it)
                }
        }
    }

    private fun callRecommendationAPI(videoEmotion: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.e("RecommendationAPI", "User not logged in")
            return
        }

        val url = "https://mybipolarapp.loca.lt/api/v1/predict_bipolar_stage"
        val client = OkHttpClient()

        val json = JSONObject()
        json.put("userID", user.uid)
        json.put("video_emotion", videoEmotion)
        json.put("text_emotion", "Happy")   // Replace with actual value
        json.put("audio_emotion", "Happy")  // Replace with actual value
        json.put("activity", "Low")       // Replace with actual value

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("RecommendationAPI", "API call failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d("RecommendationAPI", "Response: $responseBody")
                    runOnUiThread {
                        Toast.makeText(this@CameraEmotionActivity, "Recommendation fetched", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e("RecommendationAPI", "Error: ${response.code}")
                }
            }
        })
    }

    override fun onDestroy() {
        tflite.close()
        super.onDestroy()
    }
}
