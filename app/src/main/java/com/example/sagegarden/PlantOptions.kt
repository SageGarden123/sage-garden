@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.google.maps.android.compose.*

// ============================================================================
// DROPDOWN OPTIONS
// ============================================================================

val sunOptions = listOf("Full", "Full-Partial", "Partial", "Partial-Shade", "Shade", "Unknown")
val waterOptions = listOf("Low", "Moderate", "High", "Unknown")
val soilOptions = listOf(
    "Sandy", "Loamy", "Clay", "Silty", "Peaty", "Chalky", "Rocky/Stony", "Potting Mix", "Other", "Unknown"
)
val soilPhOptions = listOf("Acidic", "Acidic–Neutral", "Neutral", "Neutral–Alkaline", "Alkaline", "Acidic–Alkaline", "Unknown")
val categoryOptions = listOf(
    "Trees", "Shrubs", "Ground Cover", "Climbers/Vines", "Grasses", "Ferns", "Perennials",
    "Annuals", "Bulbs", "Succulents", "Palms/Cycads", "Aquatic", "Herbs", "Other"
)

/** Emoji used as this category's map marker — falls back to a plain pin for "Other"/unset. */
fun categoryMarkerEmoji(category: String): String = when (category) {
    "Trees" -> "🌳"
    "Shrubs" -> "🪴"
    "Ground Cover" -> "🍀"
    "Climbers/Vines" -> "🍃"
    "Grasses" -> "🌾"
    "Ferns" -> "🌿"
    "Perennials" -> "🌸"
    "Annuals" -> "🌻"
    "Bulbs" -> "🌷"
    "Succulents" -> "🌵"
    "Palms/Cycads" -> "🌴"
    "Aquatic" -> "🪷"
    "Herbs" -> "🌱"
    else -> "📍"
}

/** Category → dot colour for the custom (uploaded-drawing) map, which uses plain colour-coded
 * dots instead of the real map's emoji markers — a drawing's own colours/background can't be
 * predicted, so an emoji icon can disappear against it in a way it never does on the real map's
 * consistent satellite/hybrid tile background. */
fun categoryMarkerColor(category: String): Color = when (category) {
    "Trees" -> Color(0xFF2E7D32)
    "Shrubs" -> Color(0xFF558B2F)
    "Ground Cover" -> Color(0xFF9E9D24)
    "Climbers/Vines" -> Color(0xFF00897B)
    "Grasses" -> Color(0xFFF9A825)
    "Ferns" -> Color(0xFF00695C)
    "Perennials" -> Color(0xFFD81B60)
    "Annuals" -> Color(0xFFFB8C00)
    "Bulbs" -> Color(0xFF8E24AA)
    "Succulents" -> Color(0xFF00ACC1)
    "Palms/Cycads" -> Color(0xFF6D4C41)
    "Aquatic" -> Color(0xFF1E88E5)
    "Herbs" -> Color(0xFF43A047)
    else -> Color(0xFFFF7A45)
}
val frostOptions = listOf("Hardy", "Half-hardy", "Tender", "Tender (indoor only)", "Unknown")
val nativeOptions = listOf("Native (Aus)", "Exotic")
val pollinatorOptions = listOf(
    "Yes - bees", "Yes - butterflies", "Yes - bees & butterflies",
    "Yes - birds", "No", "Other"
)
