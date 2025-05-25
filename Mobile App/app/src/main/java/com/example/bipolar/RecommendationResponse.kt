package com.example.bipolar

data class RecommendationResponse(
    val message: String,
    val recommendations: List<Recommendation>
)

data class Recommendation(
    val activity: String,
    val description: String,
    val duration: Int,
    val image_url: String
)
