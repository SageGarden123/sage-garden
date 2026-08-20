package com.example.dansgardenmapper

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NotificationDetailsScreen(type: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: PlantViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val plants by viewModel.plants.collectAsState()
    val now = remember { System.currentTimeMillis() }

    val (title, matchingPlants, subtitleFor) = remember(plants, type, now) {
        when (type) {
            "fertilise" -> Triple(
                "Plants needing fertiliser",
                plants.filter { computeFertiliseStatus(it, now)?.nextDueMillis?.let { d -> d <= now } == true },
                { p: PlantEntity -> computeFertiliseStatus(p, now)?.label ?: "" }
            )
            "prune" -> Triple(
                "Plants needing pruning",
                plants.filter { computePruneStatus(it, now)?.nextDueMillis?.let { d -> d <= now } == true },
                { p: PlantEntity -> computePruneStatus(p, now)?.label ?: "" }
            )
            "frost" -> Triple(
                "Frost risk — protect these plants",
                frostTenderOutdoorPlants(plants),
                { p: PlantEntity -> "Frost: ${p.frost}" }
            )
            else -> Triple(
                "Plants needing water",
                plants.filter { computeWateringStatus(it, now)?.nextDueMillis?.let { d -> d <= now } == true },
                { p: PlantEntity -> computeWateringStatus(p, now)?.label ?: "" }
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(14.dp))

        if (matchingPlants.isEmpty()) {
            Text("Nothing needs attention right now.", color = Color.Gray, fontSize = 13.sp)
        } else {
            matchingPlants.forEach { plant ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(plant.location.ifBlank { "No location" }, fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(subtitleFor(plant), fontSize = 12.sp, color = Color(0xFFB23B3B), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}