@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.sagegarden.car

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeState.accent = getSavedAccentColor(this)
        setContent {
            SageGardenCarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CarApp()
                }
            }
        }
    }
}

private sealed class Screen {
    data object Dashboard : Screen()
    data object Map : Screen()
    data class PlantDetail(val id: String, val returnTo: Screen) : Screen()
    data object Settings : Screen()
}

private data class DashboardStat(val key: String, val label: String)
private val statCatalog = listOf(
    DashboardStat("total", "Total Plants"),
    DashboardStat("native", "Native"),
    DashboardStat("exotic", "Exotic"),
    DashboardStat("pollinator", "Pollinator-friendly"),
    DashboardStat("needs_water", "Needs Watering"),
    DashboardStat("frost_hardy", "Frost Hardy")
)

private fun statValue(key: String, plants: List<Plant>, now: Long): String = when (key) {
    "total" -> plants.sumOf { it.qty }.toString()
    "native" -> plants.count { it.native.startsWith("Native") }.toString()
    "exotic" -> plants.count { it.native.startsWith("Exotic") }.toString()
    "pollinator" -> plants.count { it.pollinator.startsWith("Yes") }.toString()
    "needs_water" -> plants.count { it.wateringStatus(now)?.let { s -> s.overdue || s.dueToday } == true }.toString()
    "frost_hardy" -> plants.count { it.frost == "Hardy" }.toString()
    else -> "-"
}

private fun matchesStat(key: String, plant: Plant, now: Long): Boolean = when (key) {
    "native" -> plant.native.startsWith("Native")
    "exotic" -> plant.native.startsWith("Exotic")
    "pollinator" -> plant.pollinator.startsWith("Yes")
    "needs_water" -> plant.wateringStatus(now)?.let { it.overdue || it.dueToday } == true
    "frost_hardy" -> plant.frost == "Hardy"
    else -> true
}

/** Simple JSON cache of the last successful fetch, so the app has something to show immediately on
 * launch (before this session's own refresh completes) instead of a blank screen every time. */
private fun cacheFile(context: android.content.Context) = java.io.File(context.filesDir, "plants_cache.json")
private fun loadCachedPlants(context: android.content.Context): List<Plant> {
    val file = cacheFile(context)
    if (!file.exists()) return emptyList()
    return try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { jsonToPlant(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }
}
private fun saveCachedPlants(context: android.content.Context, plants: List<Plant>) {
    val arr = JSONArray()
    plants.forEach { p ->
        arr.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("sci", p.sci); put("location", p.location)
            put("category", p.category); put("sun", p.sun); put("water", p.water); put("soil", p.soil)
            put("frost", p.frost); put("native", p.native); put("pollinator", p.pollinator)
            put("notes", p.notes); put("qty", p.qty)
            put("photoUri", p.photoUri ?: JSONObject.NULL)
            put("photoThumbnail", p.photoThumbnailBase64 ?: JSONObject.NULL)
            put("lastWateredDate", p.lastWateredDate ?: JSONObject.NULL)
            put("wateringFrequencyDays", p.wateringFrequencyDays ?: JSONObject.NULL)
            put("lastFertilisedDate", p.lastFertilisedDate ?: JSONObject.NULL)
            put("fertiliseFrequencyDays", p.fertiliseFrequencyDays ?: JSONObject.NULL)
            put("lastPrunedDate", p.lastPrunedDate ?: JSONObject.NULL)
            put("pruneFrequencyDays", p.pruneFrequencyDays ?: JSONObject.NULL)
            put("lastFedDate", p.lastFedDate ?: JSONObject.NULL)
            put("feedFrequencyDays", p.feedFrequencyDays ?: JSONObject.NULL)
            put("lat", p.lat ?: JSONObject.NULL)
            put("lng", p.lng ?: JSONObject.NULL)
        })
    }
    cacheFile(context).writeText(arr.toString())
}

