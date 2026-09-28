@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

import androidx.compose.material3.MaterialTheme

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.maps.android.compose.*
import java.text.DecimalFormat

// ============================================================================
// DASHBOARD SCREEN (with filter panel + tappable plant detail)
// ============================================================================

data class DashboardFilters(
    val location: String = "All",
    val source: String = "All",
    val plant: String = "All",
    val sun: String = "All",
    val soil: String = "All",
    val soilPh: String = "All",
    val category: String = "All",
    val water: String = "All",
    val frost: String = "All"
)
data class DashboardStatOption(val key: String, val label: String)

val dashboardStatCatalog = listOf(
    DashboardStatOption("total", "Total Plants"),
    DashboardStatOption("native", "Native"),
    DashboardStatOption("exotic", "Exotic"),
    DashboardStatOption("pollinator", "Pollinator-friendly"),
    DashboardStatOption("locations", "Unique Locations"),
    DashboardStatOption("species", "Unique Species"),
    DashboardStatOption("no_photo", "Plants without Photos"),
    DashboardStatOption("needs_water", "Needs Watering Now"),
    DashboardStatOption("manual_water", "Manual Watering Only"),
    DashboardStatOption("frost_hardy", "Frost Hardy"),
    DashboardStatOption("indoor", "Indoor Plants")
)

val defaultDashboardStatKeys = listOf("total", "native", "exotic", "pollinator")

fun getDashboardStatKeys(context: Context): List<String> {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString("dashboard_stat_keys", null) ?: return defaultDashboardStatKeys
    val keys = raw.split(",").filter { it.isNotBlank() }
    return keys.ifEmpty { defaultDashboardStatKeys }
}

fun setDashboardStatKeys(context: Context, keys: List<String>) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dashboard_stat_keys", keys.joinToString(",")).apply()
}

fun computeDashboardStatValue(key: String, plants: List<PlantEntity>): String {
    val now = System.currentTimeMillis()
    return when (key) {
        "total" -> plants.sumOf { it.qty }.toString()
        "native" -> plants.count { it.native.startsWith("Native") }.toString()
        "exotic" -> plants.count { it.native.startsWith("Exotic") }.toString()
        "pollinator" -> plants.count { it.pollinator.startsWith("Yes") }.toString()
        "locations" -> plants.map { it.location }.filter { it.isNotBlank() }.distinct().size.toString()
        "species" -> plants.map { it.sci }.filter { it.isNotBlank() }.distinct().size.toString()
        "no_photo" -> plants.count { it.photoUri == null }.toString()
        "needs_water" -> plants.count { p ->
            computeWateringStatus(p, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
        }.toString()
        "manual_water" -> plants.count { it.manualWateringOnly }.toString()
        "frost_hardy" -> plants.count { it.frost == "Hardy" }.toString()
        "indoor" -> plants.count { it.isIndoor }.toString()
        else -> "0"
    }
}

fun plantMatchesStatKey(key: String, plant: PlantEntity, now: Long): Boolean = when (key) {
    "total" -> true
    "native" -> plant.native.startsWith("Native")
    "exotic" -> plant.native.startsWith("Exotic")
    "pollinator" -> plant.pollinator.startsWith("Yes")
    "locations" -> plant.location.isNotBlank()
    "species" -> plant.sci.isNotBlank()
    "no_photo" -> plant.photoUri == null
    "needs_water" -> computeWateringStatus(plant, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
    "manual_water" -> plant.manualWateringOnly
    "frost_hardy" -> plant.frost == "Hardy"
    "indoor" -> plant.isIndoor
    else -> true
}

@Composable
fun StatCard(
    label: String, value: String, modifier: Modifier = Modifier,
    selected: Boolean = false, onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimaryContainer) else null
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================================
// DASHBOARD CHART SETTINGS
// ============================================================================

val dashboardChartGroupOptions = listOf(
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun Needs"),
    DashboardStatOption("water", "Water Needs"),
    DashboardStatOption("native", "Native/Exotic Status")
)

fun getDashboardChartEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("dashboard_chart_enabled", true)
}
fun setDashboardChartEnabled(context: Context, value: Boolean) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("dashboard_chart_enabled", value).apply()
}
fun getDashboardChartGroupBy(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dashboard_chart_group_by", "location") ?: "location"
}
fun setDashboardChartGroupBy(context: Context, value: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dashboard_chart_group_by", value).apply()
}

// ============================================================================
// LIST VIEW CUSTOMISATION
// ============================================================================

