package com.example.sagegarden

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sagegarden.ui.theme.appColors
import java.util.Calendar

/** One thing to do today: a care [type] ("watering", "fertilise", "prune", "feed") for [plant]. */
private data class CareTask(val plant: PlantEntity, val type: String, val status: WateringStatus)

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** End of today (local time) — anything due before this counts as "today". */
private fun endOfToday(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

/**
 * Home: what needs doing today in the garden on screen, first — the thing most people open a
 * garden app for — with the garden overview (the old Report tab) underneath.
 */
@Composable
fun HomeScreen(
    viewModel: PlantViewModel,
    onAddPlant: () -> Unit,
    onOpenPlant: (String) -> Unit,
    onOpenZonePhotos: (String) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit,
) {
    val plants by viewModel.plants.collectAsState()
    DashboardScreen(viewModel = viewModel, header = {
        TodaySection(plants, onAddPlant, onOpenPlant, onOpenZonePhotos, onOpenSettings)
    })
}

@Composable
private fun TodaySection(
    plants: List<PlantEntity>,
    onAddPlant: () -> Unit,
    onOpenPlant: (String) -> Unit,
    onOpenZonePhotos: (String) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val careLogViewModel: CareLogViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val canEdit = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }
    // Re-evaluated whenever plants change (e.g. after logging a task), so "now" stays current.
    val now = remember(plants) { System.currentTimeMillis() }
    val dayEnd = endOfToday(now)
    val hemisphere = HemisphereState.value

    if (plants.isEmpty()) {
        WelcomeCard(onAddPlant = onAddPlant, hasAddress = GardenAddressState.latLng != null, onSetAddress = { onOpenSettings(SettingsPage.GARDEN) })
        return
    }

    val showFertilisePrune = FeatureVisibility.shouldShow(context, Feature.FERTILISE_PRUNE)
    val showFeed = FeatureVisibility.shouldShow(context, Feature.FEEDING)
    val tasks = remember(plants, now, hemisphere, showFertilisePrune, showFeed) {
        buildList {
            plants.forEach { p ->
                computeWateringStatus(p, now, hemisphere)?.takeIf { (it.nextDueMillis ?: now) <= dayEnd }?.let { add(CareTask(p, "watering", it)) }
                if (showFertilisePrune) {
                    computeFertiliseStatus(p, now)?.takeIf { (it.nextDueMillis ?: now) <= dayEnd }?.let { add(CareTask(p, "fertilise", it)) }
                    computePruneStatus(p, now)?.takeIf { (it.nextDueMillis ?: now) <= dayEnd }?.let { add(CareTask(p, "prune", it)) }
                }
                if (showFeed) computeFeedStatus(p, now)?.takeIf { (it.nextDueMillis ?: now) <= dayEnd }?.let { add(CareTask(p, "feed", it)) }
            }
        }.sortedWith(compareBy({ it.status.nextDueMillis ?: 0L }, { it.plant.name }))
    }

    var photoZones by remember { mutableStateOf<List<String>>(emptyList()) }
    val showPhotoZones = FeatureVisibility.shouldShow(context, Feature.PROGRESS_PHOTOS) && GardenSettings.active(context).progressPhotoRemindersEnabled
    LaunchedEffect(plants, showPhotoZones) {
        photoZones = if (!showPhotoZones) emptyList() else
            dueProgressPhotoZones(plants, AppDatabase.getInstance(context).locationPhotoDao().getAllOnceForGarden(effectiveGardenId(context)), now)
    }

    Text(stringResource(R.string.home_today), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))

    if (tasks.isEmpty() && photoZones.isEmpty()) {
        AllCaughtUpCard(plants, now, hemisphere)
    } else {
        // Watering first, grouped by zone so a whole bed can be logged at once; other care after.
        val watering = tasks.filter { it.type == "watering" }
        watering.groupBy { it.plant.location }.toSortedMap(compareBy { it.ifBlank { "￿" } }).forEach { (zone, zoneTasks) ->
            TaskGroupCard(
                title = zone.ifBlank { stringResource(R.string.home_no_zone) },
                icon = Icons.Outlined.WaterDrop,
                tasks = zoneTasks, canEdit = canEdit, onOpenPlant = onOpenPlant,
                onDone = { careLogViewModel.logCare(it.plant.id, it.plant.gardenId, it.type, System.currentTimeMillis()) },
                onDoneAll = if (zoneTasks.size > 1) ({ zoneTasks.forEach { careLogViewModel.logCare(it.plant.id, it.plant.gardenId, it.type, System.currentTimeMillis()) } }) else null
            )
        }
        val otherCare = tasks.filter { it.type != "watering" }
        if (otherCare.isNotEmpty()) {
            TaskGroupCard(
                title = stringResource(R.string.home_other_care), icon = Icons.Outlined.Spa,
                tasks = otherCare, canEdit = canEdit, onOpenPlant = onOpenPlant,
                onDone = { careLogViewModel.logCare(it.plant.id, it.plant.gardenId, it.type, System.currentTimeMillis()) },
                onDoneAll = null
            )
        }
        if (photoZones.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.home_progress_photos), style = MaterialTheme.typography.titleSmall)
                    }
                    photoZones.forEach { zone ->
                        ListItem(
                            headlineContent = { Text(zone) },
                            trailingContent = { Text(stringResource(R.string.action_add_photo), color = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.clickable { onOpenZonePhotos(zone) }
                        )
                    }
                }
            }
        }
    }
    OtherGardensDue()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun TaskGroupCard(
    title: String,
    icon: ImageVector,
    tasks: List<CareTask>,
    canEdit: Boolean,
    onOpenPlant: (String) -> Unit,
    onDone: (CareTask) -> Unit,
    onDoneAll: (() -> Unit)?,
) {
    val colors = MaterialTheme.appColors
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                if (canEdit && onDoneAll != null) TextButton(onClick = onDoneAll) { Text(stringResource(R.string.home_water_all)) }
            }
            tasks.forEach { task ->
                val overdue = (task.status.nextDueMillis ?: 0L) < startOfToday()
                ListItem(
                    headlineContent = { Text(task.plant.name) },
                    supportingContent = {
                        Text(
                            careTaskLabel(task.type) + " · " + task.status.label,
                            color = if (overdue) colors.statusUrgent else colors.statusSoon
                        )
                    },
                    trailingContent = {
                        if (canEdit) FilledTonalButton(onClick = { onDone(task) }) { Text(stringResource(R.string.action_done)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.clickable { onOpenPlant(task.plant.id) }
                )
            }
        }
    }
}