@Composable
private fun CarApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var installId by remember { mutableStateOf(getLinkedInstallId(context)) }
    var plants by remember { mutableStateOf(loadCachedPlants(context)) }
    var gardenLatLng by remember { mutableStateOf(getSavedGardenLatLng(context)) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedStatKey by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun refresh() {
        if (installId.isBlank()) return
        loading = true
        errorMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SyncClient.fetch(getOwnDeviceId(context), installId.trim(), getMemberToken(context))
            }
            loading = false
            when (result) {
                is SyncResult.Success -> {
                    result.memberToken?.let { setMemberToken(context, it) }
                    plants = result.plants.sortedBy { it.name.lowercase() }
                    saveCachedPlants(context, plants)
                    if (result.gardenLat != null && result.gardenLng != null) {
                        gardenLatLng = Pair(result.gardenLat, result.gardenLng)
                        setSavedGardenLatLng(context, result.gardenLat, result.gardenLng)
                    }
                }
                SyncResult.NotAuthorized -> {
                    // Removed from the garden, or the token is stale — forget it so re-linking starts fresh.
                    setMemberToken(context, null)
                    errorMessage = "Not authorised — check the Install ID, or ask the garden's owner if this display was removed."
                }
                SyncResult.NetworkError -> errorMessage = "Couldn't reach the server — check your connection."
                SyncResult.ServerError -> errorMessage = "Server error — try again shortly."
            }
        }
    }

    LaunchedEffect(Unit) { if (installId.isNotBlank() && plants.isEmpty()) refresh() }

    BackHandler(enabled = screen !is Screen.Dashboard) { screen = Screen.Dashboard }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("🌿 Sage Garden") },
                actions = {
                    IconButton(onClick = { screen = if (screen is Screen.Map) Screen.Dashboard else Screen.Map }) {
                        Text("🗺️", fontSize = 18.sp)
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading && installId.isNotBlank()) {
                        Text("🔄", fontSize = 18.sp)
                    }
                    IconButton(onClick = { screen = Screen.Settings }) {
                        Text("⚙️", fontSize = 18.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = screen) {
                is Screen.Dashboard -> DashboardScreen(
                    installId = installId,
                    plants = plants,
                    loading = loading,
                    errorMessage = errorMessage,
                    selectedStatKey = selectedStatKey,
                    onSelectStat = { selectedStatKey = if (selectedStatKey == it) null else it },
                    onPlantClick = { screen = Screen.PlantDetail(it.id, returnTo = Screen.Dashboard) },
                    onOpenSettings = { screen = Screen.Settings }
                )
                is Screen.Map -> MapScreen(
                    plants = plants,
                    gardenLat = gardenLatLng?.first,
                    gardenLng = gardenLatLng?.second,
                    onPlantClick = { screen = Screen.PlantDetail(it.id, returnTo = Screen.Map) }
                )
                is Screen.PlantDetail -> {
                    val plant = plants.firstOrNull { it.id == s.id }
                    if (plant != null) {
                        PlantDetailScreen(plant = plant, onBack = { screen = s.returnTo })
                    } else {
                        screen = Screen.Dashboard
                    }
                }
                is Screen.Settings -> SettingsScreen(
                    context = context,
                    installId = installId,
                    onInstallIdChange = {
                        installId = it
                        setLinkedInstallId(context, it)
                    },
                    onConnect = { screen = Screen.Dashboard; refresh() },
                    onBack = { screen = Screen.Dashboard }
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    installId: String,
    plants: List<Plant>,
    loading: Boolean,
    errorMessage: String?,
    selectedStatKey: String?,
    onSelectStat: (String) -> Unit,
    onPlantClick: (Plant) -> Unit,
    onOpenSettings: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }

    if (installId.isBlank()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Not connected yet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter your phone's Install ID in Settings to see your garden here.",
                textAlign = TextAlign.Center, color = Color.Gray, fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("Open Settings") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (loading && plants.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        if (errorMessage != null) {
            Text(
                errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (plants.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No plants synced yet.", color = Color.Gray)
            }
            return
        }

        val filtered = remember(plants, selectedStatKey, now) {
            if (selectedStatKey == null) plants else plants.filter { matchesStat(selectedStatKey, it, now) }
        }
        var chartGroupBy by remember { mutableStateOf("location") }
        val needsWateringNow = remember(plants, now) { plants.filter { matchesStat("needs_water", it, now) } }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                statCatalog.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { stat ->
                            StatCard(
                                label = stat.label,
                                value = statValue(stat.key, plants, now),
                                selected = selectedStatKey == stat.key,
                                onClick = { onSelectStat(stat.key) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(18.dp))
                val chartLabel = chartGroupOptions.firstOrNull { it.key == chartGroupBy }?.label ?: "Location"
                val statLabel = statCatalog.firstOrNull { it.key == selectedStatKey }?.label
                Text(
                    if (statLabel != null) "Plants by $chartLabel — $statLabel" else "Plants by $chartLabel",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    chartGroupOptions.forEach { option ->
                        FilterChip(
                            selected = chartGroupBy == option.key,
                            onClick = { chartGroupBy = option.key },
                            label = { Text(option.label, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PlantsByChart(filtered, chartGroupBy)

                Spacer(Modifier.height(22.dp))
                Text("Needs watering now", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (needsWateringNow.isEmpty()) {
                    Text("Nothing due right now.", fontSize = 13.sp, color = Color.Gray)
                } else {
                    needsWateringNow.forEach { plant ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onPlantClick(plant) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(plant.name.ifBlank { "(unnamed)" }, fontSize = 13.sp)
                                Text(
                                    plant.wateringStatus(now)?.label ?: "", fontSize = 12.sp,
                                    color = Color(0xFFE07A5F)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "${filtered.size} of ${plants.size} plants" + (selectedStatKey?.let { " — tap the card again to clear" } ?: ""),
                    fontSize = 12.sp, color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
            }
            items(filtered, key = { it.id }) { plant ->
                PlantRow(plant = plant, now = now, onClick = { onPlantClick(plant) })
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class ChartGroupOption(val key: String, val label: String)
private val chartGroupOptions = listOf(
    ChartGroupOption("location", "Location"),
    ChartGroupOption("category", "Category"),
    ChartGroupOption("sun", "Sun Needs"),
    ChartGroupOption("water", "Water Needs"),
    ChartGroupOption("native", "Native/Exotic")
)

/** Mirrors the desktop app's DashboardBarChart — same grouping keys/behaviour, just using the
 * live accent colour (ThemeState) for the bars instead of a fixed green, since this app's colour
 * is user-customisable. */
@Composable
private fun PlantsByChart(plants: List<Plant>, groupBy: String) {
    val keyFn: (Plant) -> String = when (groupBy) {
        "category" -> { p -> p.category.ifBlank { "Unspecified" } }
        "sun" -> { p -> p.sun.ifBlank { "Unspecified" } }
        "water" -> { p -> p.water.ifBlank { "Unspecified" } }
        "native" -> { p -> p.native.ifBlank { "Unspecified" } }
        else -> { p -> p.location.ifBlank { "Unspecified" } }
    }
    val counts = remember(plants, groupBy) {
        plants.groupingBy(keyFn).eachCount().entries.sortedByDescending { it.value }.take(6)
    }
    if (counts.isEmpty()) {
        Text("No data to chart yet.", fontSize = 12.sp, color = Color.Gray)
        return
    }
    val maxCount = counts.maxOf { it.value }.coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { entry ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val fraction = (entry.value.toFloat() / maxCount).coerceIn(0.05f, 1f)
                    val barHeightDp = (95.dp * fraction).coerceAtLeast(4.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.value.toString(), fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(barHeightDp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(barColor)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { entry ->
                Text(
                    entry.key, fontSize = 10.sp, color = Color.Gray, lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                value, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Text(
                label, fontSize = 12.sp,
                color = if (selected) Color.White.copy(alpha = 0.85f) else Color.Gray
            )
        }
    }
}

@Composable
private fun PlantThumb(plant: Plant, size: Int) {
    val shape = RoundedCornerShape(10.dp)
    val bitmap = remember(plant.photoThumbnailBase64) {
        plant.photoThumbnailBase64?.let {
            try {
                val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    val isHttpPhoto = plant.photoUri?.startsWith("http") == true
    Box(
        Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(bitmap, contentDescription = plant.name, modifier = Modifier.fillMaxSize())
            isHttpPhoto -> AsyncImage(model = plant.photoUri, contentDescription = plant.name, modifier = Modifier.fillMaxSize())
            else -> Text("🌿", fontSize = (size / 2.2).sp)
        }
    }
}

@Composable
private fun PlantRow(plant: Plant, now: Long, onClick: () -> Unit) {
    val status = plant.wateringStatus(now)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlantThumb(plant, size = 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(plant.name.ifBlank { "(unnamed)" }, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(plant.location.ifBlank { "No location" }, fontSize = 12.sp, color = Color.Gray)
        }
        if (status != null) {
            Text(
                status.label, fontSize = 11.sp,
                color = if (status.overdue) Color(0xFFE07A5F) else if (status.dueToday) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }
    }
}

@Composable
private fun PlantDetailScreen(plant: Plant, onBack: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Plant details", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            PlantThumb(plant, size = 180)
        }
        Spacer(Modifier.height(16.dp))
        Text(plant.name.ifBlank { "(unnamed)" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (plant.sci.isNotBlank()) Text(plant.sci, fontSize = 14.sp, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Spacer(Modifier.height(16.dp))

        DetailRow("Location", plant.location)
        DetailRow("Category", plant.category)
        DetailRow("Quantity", plant.qty.toString())
        DetailRow("Sun", plant.sun)
        DetailRow("Water need", plant.water)
        DetailRow("Soil", plant.soil)
        DetailRow("Frost tolerance", plant.frost)
        DetailRow("Native/Exotic", plant.native)
        DetailRow("Pollinator-friendly", plant.pollinator)

        Spacer(Modifier.height(16.dp))
        Text("Care", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        careStatus(plant.lastWateredDate, plant.wateringFrequencyDays, now)?.let { DetailRow("💧 Watering", it.label) }
        careStatus(plant.lastFertilisedDate, plant.fertiliseFrequencyDays, now)?.let { DetailRow("🌱 Fertilise", it.label) }
        careStatus(plant.lastPrunedDate, plant.pruneFrequencyDays, now)?.let { DetailRow("✂️ Prune", it.label) }
        careStatus(plant.lastFedDate, plant.feedFrequencyDays, now)?.let { DetailRow("🍽️ Feed", it.label) }

        if (plant.notes.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("Notes", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(plant.notes, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun SettingsScreen(
    context: android.content.Context,
    installId: String,
    onInstallIdChange: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit
) {
    var text by remember { mutableStateOf(installId) }
    var accent by remember { mutableStateOf(getSavedAccentColor(context)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        Text("Connected garden", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            "Paste the phone's Install ID — Help → Sync with other devices, on the phone.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Install ID") }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onInstallIdChange(text); onConnect() },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Connect & sync") }

        Spacer(Modifier.height(28.dp))
        Text("Accent colour", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            accentPresets.forEach { preset ->
                val selected = accent == preset.color
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 16.dp, bottom = 12.dp)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(preset.color)
                            .then(
                                if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier
                            )
                            .clickable {
                                accent = preset.color
                                setSavedAccentColor(context, preset.color)
                            }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(preset.label, fontSize = 10.sp, color = if (selected) Color.White else Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
