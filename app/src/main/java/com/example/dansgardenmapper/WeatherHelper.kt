package com.example.dansgardenmapper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class RainForecast(val maxProbabilityPercent: Int, val totalPrecipitationMm: Double)

object WeatherHelper {
    suspend fun fetchTodayForecast(lat: Double, lng: Double): RainForecast? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
                    "&daily=precipitation_probability_max,precipitation_sum&timezone=auto&forecast_days=1"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val daily = json.optJSONObject("daily") ?: return@withContext null
                val prob = daily.optJSONArray("precipitation_probability_max")?.optInt(0) ?: return@withContext null
                val precip = daily.optJSONArray("precipitation_sum")?.optDouble(0) ?: 0.0
                RainForecast(prob, precip)
            }
        } catch (e: Exception) { null }
    }
}