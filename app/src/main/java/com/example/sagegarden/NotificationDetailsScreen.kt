package com.example.sagegarden

import androidx.compose.material3.MaterialTheme

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

@Composable
fun NotificationDetailsScreen(type: String, onBack: () -> Unit, onOpenZone: (String) -> Unit = {}) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }

    // Every known garden, not just the active one — WateringReminderWorker (which built the
    // notification this screen is reached from) already checks every garden this device has
    // access to, so a "plant X needs watering" tap can be about a plant in ANY of them. Reading
    // only PlantViewModel.plants (active-garden-scoped) meant this screen could show "nothing due"
    // for the exact plant the notification was just about, whenever a different garden happened to
    // be active at the time — confirmed in practice 2026-09-15. Each garden's plants are checked
    // against that garden's own hemisphere for watering status, same reasoning as the widget/worker.
    var gardenPlants by remember { mutableStateOf<List<Pair<String, List<PlantEntity>>>>(emptyList()) }
    LaunchedEffect(Unit) {
        // refreshKnownGardens FIRST — allKnownGardenIds reads GardenMembershipStore's local cache,
        // which is only otherwise kept fresh by the foreground app's own 60s loop. A cold-started
        // MainActivity (the common way this screen is actually reached — tapping a notification)
        // hasn't necessarily had that loop tick yet, so the cache can still be missing a garden the
        // tapped notification is genuinely about — confirmed in practice 2026-09-16: same symptom
        // ("nothing due" here despite the widget/app agreeing something was) survived the first fix
        // above because THIS gap, not the active-garden one, was still live. Same lesson as Round 5's
        // widget-config-screen fix for the identical stale-known-gardens-cache bug class.
        GardenMembershipClient.refreshKnownGardens(context)
        val dao = AppDatabase.getInstance(context).plantDao()
        gardenPlants = allKnownGardenIds(context).map { gardenId -> gardenId to dao.getAllOnceForGarden(gardenId) }
    }
    val plants = remember(gardenPlants) { gardenPlants.flatMap { it.second } }
    val hemisphereByGarden = remember(gardenPlants) {
        gardenPlants.associate { (gardenId, _) -> gardenId to GardenSettings.of(context, gardenId).hemisphere }
    }
    // Only worth labelling rows by garden when more than one is actually in scope — a single-garden
    // device renders exactly as it always did.
    val gardenNameById = remember(gardenPlants) {
        if (gardenPlants.size > 1) knownGardensIncludingOwn(context).associate { it.gardenId to it.name } else emptyMap()
    }
    fun wateringStatusFor(p: PlantEntity) = computeWateringStatus(p, now, hemisphereByGarden[p.gardenId] ?: HemisphereState.value)

    if (type == "progress_photo") {
        val viewModel: PlantViewModel = viewModel(
            factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
        )
        var photos by remember { mutableStateOf<List<LocationPhotoEntity>>(emptyList()) }
        LaunchedEffect(Unit) {
            photos = AppDatabase.getInstance(context).locationPhotoDao().getAllOnceForGarden(effectiveGardenId(context))
        }
        // Progress-photo zones are still active-garden-only for now (matches the existing "add a
        // photo" flow, which only ever writes to the active garden) — not part of this fix.
        val activePlants by viewModel.plants.collectAsState()
        val dueZones = remember(activePlants, photos, now) { dueProgressPhotoZones(activePlants, photos, now) }

        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Spacer(Modifier.height(6.dp))
            Text("Zones due a progress photo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(14.dp))

            if (dueZones.isEmpty()) {
                Text("Nothing needs attention right now.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                dueZones.forEach { zone ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenZone(zone) }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(zone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("Add photo ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
        return
    }

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
            "feed" -> Triple(
                "Plants needing feeding",
                plants.filter { computeFeedStatus(it, now)?.nextDueMillis?.let { d -> d <= now } == true },
                { p: PlantEntity -> computeFeedStatus(p, now)?.label ?: "" }
            )
            "frost" -> Triple(
                "Frost risk — protect these plants",
                frostTenderOutdoorPlants(plants),
                { p: PlantEntity -> "Frost: ${p.frost}" }
            )
            else -> Triple(
                "Plants needing water",
                plants.filter { wateringStatusFor(it)?.nextDueMillis?.let { d -> d <= now } == true },
                { p: PlantEntity -> wateringStatusFor(p)?.label ?: "" }
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(14.dp))

        if (matchingPlants.isEmpty()) {
            Text("Nothing needs attention right now.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        } else {
            matchingPlants.forEach { plant ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val gardenSuffix = gardenNameById[plant.gardenId]?.let { " · $it" } ?: ""
                            Text(plant.name + gardenSuffix, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(plant.location.ifBlank { "No location" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(subtitleFor(plant), fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
