@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.material3.MaterialTheme

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.maps.android.compose.*

// ============================================================================
// LIST SCREEN
// ============================================================================
object ListScreenState {
    val searchState = mutableStateOf("")
    val collapsedGroups = mutableStateListOf<String>()
    val scrollIndex = mutableStateOf(0)
    val scrollOffset = mutableStateOf(0)
}

@Composable
fun ListScreen(
    viewModel: PlantViewModel, onPlantClick: (String) -> Unit, onAddPlant: () -> Unit,
    onChangeLocation: (String, Boolean) -> Unit, onOpenLocationPhotos: (String) -> Unit
) {
    val context = LocalContext.current
    val canEdit = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }
    val growthViewModel: GrowthPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val plantIdsWithPhotos by growthViewModel.plantIdsWithPhotos.collectAsState()
    val extraPhotoViewModel: ExtraPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val plantIdsWithExtraPhotos by extraPhotoViewModel.plantIdsWithPhotos.collectAsState()
    val plants by viewModel.filteredPlants.collectAsState()
    var search by ListScreenState.searchState
    var locationChangePlantId by remember { mutableStateOf<String?>(null) }
    var showListFieldsDialog by remember { mutableStateOf(false) }
    var showProgressPhotosPicker by remember { mutableStateOf(false) }
    var groupBy by remember { mutableStateOf(getListGroupBy(context)) }
    var sortBy by remember { mutableStateOf(getListSortBy(context)) }
    var fieldKeys by remember { mutableStateOf(getListFieldKeys(context)) }
    val hasCustomMap = remember { GardenSettings.active(context).customMapUri != null }
    val now = remember { System.currentTimeMillis() }
    val collapsedGroups = ListScreenState.collapsedGroups

    // Every text field except notes (free-form, easy to search separately if ever needed), numeric
    // fields, date fields, and latitude/longitude — those aren't meaningful to match against typed
    // search text.
    val filtered = plants.filter {
        search.isBlank() ||
                it.name.contains(search, ignoreCase = true) ||
                it.sci.contains(search, ignoreCase = true) ||
                it.location.contains(search, ignoreCase = true) ||
                it.id.contains(search, ignoreCase = true) ||
                it.sun.contains(search, ignoreCase = true) ||
                it.water.contains(search, ignoreCase = true) ||
                it.soil.contains(search, ignoreCase = true) ||
                it.soilPh.contains(search, ignoreCase = true) ||
                it.category.contains(search, ignoreCase = true) ||
                it.frost.contains(search, ignoreCase = true) ||
                it.native.contains(search, ignoreCase = true) ||
                it.pollinator.contains(search, ignoreCase = true) ||
                it.source.contains(search, ignoreCase = true) ||
                it.wateringSystem.contains(search, ignoreCase = true)
    }

    fun groupKey(p: PlantEntity): String = when (groupBy) {
        "sun" -> p.sun.ifBlank { "Unspecified sun" }
        "water" -> p.water.ifBlank { "Unspecified water" }
        "category" -> p.category.ifBlank { "Unspecified category" }
        "none" -> ""
        else -> p.location.ifBlank { "Unspecified location" }
    }

    fun sortedWithin(list: List<PlantEntity>): List<PlantEntity> = when (sortBy) {
        "due" -> list.sortedWith(
            compareBy(
                { p -> computeWateringStatus(p, now)?.nextDueMillis ?: Long.MAX_VALUE },
                { p -> p.name.lowercase() }
            )
        )
        else -> list.sortedBy { it.name.lowercase() }
    }

    val grouped: Map<String, List<PlantEntity>> = if (groupBy == "none") {
        mapOf("" to filtered)
    } else {
        filtered.groupBy { groupKey(it) }
            .toSortedMap(compareBy { if (it.startsWith("Unspecified")) "\uFFFF" else it.lowercase() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                label = { Text("Search plant data") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    DropdownField(
                        label = "Group by", options = listGroupOptions.map { it.label },
                        selected = listGroupOptions.firstOrNull { it.key == groupBy }?.label ?: "Location",
                        onSelect = { label ->
                            val key = listGroupOptions.firstOrNull { it.label == label }?.key ?: "location"
                            groupBy = key; setListGroupBy(context, key)
                        }
                    )
                }
                Box(Modifier.weight(1f)) {
                    DropdownField(
                        label = "Sort by", options = listSortOptions.map { it.label },
                        selected = listSortOptions.firstOrNull { it.key == sortBy }?.label ?: "Name",
                        onSelect = { label ->
                            val key = listSortOptions.firstOrNull { it.label == label }?.key ?: "name"
                            sortBy = key; setListSortBy(context, key)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow {
                TextButton(onClick = { showListFieldsDialog = true }) { Text("Customise fields shown", fontSize = 12.sp) }
                if (FeatureVisibility.shouldShow(context, Feature.PROGRESS_PHOTOS)) {
                    TextButton(onClick = { showProgressPhotosPicker = true }) { Text("📷 Progress photos", fontSize = 12.sp) }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No plants match — tap + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = ListScreenState.scrollIndex.value,
                    initialFirstVisibleItemScrollOffset = ListScreenState.scrollOffset.value
                )
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            ListScreenState.scrollIndex.value = index
                            ListScreenState.scrollOffset.value = offset
                        }
                }
                LazyColumn(state = listState) {
                    grouped.forEach { (label, plantsInGroup) ->
                        if (label.isNotBlank()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            if (collapsedGroups.contains(label)) collapsedGroups.remove(label)
                                            else collapsedGroups.add(label)
                                        }
                                        .padding(top = 12.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        if (collapsedGroups.contains(label)) "▸ ${plantsInGroup.size}" else "▾",
                                        color = MaterialTheme.colorScheme.primary, fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        if (!collapsedGroups.contains(label)) {
                            items(sortedWithin(plantsInGroup)) { plant ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPlantClick(plant.id) }
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (plant.photoUri != null) {
                                        PlantPhoto(
                                            photoUri = plant.photoUri, photoThumbnailBase64 = plant.photoThumbnailBase64,
                                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) { Text("🌿") }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(plant.name, fontWeight = FontWeight.SemiBold)
                                            if (plantIdsWithPhotos.contains(plant.id)) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("📸", fontSize = 12.sp)
                                            }
                                            if (plantIdsWithExtraPhotos.contains(plant.id)) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("📷", fontSize = 12.sp)
                                            }
                                        }
                                        val subtitle = fieldKeys.mapNotNull { listFieldValue(it, plant) }.joinToString(" · ")
                                        if (subtitle.isNotBlank()) {
                                            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (canEdit) {
                                        IconButton(onClick = { locationChangePlantId = plant.id }) { Text("📍") }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddPlant,
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
        ) {
            Text("+", fontSize = 28.sp)
        }

        if (locationChangePlantId != null) {
            val id = locationChangePlantId!!
            AlertDialog(
                onDismissRequest = { locationChangePlantId = null },
                title = { Text("Change plant location") },
                text = {
                    Column {
                        Text(
                            "Pick where to place this plant. You'll be taken to the map — tap the new spot.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { locationChangePlantId = null; onChangeLocation(id, false) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Real map")
                        }
                        if (hasCustomMap) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { locationChangePlantId = null; onChangeLocation(id, true) }, modifier = Modifier.fillMaxWidth()) {
                                Text("My drawing")
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { locationChangePlantId = null }) { Text("Cancel") } }
            )
        }

        if (showListFieldsDialog) {
            val draftFields = remember { mutableStateListOf(*fieldKeys.toTypedArray()) }
            AlertDialog(
                onDismissRequest = { showListFieldsDialog = false },
                title = { Text("Customise fields shown") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Choose which details appear under each plant's name:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        listFieldCatalog.forEach { option ->
                            val checked = draftFields.contains(option.key)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { if (checked) draftFields.remove(option.key) else draftFields.add(option.key) }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (checked) draftFields.remove(option.key) else draftFields.add(option.key)
                                })
                                Text(option.label, fontSize = 13.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val finalFields = if (draftFields.isEmpty()) defaultListFieldKeys else draftFields.toList()
                        fieldKeys = finalFields
                        setListFieldKeys(context, finalFields)
                        showListFieldsDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showListFieldsDialog = false }) { Text("Cancel") } }
            )
        }

        if (showProgressPhotosPicker) {
            val locationPhotoViewModel: LocationPhotoViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            )
            val photoCounts by locationPhotoViewModel.locationsWithPhotoCounts.collectAsState()
            val allLocations = remember(plants) { plants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted() }
            AlertDialog(
                onDismissRequest = { showProgressPhotosPicker = false },
                title = { Text("Progress photos") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (allLocations.isEmpty()) {
                            Text("Add a location to a plant first to track progress photos for it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Tap a zone below to view or add photos of that area.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            allLocations.forEach { location ->
                                val count = photoCounts[location] ?: 0
                                OutlinedButton(
                                    onClick = { showProgressPhotosPicker = false; onOpenLocationPhotos(location) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    Text("📷", fontSize = 14.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(location, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                                    Text(
                                        if (count == 1) "1 photo" else "$count photos",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showProgressPhotosPicker = false }) { Text("Close") } }
            )
        }
    }
}
