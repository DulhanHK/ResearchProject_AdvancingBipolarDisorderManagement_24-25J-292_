package com.example.bipolar

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)  // Ensure this layout file exists

        // Initialize Firebase Auth and Firestore
        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Get references to input fields
        val registerButton = findViewById<Button>(R.id.RegisterButton)
        val registerEmail = findViewById<EditText>(R.id.RegisterEmail)
        val registerPassword = findViewById<EditText>(R.id.RegisterPassword)
        val registerConfirmPassword = findViewById<EditText>(R.id.RegisterConfirmPassword)
        val registerAge = findViewById<EditText>(R.id.RegisterAge)
        val registerGenderSpinner = findViewById<Spinner>(R.id.RegisterGenderSpinner)

        // Set up Spinner options for Gender (Male, Female)
        val genderOptions = listOf("Select Gender", "Male", "Female")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, genderOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        registerGenderSpinner.adapter = adapter

        // Set the button click listener for registering a new user
        registerButton.setOnClickListener {
            val email = registerEmail.text.toString().trim()
            val password = registerPassword.text.toString().trim()
            val confirmPassword = registerConfirmPassword.text.toString().trim()
            val ageStr = registerAge.text.toString().trim()
            val gender = registerGenderSpinner.selectedItem.toString()

            // Validate all fields
            if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty() || ageStr.isEmpty() || gender == "Select Gender") {
                Toast.makeText(this, "All fields are required and gender must be selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check if passwords match
            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate age (must be a positive number)
            val age = ageStr.toIntOrNull()
            if (age == null || age <= 0) {
                Toast.makeText(this, "Please enter a valid age", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Register user with Firebase Authentication
            firebaseAuth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val userId = firebaseAuth.currentUser?.uid

                    // Save additional user data (age, gender) to Firestore
                    if (userId != null) {
                        val userMap = hashMapOf(
                            "email" to email,
                            "age" to age,
                            "gender" to gender
                        )

                        firestore.collection("users").document(userId)
                            .set(userMap)
                            .addOnSuccessListener {
                                // Registration successful, navigate to Login Activity
                                Toast.makeText(this, "Registration successful", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this, LoginActivity::class.java))
                                finish()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Failed to save user data: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                } else {
                    Toast.makeText(this, task.exception?.message ?: "Registration failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Set the "Already have an account?" link click listener to go to LoginActivity
        val redirectToLogin = findViewById<TextView>(R.id.RedirectLogin)
        redirectToLogin.setOnClickListener {
            // Redirect to LoginActivity when the user clicks on the "Already have an account?" text
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()  // Optionally finish this activity to prevent going back to the registration page
        }
    }
}
