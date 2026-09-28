@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.*
import java.util.Date

// ============================================================================
// FAQ SCREEN (accordion - separate page, linked from Help)
// ============================================================================

data class FaqItem(val question: String, val answer: String)

val faqItems = listOf(
    FaqItem(
        "What does this app do?",
        "Sage Garden helps you track every plant in your garden — photos, care history, and location on a real-world or hand-drawn map — with smart reminders for watering, feeding, fertilising and pruning, weather-aware skipping, a sun exposure map, companion planting/spacing checks, watering cost & usage tracking, and Sage, a built-in AI assistant for gardening questions. It's entirely free — see \"Are there any limits?\" below for the two small exceptions."
    ),
    FaqItem(
        "Where is my plant data stored?",
        "All plant details (name, care info, location, etc.) are stored locally in a database on this device only. Nothing is sent to a server unless you choose cloud photo storage below."
    ),
    FaqItem(
        "Where are my photos stored, and what's recommended?",
        "By default, photos you take or upload are stored locally on this device and only referenced from within the app. This means they won't automatically back up or " +
                "sync to another device, and could be lost if this device is lost, reset, or the app is uninstalled. For safer, more portable storage, it's recommended to " +
                "connect Dropbox in the Photo storage section below - photos you take will then be saved there automatically. " +
                "I recommend compressing your photos to <1MB, so you can save more photos on the cloud."
    ),
    FaqItem(
        "How do I find a plant I've already added?",
        "Use the List tab — plants are grouped alphabetically by garden location, and the search bar matches against most short text fields: plant name, scientific name, location, Plant ID, sun, water, soil, soil pH, category, frost tolerance, native/exotic, pollinator-friendly, source, and watering system. It doesn't search Notes (free-form text) or numeric/date fields like latitude/longitude."
    ),
    FaqItem(
        "How do I back up or move my data to another device?",
        "There are two options in Help → Data, and they cover different things. \"Export CSV\" downloads your plant and irrigation data (not photos) as a spreadsheet — good for a quick data-only copy, or bulk-editing in a spreadsheet app; bring it back in with \"Import CSV\" on another install. \"Backup & restore all data\" is the fuller option — it backs up everything (plants, irrigation, sun zones, Tuya mappings, your custom map, growth/care history, and all app settings) to a Dropbox folder you choose, and restores it on another device in one go. Locally-stored photos aren't included in either — connect Dropbox photo storage first if you want photos to carry across too."
    ),
    FaqItem(
        "What format should my CSV files be in?",
        "For plant imports, the file needs a header row with at least a \"Plant\" column (name); optional columns are Plant ID, Scientific name, Location, Date planted, Source, Sun, Soil, Water, Frost, Native/Exotic, Pollinator-Friendly, Notes, Latitude, Longitude, and Watering System — export a CSV first to see the exact layout. \" For irrigation log imports, the header row needs Zone, StartTime, and DurationMinutes; Outlet and Source are optional. StartTime accepts either epoch milliseconds or a DateTime like \\\"2024-01-31 06:30:00\\\".\" Column order and capitalisation don't matter, but names need to match — if something's missing or the file is empty, you'll get a pop-up explaining exactly what's wrong."
    ),
    FaqItem(
        "Are there any limits?",
        "Sage Garden is free — every feature is unlocked for everyone: unlimited plants and log history, watering reminders, the photo log, plant care widget, Dropbox backup, weather-aware reminders, Tuya/Rachio smart-irrigation integration, the sun map, companion planting/spacing audit, cost & water usage tracking, and growth photo timelines. The only limits are on the Sage AI assistant (${EntitlementManager.FREE_SAGE_PROMPT_LIMIT} free questions total) and AI plant-photo identification ($PLANTNET_TRIAL_DAILY_LIMIT identifications a day) — both of which call paid AI services behind the scenes."
    )
)

@Composable
fun FaqScreen(onBack: () -> Unit) {
    var expandedIndex by remember { mutableStateOf(-1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text("Frequently Asked Questions", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(12.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            faqItems.forEachIndexed { index, faq ->
                val expanded = expandedIndex == index
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { expandedIndex = if (expanded) -1 else index }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(faq.question, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(if (expanded) "▾" else "▸", color = Color.Gray)
                        }
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            Text(faq.answer, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