@Composable
private fun careTaskLabel(type: String): String = stringResource(
    when (type) {
        "fertilise" -> R.string.care_fertilise
        "prune" -> R.string.care_prune
        "feed" -> R.string.care_feed
        else -> R.string.care_water
    }
)

@Composable
private fun AllCaughtUpCard(plants: List<PlantEntity>, now: Long, hemisphere: Hemisphere) {
    val next = remember(plants, now) {
        plants.mapNotNull { p -> computeWateringStatus(p, now, hemisphere)?.nextDueMillis?.let { p to it } }.minByOrNull { it.second }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.home_all_caught_up), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (next != null) {
                    val days = ((next.second - now) / 86_400_000L).coerceAtLeast(1).toInt()
                    Text(
                        pluralStringResource(R.plurals.home_next_watering, days, next.first.name, days),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeCard(onAddPlant: () -> Unit, hasAddress: Boolean, onSetAddress: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Icon(Icons.Outlined.Yard, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.home_welcome_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddPlant) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_add_first_plant))
            }
            if (!hasAddress) {
                TextButton(onClick = onSetAddress) { Text(stringResource(R.string.home_set_address)) }
            }
        }
    }
}

/**
 * A one-line nudge when another garden you belong to has watering due — reminders already cover
 * every garden, so without this the Home screen would look "all caught up" while a notification said
 * otherwise. Tapping switches to that garden.
 */
@Composable
private fun OtherGardensDue() {
    val context = LocalContext.current
    var dueByGarden by remember { mutableStateOf<List<Pair<KnownGarden, Int>>>(emptyList()) }
    LaunchedEffect(ActiveGardenState.activeGardenId) {
        val activeId = effectiveGardenId(context)
        val dao = AppDatabase.getInstance(context).plantDao()
        val now = System.currentTimeMillis()
        val dayEnd = endOfToday(now)
        dueByGarden = knownGardensIncludingOwn(context).filter { it.gardenId != activeId }.mapNotNull { garden ->
            val hemisphere = GardenSettings.of(context, garden.gardenId).hemisphere
            val count = dao.getAllOnceForGarden(garden.gardenId).count { p ->
                computeWateringStatus(p, now, hemisphere)?.let { (it.nextDueMillis ?: now) <= dayEnd } == true
            }
            if (count > 0) garden to count else null
        }
    }
    dueByGarden.forEach { (garden, count) ->
        AssistChip(
            onClick = {
                val installId = getOrCreateInstallId(context)
                GardenMembershipStore.setActiveGardenId(context, if (garden.gardenId == installId) null else garden.gardenId)
            },
            label = { Text(pluralStringResource(R.plurals.home_other_garden_due, count, garden.name, count)) },
            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}
