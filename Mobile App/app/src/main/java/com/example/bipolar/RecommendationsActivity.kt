package com.example.bipolar

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class RecommendationsActivity : AppCompatActivity() {

    private lateinit var moodSpinner: Spinner
    private lateinit var stageSpinner: Spinner
    private lateinit var genderSpinner: Spinner
    private lateinit var ageInput: EditText
    private lateinit var recommendationsRecyclerView: RecyclerView
    private lateinit var activitiesLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var inputContainer: LinearLayout
    private lateinit var infoButton: ImageButton
    private lateinit var unlockStageIcon: ImageButton
    private lateinit var stageContainer: LinearLayout

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val moodOptions = arrayOf("Happy", "Sad", "Angry", "Neutral", "Fear", "Disgust", "Surprise")
    private val stageOptions = arrayOf("Euthymia", "Hypomania", "Depression", "Mixed Episodes", "Mania")
    private val genderOptions = arrayOf("Male", "Female")

    private val secretPin = "1234" // Admin PIN

    private var inputsVisible = false
    private var stageUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommendations)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        recommendationsRecyclerView.layoutManager = LinearLayoutManager(this)
        setupSpinners()

        // Hide inputs container and stage container initially
        inputContainer.visibility = View.GONE
        stageContainer.visibility = View.GONE

        // Hide Get Recommendations button completely
        // (so no manual refresh button)
        // No need to set click listener for it

        fetchUserDataAndLatestStage()
        fetchLatestMoodFromTextAudioVideo()

        setupUnlockStageIcon()

        // Info button toggles inputs and fetches recommendations immediately
        infoButton.setOnClickListener {
            inputsVisible = !inputsVisible
            if (inputsVisible) {
                inputContainer.visibility = View.VISIBLE
                tryAutoFetchRecommendations()  // Fetch immediately every time inputs show
            } else {
                inputContainer.visibility = View.GONE
                activitiesLabel.visibility = View.GONE
                recommendationsRecyclerView.visibility = View.GONE
                recommendationsRecyclerView.adapter = null  // Clear adapter on hide
            }
        }
    }

    private fun initializeViews() {
        moodSpinner = findViewById(R.id.moodSpinner)
        stageSpinner = findViewById(R.id.stageSpinner)
        genderSpinner = findViewById(R.id.genderSpinner)
        ageInput = findViewById(R.id.ageInput)
        recommendationsRecyclerView = findViewById(R.id.recommendationsRecyclerView)
        activitiesLabel = findViewById(R.id.activitiesLabel)
        progressBar = findViewById(R.id.progressBar)
        inputContainer = findViewById(R.id.inputContainer)
        infoButton = findViewById(R.id.infoButton)
        unlockStageIcon = findViewById(R.id.unlockStageIcon)
        stageContainer = findViewById(R.id.stageContainer)
    }

    private fun setupSpinners() {
        setupSpinner(moodSpinner, moodOptions, "Select the mood")
        setupSpinner(stageSpinner, stageOptions, "Select the stage")
        setupSpinner(genderSpinner, genderOptions, "Select your gender")
    }

    private fun setupSpinner(spinner: Spinner, options: Array<String>, defaultText: String) {
        val items = listOf(defaultText) + options
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun fetchUserDataAndLatestStage() {
        val userId = auth.currentUser?.uid ?: return showToast("No user found")

        db.collection("users").document(userId).get()
            .addOnSuccessListener { doc ->
                doc?.let {
                    it.getLong("age")?.let { age ->
                        ageInput.setText(age.toString())
                        ageInput.isEnabled = false
                        ageInput.setBackgroundColor(Color.TRANSPARENT)
                    }

                    it.getString("gender")?.let { gender ->
                        val index = genderOptions.indexOf(gender) + 1
                        if (index > 0) {
                            genderSpinner.setSelection(index)
                            lockSpinner(genderSpinner)
                        }
                    }

                    fetchLatestBipolarStage(userId)
                }
            }
            .addOnFailureListener {
                Log.e("Firebase", "User fetch failed", it)
                showToast("Error loading user info")
            }
            .addOnCompleteListener {
                tryAutoFetchRecommendations()
            }
    }

    private fun fetchLatestBipolarStage(userId: String) {
        db.collection("users")
            .document(userId)
            .collection("bipolar_stages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { result ->
                result.firstOrNull()?.getString("emotion")?.let { emotion ->
                    val index = stageOptions.indexOf(emotion) + 1
                    if (index > 0) {
                        stageSpinner.setSelection(index)
                        lockSpinner(stageSpinner)
                    }
                }
            }
            .addOnFailureListener {
                Log.e("Firebase", "Stage fetch failed", it)
                showToast("Error loading stage info")
            }
            .addOnCompleteListener {
                tryAutoFetchRecommendations()
            }
    }

    private fun fetchLatestMoodFromTextAudioVideo() {
        val userId = auth.currentUser?.uid ?: run {
            Log.e("MoodFetch", "No logged-in user")
            return
        }
        val collections = listOf("text_emotions", "audio_emotions", "Video_emotions")
        val moodResults = mutableListOf<Pair<String, Timestamp>>()

        val tasks = collections.map { collection ->
            db.collection("users")
                .document(userId)
                .collection(collection)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(1)
                .get()
        }

        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            for (task in tasks) {
                if (task.isSuccessful) {
                    val snapshot = task.result
                    if (!snapshot.isEmpty) {
                        val doc = snapshot.documents[0]
                        val emotion = doc.getString("emotion")
                        val timestamp = doc.getTimestamp("timestamp")
                        if (!emotion.isNullOrEmpty() && timestamp != null) {
                            Log.d("MoodFetch", "Found $emotion at $timestamp")
                            moodResults.add(Pair(emotion.capitalize(), timestamp))
                        }
                    }
                } else {
                    Log.e("MoodFetch", "Task failed", task.exception)
                }
            }

            val latestMood = moodResults.maxByOrNull { it.second }?.first
            Log.d("MoodFetch", "Latest mood selected: $latestMood")

            runOnUiThread {
                val moodIndex = moodOptions.indexOf(latestMood ?: "") + 1
                if (moodIndex > 0) {
                    moodSpinner.setSelection(moodIndex)
                    lockSpinner(moodSpinner)
                } else {
                    moodSpinner.setSelection(0)
                    showToast("Mood found but not in options list")
                }
            }
        }
    }

    private fun tryAutoFetchRecommendations() {
        val mood = moodSpinner.selectedItem.toString()
        val stage = if(stageUnlocked) stageSpinner.selectedItem.toString() else "Euthymia" // default stage if locked
        val gender = genderSpinner.selectedItem.toString()
        val age = ageInput.text.toString().trim().toIntOrNull()

        if (mood.contains("Select") || stage.contains("Select") || gender.contains("Select") || age == null || age <= 0) {
            return
        }

        progressBar.visibility = View.VISIBLE
        fetchRecommendations(mood, stage, age, gender)
    }

    private fun setupUnlockStageIcon() {
        unlockStageIcon.setOnClickListener {
            showPinDialog()
        }
    }

    private fun fetchRecommendations(mood: String, stage: String, age: Int, gender: String) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://web-production-d7819.up.railway.app/")
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(ApiService::class.java)
        val input = UserInput(mood, stage, age, gender)

        service.getRecommendations(input).enqueue(object : Callback<RecommendationResponse> {
            override fun onResponse(call: Call<RecommendationResponse>, response: Response<RecommendationResponse>) {
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    response.body()?.recommendations?.let { recs ->
                        if (recs.isNotEmpty()) {
                            activitiesLabel.visibility = View.VISIBLE
                            recommendationsRecyclerView.visibility = View.VISIBLE
                            recommendationsRecyclerView.adapter = RecommendationAdapter(recs)
                        } else {
                            showToast("No recommendations found")
                        }
                    }
                } else {
                    showToast("Server error: ${response.message()}")
                }
            }

            override fun onFailure(call: Call<RecommendationResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                Log.e("API Error", "Call failed", t)
                showToast("Could not connect to server")
            }
        })
    }

    private fun getOkHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        return OkHttpClient.Builder().addInterceptor(logger).build()
    }

    private fun lockSpinner(spinner: Spinner) {
        spinner.isEnabled = false
        spinner.background?.setColorFilter(Color.LTGRAY, PorterDuff.Mode.SRC_IN)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showPinDialog() {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
        }

        AlertDialog.Builder(this)
            .setTitle("Admin PIN Required")
            .setView(pinInput)
            .setPositiveButton("OK") { dialog, _ ->
                val enteredPin = pinInput.text.toString()
                if (enteredPin == secretPin) {
                    Toast.makeText(this, "Access granted", Toast.LENGTH_SHORT).show()
                    stageContainer.visibility = View.VISIBLE
                    stageSpinner.isEnabled = true
                    unlockStageIcon.visibility = View.GONE
                    stageUnlocked = true
                    tryAutoFetchRecommendations() // Fetch with unlocked stage now
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .show()
    }
}
