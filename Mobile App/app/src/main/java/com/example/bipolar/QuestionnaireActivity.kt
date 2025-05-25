package com.example.bipolar

import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.text.nlclassifier.NLClassifier

class QuestionnaireActivity : AppCompatActivity() {

    private lateinit var submitButton: Button
    private lateinit var response1: EditText
    private lateinit var resultView: TextView

    private var classifier: NLClassifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_questionnaire)

        // Initialize views
        response1 = findViewById(R.id.answer1)
        resultView = findViewById(R.id.resultText)
        submitButton = findViewById(R.id.submitButton)

        // Initialize classifier
        try {
            classifier = NLClassifier.createFromFile(this, "Questionnaire_model_updated.tflite")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        submitButton.setOnClickListener {
            val answers = listOf(
                response1.text.toString()
            )

            val combinedText = answers.joinToString(" ")
            val predictedEmotion = predictEmotion(combinedText)
            resultView.text = "Predicted Emotion: $predictedEmotion"

            saveQuestionnaireEmotionToFirebase(predictedEmotion)
        }
    }

    private fun predictEmotion(text: String): String {
        return classifier?.classify(text)
            ?.maxByOrNull { result: Category -> result.score }
            ?.label ?: "Neutral"
    }

    private fun saveQuestionnaireEmotionToFirebase(emotion: String) {
        val user = FirebaseAuth.getInstance().currentUser

        if (user != null) {
            val db = FirebaseFirestore.getInstance()
            val emotionData = hashMapOf(
                "emotion" to emotion,
                "timestamp" to Timestamp.now()
            )

            db.collection("users")
                .document(user.uid)
                .collection("Questionnaire_emotion")
                .add(emotionData)
                .addOnSuccessListener {
                    Log.d("Firebase", "Emotion saved successfully to Firestore")
                }
                .addOnFailureListener { e ->
                    Log.e("Firebase", "Error saving emotion", e)
                }
        } else {
            Log.e("Firebase", "User not logged in. Cannot save emotion.")
        }
    }
}