val listFieldCatalog = listOf(
    DashboardStatOption("sci", "Scientific name"),
    DashboardStatOption("native", "Native/Exotic"),
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun"),
    DashboardStatOption("water", "Water"),
    DashboardStatOption("soil", "Soil"),
    DashboardStatOption("soilPh", "Soil pH"),
    DashboardStatOption("frost", "Frost"),
    DashboardStatOption("pollinator", "Pollinator-friendly"),
    DashboardStatOption("source", "Source"),
    DashboardStatOption("wateringSystem", "Watering system"),
    DashboardStatOption("due", "Watering due")
)
val defaultListFieldKeys = listOf("sci", "native")

val listGroupOptions = listOf(
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun"),
    DashboardStatOption("water", "Water"),
    DashboardStatOption("none", "None")
)
val listSortOptions = listOf(
    DashboardStatOption("name", "Name"),
    DashboardStatOption("due", "Watering due")
)

fun getListFieldKeys(context: Context): List<String> {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString("list_field_keys", null) ?: return defaultListFieldKeys
    val keys = raw.split(",").filter { it.isNotBlank() }
    return keys.ifEmpty { defaultListFieldKeys }
}
fun setListFieldKeys(context: Context, keys: List<String>) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("list_field_keys", keys.joinToString(",")).apply()
}
fun getListGroupBy(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("list_group_by", "location") ?: "location"
}
fun setListGroupBy(context: Context, value: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("list_group_by", value).apply()
}
fun getListSortBy(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("list_sort_by", "name") ?: "name"
}
fun setListSortBy(context: Context, value: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("list_sort_by", value).apply()
}

fun listFieldValue(key: String, plant: PlantEntity): String? = when (key) {
    "sci" -> plant.sci.takeIf { it.isNotBlank() }
    "native" -> plant.native.takeIf { it.isNotBlank() }
    "location" -> plant.location.takeIf { it.isNotBlank() }
    "category" -> plant.category.takeIf { it.isNotBlank() }
    "sun" -> plant.sun.takeIf { it.isNotBlank() }?.let { "$it sun" }
    "water" -> plant.water.takeIf { it.isNotBlank() }?.let { "$it water" }
    "soil" -> plant.soil.takeIf { it.isNotBlank() }
    "soilPh" -> plant.soilPh.takeIf { it.isNotBlank() }
    "frost" -> plant.frost.takeIf { it.isNotBlank() }
    "pollinator" -> plant.pollinator.takeIf { it.isNotBlank() }
    "source" -> plant.source.takeIf { it.isNotBlank() }
    "wateringSystem" -> plant.wateringSystem.takeIf { it.isNotBlank() }
    "due" -> computeWateringStatus(plant)?.label
    else -> null
}

// ============================================================================
// APP SETTINGS (default landing tab)
// ============================================================================

val landingTabOptions = listOf(
    DashboardStatOption("home", "Home"),
    DashboardStatOption("map", "Map"),
    DashboardStatOption("list", "Plants"),
    DashboardStatOption("irrigation", "Water")
)

