package com.example.bipolar

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("recommendations/")
    fun getRecommendations(@Body userInput: UserInput): Call<RecommendationResponse>
}
