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
import kotlin.math.abs
import kotlin.math.sqrt

data class AuditIssue(val title: String, val explanation: String, val plants: List<PlantEntity>, val detailLines: List<String> = emptyList()) {
    val count: Int get() = if (detailLines.isNotEmpty()) detailLines.size else plants.size
}

/** Fraction-of-map-diagonal distance below which two plants are considered "right next to each other" on the custom map. No real-world scale is known for that map, so this is an approximate guide, not a precise spacing measurement. */
private const val CUSTOM_MAP_PROXIMITY_THRESHOLD = 0.05f

/** Real-world distance (meters) below which two plants placed on the Google Map are considered "right next to each other". */
private const val REAL_MAP_PROXIMITY_METERS = 2.0

private fun auditSunLevel(sun: String): Float? = when (sun) {
    "Full" -> 3f
    "Full-Partial" -> 2.5f
    "Partial" -> 2f
    "Partial-Shade" -> 1f
    "Shade" -> 0f
    else -> null
}

private fun isFrostTender(plant: PlantEntity) = plant.frost == "Tender" || plant.frost == "Half-hardy"

/** True if plants a and b are "right next to" each other, preferring real-world GPS distance when both have it, falling back to custom-map fraction distance otherwise. */
private fun isNearby(a: PlantEntity, b: PlantEntity): Boolean {
    val aLat = a.lat; val aLng = a.lng; val bLat = b.lat; val bLng = b.lng
    if (aLat != null && aLng != null && bLat != null && bLng != null) {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLng = 111_320.0 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2.0))
        val dy = (aLat - bLat) * metersPerDegreeLat
        val dx = (aLng - bLng) * metersPerDegreeLng
        return sqrt(dx * dx + dy * dy) <= REAL_MAP_PROXIMITY_METERS
    }
    val ax = a.mapX; val ay = a.mapY; val bx = b.mapX; val by = b.mapY
    if (ax != null && ay != null && bx != null && by != null) {
        val dx = (ax - bx).toFloat(); val dy = (ay - by).toFloat()
        return sqrt(dx * dx + dy * dy) <= CUSTOM_MAP_PROXIMITY_THRESHOLD
    }
    return false
}

private fun describeExposureConflict(a: PlantEntity, b: PlantEntity): String = when {
    isFrostTender(a) && b.sun == "Full" ->
        "${a.name} (frost-tender) is right next to ${b.name} (full sun) — exposed spots lose the most heat on cold nights"
    isFrostTender(b) && a.sun == "Full" ->
        "${b.name} (frost-tender) is right next to ${a.name} (full sun) — exposed spots lose the most heat on cold nights"
    else ->
        "${a.name} (${a.sun.ifBlank { "unknown sun" }}) is right next to ${b.name} (${b.sun.ifBlank { "unknown sun" }}) — quite different sun needs for such close neighbors"
}

/** Flags plants placed close together on either map whose sun/frost needs clash — geometry + attributes only, no plant-size or companion-planting data. */
private fun findExposureConflicts(plants: List<PlantEntity>): List<Pair<PlantEntity, PlantEntity>> {
    val withCoords = plants.filter { (it.lat != null && it.lng != null) || (it.mapX != null && it.mapY != null) }
    val pairs = mutableListOf<Pair<PlantEntity, PlantEntity>>()
    for (i in withCoords.indices) {
        for (j in i + 1 until withCoords.size) {
            val a = withCoords[i]; val b = withCoords[j]
            if (!isNearby(a, b)) continue

            val frostClash = (isFrostTender(a) && b.sun == "Full") || (isFrostTender(b) && a.sun == "Full")
            val levelA = auditSunLevel(a.sun); val levelB = auditSunLevel(b.sun)
            val sunClash = levelA != null && levelB != null && abs(levelA - levelB) >= 2f

            if (frostClash || sunClash) pairs.add(a to b)
        }
    }
    return pairs
}

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

            val exposureConflicts = findExposureConflicts(plants)
            if (exposureConflicts.isNotEmpty()) add(
                AuditIssue(
                    "Placement conflicts",
                    "Plants placed close together on the map with clashing sun or frost needs. Based on map position only — there's no plant-size or true companion-planting data yet, so treat this as a rough guide.",
                    plants = exposureConflicts.flatMap { (a, b) -> listOf(a, b) }.distinctBy { it.id },
                    detailLines = exposureConflicts.map { (a, b) -> describeExposureConflict(a, b) }
                )
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

            val overdueFeed = plants.filter { computeFeedStatus(it, now)?.nextDueMillis?.let { d -> d < now } == true }
            if (overdueFeed.isNotEmpty()) add(
                AuditIssue("Overdue for feeding", "Based on last fed date and frequency.", overdueFeed)
            )

            val frostRisk = frostTenderOutdoorPlants(plants)
            if (frostRisk.isNotEmpty()) add(
                AuditIssue("Frost-tender & outdoors", "Worth keeping an eye on the forecast for these — consider covering on cold nights.", frostRisk)
            )
        }.sortedBy { it.title }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Garden audit", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(4.dp))
        Text("${issues.sumOf { it.count }} item(s) across ${issues.size} check(s)", fontSize = 12.sp, color = Color.Gray)
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
                            Text("${issue.title} (${issue.count})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(issue.explanation, fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(if (expanded) "▾" else "▸", color = Color.Gray)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(10.dp))
                        if (issue.detailLines.isNotEmpty()) {
                            issue.detailLines.forEach { line ->
                                Text("• $line", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        } else {
                            issue.plants.forEach { p ->
                                Text("• ${p.name}${if (p.location.isNotBlank()) " (${p.location})" else ""}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