/** Older versions stored tabs that no longer exist (Report → Home, Audit → Plants, Help → Home). */
fun getDefaultLandingTab(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return when (val stored = prefs.getString("default_landing_tab", "home") ?: "home") {
        "dashboard", "help" -> "home"
        "audit" -> "list"
        else -> stored
    }
}
fun setDefaultLandingTab(context: Context, tab: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("default_landing_tab", tab).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: PlantViewModel, header: @Composable () -> Unit = {}) {
    val allPlants by viewModel.plants.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val context = LocalContext.current
    var showFilterDialog by remember { mutableStateOf(false) }
    var showCustomiseDialog by remember { mutableStateOf(false) }
    var selectedStatKey by remember { mutableStateOf<String?>("total") }
    var statKeys by remember { mutableStateOf(getDashboardStatKeys(context)) }
    var chartEnabled by remember { mutableStateOf(getDashboardChartEnabled(context)) }
    var chartGroupBy by remember { mutableStateOf(getDashboardChartGroupBy(context)) }
    var selectedPlant by remember { mutableStateOf<PlantEntity?>(null) }

    val locations = remember(allPlants) { listOf("All") + allPlants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted() }
    val sources = remember(allPlants) { listOf("All") + allPlants.map { it.source }.filter { it.isNotBlank() }.distinct().sorted() }
    val plantNames = remember(allPlants) { listOf("All") + allPlants.map { it.name }.filter { it.isNotBlank() }.distinct().sorted() }

    val filteredPlants by viewModel.filteredPlants.collectAsState()
    val percentage = if (allPlants.isNotEmpty()) {
        filteredPlants.size.toDouble() / allPlants.size * 100
    } else {
        0.0
    }
    val activeFilterCount = listOf(
        filters.location, filters.source, filters.plant, filters.sun, filters.soil, filters.soilPh, filters.category, filters.water, filters.frost
    ).count { it != "All" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        header()
        Text(
            stringResource(R.string.home_overview), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val df = DecimalFormat("0.##")
            Text(
                if (filteredPlants.size == allPlants.size) stringResource(R.string.home_overview_count_all, allPlants.size)
                else stringResource(R.string.home_overview_count_filtered, filteredPlants.size, allPlants.size, df.format(percentage)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showCustomiseDialog = true }) { Text(stringResource(R.string.action_customise)) }
            TextButton(onClick = { showFilterDialog = true }) {
                Text(if (activeFilterCount > 0) stringResource(R.string.action_filter_count, activeFilterCount) else stringResource(R.string.action_filter))
            }
        }
            if (filteredPlants.isEmpty()) {
                Text("No plants match these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            statKeys.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { key ->
                        val option = dashboardStatCatalog.firstOrNull { it.key == key }
                        StatCard(
                            option?.label ?: key,
                            computeDashboardStatValue(key, filteredPlants),
                            Modifier.weight(1f),
                            selected = selectedStatKey == key,
                            onClick = { selectedStatKey = if (selectedStatKey == key) null else key }
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))

// Moved above the chart so both the chart and the list react to the selected stat card
            val now = remember { System.currentTimeMillis() }
            val statFilteredPlants = remember(filteredPlants, selectedStatKey, now) {
                val key = selectedStatKey
                if (key == null) filteredPlants else filteredPlants.filter { plantMatchesStatKey(key, it, now) }
            }

            if (chartEnabled) {
                val chartLabel = dashboardChartGroupOptions.firstOrNull { it.key == chartGroupBy }?.label ?: "Location"
                val statLabel = dashboardStatCatalog.firstOrNull { it.key == selectedStatKey }?.label
                Text(
                    if (statLabel != null) "Plants by $chartLabel — $statLabel" else "Plants by $chartLabel",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                DashboardBarChart(plants = statFilteredPlants, groupBy = chartGroupBy)   // was: filteredPlants
                Spacer(Modifier.height(24.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val statLabel = dashboardStatCatalog.firstOrNull { it.key == selectedStatKey }?.label
                Text(
                    if (statLabel != null) "Plants — $statLabel" else "Plants",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f)
                )
                if (selectedStatKey != null) TextButton(onClick = { selectedStatKey = null }) { Text("Clear", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))

            statFilteredPlants.forEach { plant ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedPlant = plant }
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (plant.photoUri != null) {
                            PlantPhoto(
                                photoUri = plant.photoUri, photoThumbnailBase64 = plant.photoThumbnailBase64,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) { Text("🌿") }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                text = listOfNotNull(
                                    plant.location.takeIf { it.isNotBlank() } ?: "No location",
                                    plant.sun.takeIf { it.isNotBlank() }?.let { "$it sun" },
                                    plant.water.takeIf { it.isNotBlank() }?.let { "$it water" }
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
    }

    if (showFilterDialog) {
        var draft by remember { mutableStateOf(filters) }
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Filter plants") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SimpleFilterDropdown("Location", locations, draft.location) { draft = draft.copy(location = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Source", sources, draft.source) { draft = draft.copy(source = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Plant", plantNames, draft.plant) { draft = draft.copy(plant = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Sun", listOf("All") + sunOptions, draft.sun) { draft = draft.copy(sun = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Soil", listOf("All") + soilOptions, draft.soil) { draft = draft.copy(soil = it) }
                    Spacer(Modifier.height(10.dp))
                    if (FeatureVisibility.shouldShow(context, Feature.SOIL_PH)) {
                        SimpleFilterDropdown("Soil pH", listOf("All") + soilPhOptions, draft.soilPh) { draft = draft.copy(soilPh = it) }
                        Spacer(Modifier.height(10.dp))
                    }
                    SimpleFilterDropdown("Category", listOf("All") + categoryOptions, draft.category) { draft = draft.copy(category = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Water", listOf("All") + waterOptions, draft.water) { draft = draft.copy(water = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Frost", listOf("All") + frostOptions, draft.frost) { draft = draft.copy(frost = it) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setFilters(draft); showFilterDialog = false }) { Text("Apply") } },
            dismissButton = {
                TextButton(onClick = {
                    val cleared = DashboardFilters()
                    draft = cleared; viewModel.setFilters(cleared); showFilterDialog = false
                }) { Text("Clear filters") }
            }
        )
    }
    if (showCustomiseDialog) {
        val draftKeys = remember { mutableStateListOf(*statKeys.toTypedArray()) }
        var draftChartEnabled by remember { mutableStateOf(chartEnabled) }
        var draftChartGroupBy by remember { mutableStateOf(chartGroupBy) }
        AlertDialog(
            onDismissRequest = { showCustomiseDialog = false },
            title = { Text("Customise dashboard") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Figures shown (in order):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    draftKeys.forEachIndexed { index, key ->
                        val option = dashboardStatCatalog.firstOrNull { it.key == key }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(option?.label ?: key, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { if (index > 0) { draftKeys.removeAt(index); draftKeys.add(index - 1, key) } },
                                enabled = index > 0
                            ) { Text("↑", fontSize = 14.sp) }
                            IconButton(
                                onClick = { if (index < draftKeys.size - 1) { draftKeys.removeAt(index); draftKeys.add(index + 1, key) } },
                                enabled = index < draftKeys.size - 1
                            ) { Text("↓", fontSize = 14.sp) }
                            IconButton(onClick = { draftKeys.remove(key) }) { Text("✕", fontSize = 14.sp) }
                        }
                    }

                    val addableOptions = dashboardStatCatalog.filter { !draftKeys.contains(it.key) }
                    if (addableOptions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Add more:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        addableOptions.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { draftKeys.add(option.key) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text("+ ", fontWeight = FontWeight.Bold)
                                Text(option.label, fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Show chart", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = draftChartEnabled, onCheckedChange = { draftChartEnabled = it })
                    }
                    if (draftChartEnabled) {
                        Spacer(Modifier.height(8.dp))
                        DropdownField(
                            label = "Chart grouped by",
                            options = dashboardChartGroupOptions.map { it.label },
                            selected = dashboardChartGroupOptions.firstOrNull { it.key == draftChartGroupBy }?.label ?: "Location",
                            onSelect = { label ->
                                draftChartGroupBy = dashboardChartGroupOptions.firstOrNull { it.label == label }?.key ?: "location"
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalKeys = if (draftKeys.isEmpty()) defaultDashboardStatKeys else draftKeys.toList()
                    statKeys = finalKeys
                    setDashboardStatKeys(context, finalKeys)
                    chartEnabled = draftChartEnabled
                    setDashboardChartEnabled(context, draftChartEnabled)
                    chartGroupBy = draftChartGroupBy
                    setDashboardChartGroupBy(context, draftChartGroupBy)
                    showCustomiseDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCustomiseDialog = false }) { Text("Cancel") } }
        )
    }
    val plantToShow = selectedPlant
    if (plantToShow != null) {
        Dialog(onDismissRequest = { selectedPlant = null }) {
            Card {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (plantToShow.photoUri != null) {
                        PlantPhoto(
                            photoUri = plantToShow.photoUri, photoThumbnailBase64 = plantToShow.photoThumbnailBase64,
                            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(plantToShow.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (plantToShow.sci.isNotBlank()) {
                        Text(plantToShow.sci, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    Spacer(Modifier.height(16.dp))
                    DetailRow("Sun", plantToShow.sun)
                    DetailRow("Water", plantToShow.water)
                    DetailRow("Soil", plantToShow.soil)
                    if (FeatureVisibility.shouldShow(context, Feature.SOIL_PH)) DetailRow("Soil pH", plantToShow.soilPh)
                    DetailRow("Category", plantToShow.category)
                    DetailRow("Frost", plantToShow.frost)
                    DetailRow("Native/Exotic", plantToShow.native)
                    DetailRow("Pollinator-friendly", plantToShow.pollinator)
                    DetailRow("Location", plantToShow.location)
                    DetailRow("Source", plantToShow.source)
                    DetailRow("Watering System", plantToShow.wateringSystem)
                    DetailRow("Notes", plantToShow.notes)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { selectedPlant = null }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleFilterDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DashboardBarChart(plants: List<PlantEntity>, groupBy: String) {
    val keyFn: (PlantEntity) -> String = when (groupBy) {
        "sun" -> { p -> p.sun.ifBlank { "Unspecified" } }
        "water" -> { p -> p.water.ifBlank { "Unspecified" } }
        "native" -> { p -> p.native.ifBlank { "Unspecified" } }
        "category" -> { p -> p.category.ifBlank { "Unspecified" } }
        else -> { p -> p.location.ifBlank { "Unspecified" } }
    }
    val counts = remember(plants, groupBy) {
        plants.groupingBy(keyFn).eachCount().entries.sortedByDescending { it.value }.take(6)
    }
    if (counts.isEmpty()) {
        Text("No data to chart yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val maxCount = counts.maxOf { it.value }.coerceAtLeast(1)
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height },
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            counts.forEach { entry ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    // Reserve fixed px for the number+spacer above the bar, then size the bar
                    // from whatever's left — so the number never gets squeezed out.
                    val numberReservePx = with(density) { 18.dp.toPx() }
                    val availablePx = (trackHeightPx - numberReservePx).coerceAtLeast(0f)
                    val fraction = (entry.value.toFloat() / maxCount).coerceIn(0.03f, 1f)
                    val barHeightDp = with(density) { (availablePx * fraction).toDp() }.coerceAtLeast(3.dp)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.value.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(barHeightDp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { entry ->
                Text(
                    entry.key, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
