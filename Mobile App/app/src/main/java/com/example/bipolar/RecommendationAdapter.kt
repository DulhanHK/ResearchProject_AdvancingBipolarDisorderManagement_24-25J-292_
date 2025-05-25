package com.example.bipolar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class RecommendationAdapter(
    private val recommendations: List<Recommendation>
) : RecyclerView.Adapter<RecommendationAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val activityText: TextView = itemView.findViewById(R.id.activityText)
        val descriptionText: TextView = itemView.findViewById(R.id.descriptionText)
        val durationText: TextView = itemView.findViewById(R.id.durationText)
        val activityImage: ImageView = itemView.findViewById(R.id.activityImage) // ImageView for the activity image
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recommendation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recommendation = recommendations[position]
        holder.activityText.text = recommendation.activity
        holder.descriptionText.text = recommendation.description
        holder.durationText.text = "Duration: ${recommendation.duration} minutes"

        // Load the image using Glide from the Firebase URL
        val imageUrl = recommendation.image_url // This should already be the full URL from Firebase
        Glide.with(holder.itemView.context)
            .load(imageUrl)  // Directly use the Firebase URL
            .into(holder.activityImage) // Set the image into the ImageView
    }

    override fun getItemCount(): Int {
        return recommendations.size
    }
}
