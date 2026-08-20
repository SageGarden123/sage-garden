package com.example.dansgardenmapper

import android.app.Application
import androidx.compose.foundation.clickable
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

data class AuditIssue(val title: String, val explanation: String, val plants: List<PlantEntity>)

@Composable
fun AuditScreen() {
    val context = LocalContext.current
    val viewModel: PlantViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val sunViewModel: SunZoneViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val plants by viewModel.plants.collectAsState()
    val zones by sunViewModel.zones.collectAsState()
    val now = remember { System.currentTimeMillis() }

    val issues = remember(plants, zones, now) {
        buildList {
            val highWaterManual = plants.filter { it.water == "High" && it.manualWateringOnly }
            if (highWaterManual.isNotEmpty()) add(
                AuditIssue("High water need, hand-watered only", "These plants need frequent water but aren't on an automatic irrigation path — worth checking they're not being missed.", highWaterManual)
            )

            val sunMismatched = plants.mapNotNull { p -> sunMismatchLabel(p, zones)?.let { p } }
            if (sunMismatched.isNotEmpty()) add(
                AuditIssue("Sun exposure mismatch", "Based on your sun map, these plants may be getting more or less sun than they need.", sunMismatched)
            )

            val noPhoto = plants.filter { it.photoUri == null && it.photoUris.isEmpty() }
            if (noPhoto.isNotEmpty()) add(
                AuditIssue("No photos", "These plants have no photo saved yet.", noPhoto)
            )

            val noLocation = plants.filter { it.lat == null && it.mapX == null }
            if (noLocation.isNotEmpty()) add(
                AuditIssue("No map location", "These plants aren't placed on either map, so they won't show up when browsing by location.", noLocation)
            )

            val noWateringSchedule = plants.filter { it.wateringFrequencyDays == null && it.summerWateringFrequencyDays == null && it.winterWateringFrequencyDays == null }
            if (noWateringSchedule.isNotEmpty()) add(
                AuditIssue("No watering schedule", "No watering frequency is set, so these plants won't appear in reminders or the \"needs watering\" list.", noWateringSchedule)
            )

            val overdueWatering = plants.filter { computeWateringStatus(it, now)?.nextDueMillis?.let { d -> d < now } == true }
            if (overdueWatering.isNotEmpty()) add(
                AuditIssue("Overdue for watering", "Based on last watered date and frequency.", overdueWatering)
            )

            val overdueFertilise = plants.filter { computeFertiliseStatus(it, now)?.nextDueMillis?.let { d -> d < now } == true }
            if (overdueFertilise.isNotEmpty()) add(
                AuditIssue("Overdue for fertilising", "Based on last fertilised date and frequency.", overdueFertilise)
            )

            val overduePrune = plants.filter { computePruneStatus(it, now)?.nextDueMillis?.let { d -> d < now } == true }
            if (overduePrune.isNotEmpty()) add(
                AuditIssue("Overdue for pruning", "Based on last pruned date and frequency.", overduePrune)
            )

            val frostRisk = frostTenderOutdoorPlants(plants)
            if (frostRisk.isNotEmpty()) add(
                AuditIssue("Frost-tender & outdoors", "Worth keeping an eye on the forecast for these — consider covering on cold nights.", frostRisk)
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Garden audit", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(4.dp))
        Text("${issues.sumOf { it.plants.size }} item(s) across ${issues.size} check(s)", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        if (issues.isEmpty()) {
            Text("No issues found — nice work! 🌿", color = Color(0xFF3A5A40), fontSize = 14.sp)
        }

        issues.forEach { issue ->
            var expanded by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${issue.title} (${issue.plants.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(issue.explanation, fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(if (expanded) "▾" else "▸", color = Color.Gray)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(10.dp))
                        issue.plants.forEach { p ->
                            Text("• ${p.name}${if (p.location.isNotBlank()) " (${p.location})" else ""}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}