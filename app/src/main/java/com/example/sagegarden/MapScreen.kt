@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.app.Application
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import org.json.JSONObject

// ============================================================================
// MAP SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: PlantViewModel,
    onMapTap: (Double, Double) -> Unit,
    onMarkerClick: (String) -> Unit,
    placementModeForPlantId: String? = null,
    onPlacementSaved: (() -> Unit)? = null,
    onNavigateToHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    val plants by viewModel.filteredPlants.collectAsState()
    // Categories are opt-in (most gardens never set one), so this quick filter — separate from the
    // Dashboard's full filter dialog, which isn't reachable from the map — only shows once at least
    // one plant actually has a category, and only offers the categories actually in use.
    val allPlantsUnfiltered by viewModel.plants.collectAsState()
    val usedCategories = remember(allPlantsUnfiltered) {
        categoryOptions.filter { cat -> allPlantsUnfiltered.any { it.category == cat } }
    }
    var mapCategoryFilter by remember { mutableStateOf("All") }
    val categoryFilteredPlants = remember(plants, mapCategoryFilter) {
        if (mapCategoryFilter == "All") plants else plants.filter { it.category == mapCategoryFilter }
    }
    val gardenLatLng = remember { getGardenLatLng(context) }
    val savedCamera = remember { getMapCameraPosition(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = when {
            savedCamera != null -> CameraPosition.fromLatLngZoom(LatLng(savedCamera.first, savedCamera.second), savedCamera.third)
            gardenLatLng != null -> CameraPosition.fromLatLngZoom(LatLng(gardenLatLng.first, gardenLatLng.second), 18f)
            else -> CameraPosition.fromLatLngZoom(LatLng(MAP_FALLBACK_LAT, MAP_FALLBACK_LNG), 18f)
        }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Plant markers are plain Compose overlays positioned via the map's live projection, not native
    // GoogleMap Marker/MarkerComposable objects — see the overlay loop below for why: Google's own
    // marker tap region turned out to have some SDK-enforced minimum well beyond the marker's actual
    // rendered size (confirmed by testing — even a 22dp icon at max zoom was triggering from taps
    // clearly outside it), which no icon-size change can shrink. A plain Compose Box's clickable
    // bounds are exactly its own size, giving pixel-precise control instead.
    val realMapMarkerDiameter = 22.dp
    val realMapMarkerRadiusPx = with(density) { (realMapMarkerDiameter / 2).roundToPx() }
    // Persists the camera's resting position whenever the user finishes panning/zooming, so the
    // map opens back to where they left it instead of resetting to the garden address every time.
    // isMoving starts false at composition, so this LaunchedEffect's very first firing is an
    // artifact of initial setup, not a real user pan/zoom — persisting it would lock in whatever
    // fallback position (garden address, or the hardcoded default) the camera happened to start at,
    // permanently shadowing the real garden address once it becomes known and blocking the
    // auto-fit-to-markers effect below (which only runs when no camera has been saved yet) from ever
    // running again. Skipping exactly this first firing lets a real garden address or the auto-fit's
    // own camera move persist normally afterward, while still remembering genuine user pans.
    var hasSettledOnce by remember(ActiveGardenState.activeGardenId) { mutableStateOf(false) }
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            if (hasSettledOnce) {
                val pos = cameraPositionState.position
                setMapCameraPosition(context, pos.target.latitude, pos.target.longitude, pos.zoom)
            } else {
                hasSettledOnce = true
            }
        }
    }
    // A garden viewed for the first time on this device (freshly created, or just joined) has no
    // saved camera position or garden address of its own yet — without this, the map fell back to
    // a hardcoded NYC default, making a shared garden's plants look like they "didn't come across"
    // when they were actually just off-screen on the other side of the world. Once this garden's
    // actual plants load, fit the camera to them instead, but only once and only when there was
    // nothing more specific to go on.
    var hasAutoFitted by remember(ActiveGardenState.activeGardenId) { mutableStateOf(false) }
    LaunchedEffect(plants, hasAutoFitted) {
        if (!hasAutoFitted && savedCamera == null && gardenLatLng == null && plants.isNotEmpty()) {
            val coords = plants.mapNotNull { p -> if (p.lat != null && p.lng != null) LatLng(p.lat, p.lng) else null }
            if (coords.isNotEmpty()) {
                hasAutoFitted = true
                if (coords.size == 1) {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(coords.first(), 18f))
                } else {
                    val bounds = LatLngBounds.Builder().apply { coords.forEach { include(it) } }.build()
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                }
            }
        }
    }
    var tooltipPlant by remember { mutableStateOf<PlantEntity?>(null) }

    // Only the true "nothing to show a map from" case should get the placeholder instead of the
    // real map — a garden with no address but at least one plant that already has coordinates
    // (e.g. a view-only member before the owner's address has synced down) still has something to
    // auto-fit the camera to, via the LaunchedEffect above. This used to just check gardenLatLng,
    // which crashed: that LaunchedEffect runs unconditionally (it's declared earlier in this
    // function, so a later `return` doesn't stop it from having already fired) and calls
    // CameraUpdateFactory, which is only initialized once a real GoogleMap has been created — but
    // the old condition skipped rendering GoogleMap entirely whenever gardenLatLng was null,
    // regardless of whether there were plant coordinates to fit to, so CameraUpdateFactory was
    // never initialized and the call threw "NullPointerException: CameraUpdateFactory is not
    // initialized". Broadening this condition to also render the map (letting the SDK initialize)
    // whenever plant coordinates exist fixes the crash and restores the auto-fit's actual purpose.
    val hasPlantCoordinates = plants.any { it.lat != null && it.lng != null }
    if (gardenLatLng == null && placementModeForPlantId == null && !hasPlantCoordinates) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺️", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Set your garden's address to see it here",
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Once set, the map centres on your garden instead of a generic location.",
                    fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { PendingHelpFocusState.focusWeatherSection = true; onNavigateToHelp() }) {
                    Text("Set garden address in Help")
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.HYBRID),
            onMapClick = { latLng ->
                // A view-only member can still pan/zoom freely (that's just camera state, not a
                // GoogleMap click callback) — only tapping to add/move a plant is blocked here.
                // Placement mode itself is already unreachable without edit access (its only entry
                // point, FormScreen's "Place on real-world map" button, is hidden for read-only), so
                // this check mainly guards the plain "tap empty space to add a new plant" path.
                if (hasWriteAccessToActiveGarden(context)) {
                    // Plant markers are no longer native GoogleMap Marker/MarkerComposable objects
                    // (see the overlay loop below), so a tap only ever reaches here if it missed every
                    // marker's own small Compose-owned hit box — always place/move a plant, never a
                    // marker-proximity check.
                    if (placementModeForPlantId != null) {
                        scope.launch {
                            val plant = viewModel.getById(placementModeForPlantId)
                            if (plant != null) {
                                viewModel.save(plant.copy(lat = latLng.latitude, lng = latLng.longitude))
                            }
                            onPlacementSaved?.invoke()
                        }
                    } else {
                        onMapTap(latLng.latitude, latLng.longitude)
                    }
                }
            }
        )

        // Custom marker overlay — plain Compose elements positioned via the map's live projection,
        // each clickable only within its own exact realMapMarkerDiameter bounds. Recomposes with
        // cameraPositionState.position (read inside this loop), so markers track pan/zoom/rotate.
        // Sitting outside GoogleMap's own content means these are the ONLY clickable area anywhere
        // near a plant — everywhere else (including right next to a marker) falls through untouched
        // to the native map underneath, reaching onMapClick above like normal empty-space taps.
        categoryFilteredPlants.forEach { plant ->
            key(plant.id) {
                if (plant.lat != null && plant.lng != null) {
                    @Suppress("UNUSED_VARIABLE")
                    val recomposeOnCameraMove = cameraPositionState.position
                    val point = cameraPositionState.projection?.toScreenLocation(LatLng(plant.lat, plant.lng))
                    if (point != null) {
                        val hasCategory = plant.category.isNotBlank() && plant.category != "Other"
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(point.x - realMapMarkerRadiusPx, point.y - realMapMarkerRadiusPx) }
                                .size(realMapMarkerDiameter)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (hasCategory) Color.White
                                    else if (plant.native.startsWith("Native")) Color(0xFF3A5A40)
                                    else Color(0xFFFF7A45)
                                )
                                .border(1.5.dp, Color(0xFF3A5A40), RoundedCornerShape(50))
                                .pointerInput(plant.id) { detectTapGestures { tooltipPlant = plant } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasCategory) {
                                Text(categoryMarkerEmoji(plant.category), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        if (placementModeForPlantId != null) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Color(0xFF3A5A40), RoundedCornerShape(8.dp)).padding(12.dp)
            ) {
                Text("Tap anywhere to place this plant", color = Color.White, fontSize = 13.sp)
            }
        }

        // Category filter dropdown (address search removed — the garden address is now set once
        // in Help, so a per-screen search bar here was redundant).
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            if (usedCategories.isNotEmpty()) {
                val categoryLabels = remember(usedCategories) {
                    listOf("All" to "All") + usedCategories.map { cat -> cat to "${categoryMarkerEmoji(cat)} $cat" }
                }
                val selectedCategoryLabel = categoryLabels.firstOrNull { it.first == mapCategoryFilter }?.second ?: "All"
                var categoryMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { categoryMenuExpanded = true },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.92f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(selectedCategoryLabel, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (categoryMenuExpanded) "▾" else "▸", fontSize = 12.sp, color = Color.Gray)
                    }
                    DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                        categoryLabels.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = { mapCategoryFilter = key; categoryMenuExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // "+" add-plant button, bottom-center (was bottom-end, overlapped zoom controls)
        FloatingActionButton(
            onClick = {
                val c = cameraPositionState.position.target
                onMapTap(c.latitude, c.longitude)
            },
            containerColor = Color(0xFFFF7A45),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
        ) {
            Text("+", fontSize = 28.sp)
        }

        // Tooltip sits above the map layer and consumes taps so they don't fall
        // through to the GoogleMap AndroidView underneath (which would otherwise
        // register them as "add a plant here").
        tooltipPlant?.let { plant ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { /* consume — don't let it reach the map */ } }
            ) {
                PlantTooltipCard(plant = plant, onEdit = { onMarkerClick(plant.id) }, onDismiss = { tooltipPlant = null })
            }
        }
    }
}

@Composable
fun MapTabScreen(
    viewModel: PlantViewModel,
    onMarkerClick: (String) -> Unit,
    onAddPlantAtLatLng: (Double, Double) -> Unit = { _, _ -> },
    onAddPlantAtFraction: (Double, Double) -> Unit = { _, _ -> },
    placementModeForPlantId: String? = null,
    onPlacementSaved: (() -> Unit)? = null,
    startOnCustom: Boolean = false,
    onOpenSunMap: () -> Unit = {},
    onNavigateToHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    val pathViewModel: IrrigationPathViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as Application
        )
    )
    // The custom map drawing and sun map are per-device local data that never syncs to a shared
    // garden — showing them while viewing a garden you don't own would just surface YOUR OWN
    // unrelated drawing/zones, not the owner's, so they're hidden entirely for a non-owner.
    val canManageMap = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) } && FeatureVisibility.shouldShow(context, Feature.CUSTOM_MAP)
    val hasCustomMap = canManageMap && remember(ActiveGardenState.activeGardenId) { getCustomMapUri(context) != null }
    var showingCustom by remember { mutableStateOf(startOnCustom && hasCustomMap) }

    Column(Modifier.fillMaxSize()) {
        // Placement mode has no other way out — tapping the map either places the plant (which
        // itself navigates away via onPlacementSaved) or, for a view-only viewer, does nothing at
        // all (see MapScreen's onMapClick gate), leaving them stuck on this screen with no escape.
        if (placementModeForPlantId != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).zIndex(2f).padding(10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { onPlacementSaved?.invoke() }) { Text("‹ Cancel") }
            }
        }
        if (hasCustomMap || (canManageMap && FeatureVisibility.shouldShow(context, Feature.SUN_MAP))) {
            // FlowRow (not a plain Row) so that at large accessibility font sizes, a button that no
            // longer fits wraps onto a new line instead of overflowing/squeezing unpredictably —
            // none of these buttons had a weight() or width constraint before, so at max text size
            // this row could blow out into unusable layouts.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(2f)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasCustomMap) {
                    Button(
                        onClick = { showingCustom = false },
                        contentPadding = CompactButtonPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!showingCustom) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                            contentColor = if (!showingCustom) Color.White else Color.Black
                        )
                    ) { Text("Real Map", fontSize = 12.sp) }
                    Button(
                        onClick = { showingCustom = true },
                        contentPadding = CompactButtonPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showingCustom) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                            contentColor = if (showingCustom) Color.White else Color.Black
                        )
                    ) { Text("My Drawing", fontSize = 12.sp) }
                }
                if (canManageMap && FeatureVisibility.shouldShow(context, Feature.SUN_MAP)) {
                    OutlinedButton(onClick = onOpenSunMap, contentPadding = CompactButtonPadding) { Text("☀️ Sun map", fontSize = 12.sp) }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (showingCustom && hasCustomMap) {
                CustomMapScreen(
                    viewModel = viewModel, pathViewModel = pathViewModel, onMarkerClick = onMarkerClick,
                    placementModeForPlantId = placementModeForPlantId, onPlacementSaved = onPlacementSaved,
                    onAddPlantAt = onAddPlantAtFraction
                )
            } else {
                MapScreen(
                    viewModel = viewModel, onMapTap = onAddPlantAtLatLng, onMarkerClick = onMarkerClick,
                    placementModeForPlantId = placementModeForPlantId, onPlacementSaved = onPlacementSaved,
                    onNavigateToHelp = onNavigateToHelp
                )
            }
        }
    }
}
// ============================================================================
// IRRIGATION PATH HELPERS
// ============================================================================

val zoneColorPalette = listOf(
    Color(0xFF3D8FB0), Color(0xFFB0793D), Color(0xFF6B3DB0), Color(0xFF3DB073),
    Color(0xFFB03D6B), Color(0xFFB0AA3D), Color(0xFF3D62B0), Color(0xFF8FB03D)
)

fun colorForZone(zone: String): Color {
    val idx = kotlin.math.abs(zone.hashCode()) % zoneColorPalette.size
    return zoneColorPalette[idx]
}

data class PathSegment(
    val type: String, // "main" | "drip" | "sprinkler" | "impact_sprinkler"
    val points: List<Offset>,
    val targetPlantIds: List<String> = emptyList(),
    val radius: Float? = null // fraction of map width — used by "sprinkler" only
)

fun segmentsToJson(segments: List<PathSegment>): String {
    val arr = org.json.JSONArray()
    segments.forEach { seg ->
        val obj = org.json.JSONObject()
        obj.put("type", seg.type)
        val pts = org.json.JSONArray()
        seg.points.forEach { p ->
            val pair = org.json.JSONArray()
            pair.put(p.x.toDouble()); pair.put(p.y.toDouble())
            pts.put(pair)
        }
        obj.put("points", pts)
        val targets = org.json.JSONArray()
        seg.targetPlantIds.forEach { targets.put(it) }
        obj.put("targets", targets)
        seg.radius?.let { obj.put("radius", it.toDouble()) }
        arr.put(obj)
    }
    return arr.toString()
}

fun jsonToSegments(json: String): List<PathSegment> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val type = obj.optString("type", "main")
            val ptsArr = obj.getJSONArray("points")
            val points = (0 until ptsArr.length()).map { j ->
                val pair = ptsArr.getJSONArray(j)
                Offset(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
            }
            val targetsArr = obj.optJSONArray("targets") ?: org.json.JSONArray()
            val targets = (0 until targetsArr.length()).map { targetsArr.getString(it) }
            val radius = if (obj.has("radius")) obj.optDouble("radius").toFloat() else null
            PathSegment(type, points, targets, radius)
        }
    } catch (_: Exception) { emptyList() }
}

fun distancePointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSq = abx * abx + aby * aby
    if (lengthSq == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSq).coerceIn(0f, 1f)
    val proj = Offset(a.x + t * abx, a.y + t * aby)
    return (p - proj).getDistance()
}

fun distancePointToPolyline(p: Offset, points: List<Offset>): Float {
    if (points.size < 2) return points.firstOrNull()?.let { (p - it).getDistance() } ?: Float.MAX_VALUE
    var minDist = Float.MAX_VALUE
    for (i in 0 until points.size - 1) {
        val d = distancePointToSegment(p, points[i], points[i + 1])
        if (d < minDist) minDist = d
    }
    return minDist
}

@Composable
fun CustomMapScreen(
    viewModel: PlantViewModel,
    pathViewModel: IrrigationPathViewModel,
    onMarkerClick: (String) -> Unit,
    placementModeForPlantId: String? = null,
    onPlacementSaved: (() -> Unit)? = null,
    onAddPlantAt: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plants by viewModel.filteredPlants.collectAsState()
    val paths by pathViewModel.paths.collectAsState()
    val mapUri = remember { getCustomMapUri(context) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current

    var scale by remember { mutableStateOf(1f) }
    var rotation by remember { mutableStateOf(0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var pendingFraction by remember { mutableStateOf<Offset?>(null) }
    var tooltipPlant by remember { mutableStateOf<PlantEntity?>(null) }

    // Irrigation path editing state
    var editingPaths by remember { mutableStateOf(false) }
    var editingPathId by remember { mutableStateOf<String?>(null) }
    var draftZone by remember { mutableStateOf("") }
    var draftOutlet by remember { mutableStateOf<Offset?>(null) }
    var placingOutlet by remember { mutableStateOf(false) }
    var isDrafting by remember { mutableStateOf(false) }
    var drawMode by remember { mutableStateOf<String?>(null) } // null | "main" | "drip" | "impact_sprinkler"
    var placingSprinklerCenter by remember { mutableStateOf(false) }
    var draftSprinklerCenter by remember { mutableStateOf<Offset?>(null) }
    var draftSprinklerRadius by remember { mutableStateOf(0.08f) }
    val draftSegments = remember { mutableStateListOf<PathSegment>() }
    val currentStroke = remember { mutableStateListOf<Offset>() }
    var attachingDripSegment by remember { mutableStateOf<List<Offset>?>(null) }
    val pendingDripTargets = remember { mutableStateListOf<String>() }
    var pathPendingDeletion by remember { mutableStateOf<IrrigationPathEntity?>(null) }
    var segmentPendingRemovalIndex by remember { mutableStateOf<Int?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "waterFlow")
    val dashPhase by infiniteTransition.animateFloat(
        initialValue = 40f, targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "dashPhase"
    )

    fun resetDraft() {
        draftZone = ""
        draftOutlet = null
        placingOutlet = false
        isDrafting = false
        drawMode = null
        draftSegments.clear()
        currentStroke.clear()
        attachingDripSegment = null
        pendingDripTargets.clear()
        editingPathId = null
        segmentPendingRemovalIndex = null
        placingSprinklerCenter = false
        draftSprinklerCenter = null
        draftSprinklerRadius = 0.08f
    }

    if (mapUri == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No custom map uploaded yet — add one in Help.", color = Color.Gray)
        }
        return
    }

    fun screenPointToFraction(tap: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val theta = Math.toRadians(-rotation.toDouble())
        val cosT = kotlin.math.cos(theta).toFloat()
        val sinT = kotlin.math.sin(theta).toFloat()
        val translated = tap - panOffset - center
        val rotated = Offset(
            translated.x * cosT - translated.y * sinT,
            translated.x * sinT + translated.y * cosT
        )
        val unscaled = rotated / scale
        val orig = unscaled + center
        return Offset(
            (orig.x / containerSize.width).coerceIn(0f, 1f),
            (orig.y / containerSize.height).coerceIn(0f, 1f)
        )
    }

    // Inverse of screenPointToFraction — where a map-fraction point actually renders on screen
    // right now, given the live pan/zoom/rotation. Used to hit-test existing plant markers against
    // a fixed on-screen radius (see below) rather than a fraction-space one, so the tap target
    // doesn't balloon in real screen size as the user zooms in.
    fun fractionToScreenPoint(frac: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val orig = Offset(frac.x * containerSize.width, frac.y * containerSize.height)
        val unscaled = orig - center
        val theta = Math.toRadians(rotation.toDouble())
        val cosT = kotlin.math.cos(theta).toFloat()
        val sinT = kotlin.math.sin(theta).toFloat()
        val rotated = Offset(
            unscaled.x * cosT - unscaled.y * sinT,
            unscaled.x * sinT + unscaled.y * cosT
        )
        return rotated * scale + center + panOffset
    }

    // Screen-space (not fraction-space) hit radius for "did the user tap an existing plant
    // marker" — matches each marker's own rendered radius exactly rather than padding out to a
    // comfortable touch target, and is zoom-independent (in dp, not fraction-space) so that
    // zooming in lets a plant be placed right next to an existing one instead of the marker's hit
    // target growing along with it. Deliberately precise rather than forgiving: a near-miss tap is
    // treated as "place a new plant here", which is what makes dense plantings placeable at all.
    fun plantMarkerRadiusPx(plant: PlantEntity): Float {
        return with(density) { (9.dp / 2).toPx() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { containerSize = it.size }
            .then(
                if (drawMode == null && attachingDripSegment == null && !editingPaths) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            scale = (scale * zoom).coerceIn(0.5f, 14f)
                            rotation += rot
                            panOffset += pan
                        }
                    }
                } else Modifier
            )
            .then(
                if (draftSprinklerCenter != null) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            draftSprinklerRadius = (draftSprinklerRadius * zoom).coerceIn(0.02f, 0.4f)
                        }
                    }
                } else Modifier
            )
            .pointerInput(editingPaths, placingOutlet, isDrafting, drawMode, attachingDripSegment, draftSegments.size, placingSprinklerCenter, draftSprinklerCenter) {
                detectTapGestures { tap ->
                    if (containerSize.width == 0) return@detectTapGestures
                    val frac = screenPointToFraction(tap)
                    if (editingPaths) {
                        when {
                            placingSprinklerCenter -> {
                                draftSprinklerCenter = frac
                                placingSprinklerCenter = false
                            }
                            placingOutlet -> {
                                draftOutlet = frac
                                placingOutlet = false
                                isDrafting = true
                            }
                            isDrafting && drawMode == null && attachingDripSegment == null && draftSprinklerCenter == null -> {
                                // Tap-to-remove — only active while editing a specific path's draft
                                val localPoint = Offset(frac.x * containerSize.width, frac.y * containerSize.height)
                                var closestIndex = -1
                                var closestDist = Float.MAX_VALUE
                                draftSegments.forEachIndexed { idx, seg ->
                                    val pxPoints = seg.points.map { Offset(it.x * containerSize.width, it.y * containerSize.height) }
                                    val dist = distancePointToPolyline(localPoint, pxPoints)
                                    if (dist < closestDist) { closestDist = dist; closestIndex = idx }
                                }
                                if (closestIndex >= 0 && closestDist <= 24f) {
                                    segmentPendingRemovalIndex = closestIndex
                                }
                            }
                            // else: browsing the main paths list — taps do nothing here
                        }
                    } else {
                        val nearestPlant = plants
                            .filter { it.mapX != null && it.mapY != null }
                            .minByOrNull { (fractionToScreenPoint(Offset(it.mapX!!.toFloat(), it.mapY!!.toFloat())) - tap).getDistance() }
                        val nearestDist = nearestPlant?.let { (fractionToScreenPoint(Offset(it.mapX!!.toFloat(), it.mapY!!.toFloat())) - tap).getDistance() }
                        if (nearestPlant != null && nearestDist != null && nearestDist <= plantMarkerRadiusPx(nearestPlant)) {
                            tooltipPlant = nearestPlant
                        } else {
                            pendingFraction = frac
                        }
                    }
                }
            }
            .then(
                if (drawMode != null) {
                    Modifier.pointerInput(drawMode) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentStroke.clear()
                                currentStroke.add(screenPointToFraction(offset))
                            },
                            onDrag = { change, _ ->
                                val frac = screenPointToFraction(change.position)
                                val last = currentStroke.lastOrNull()
                                if (last == null || (frac - last).getDistance() > 0.004f) {
                                    currentStroke.add(frac)
                                }
                            },
                            onDragEnd = {
                                if (currentStroke.size >= 2) {
                                    when (drawMode) {
                                        "main" -> {
                                            draftSegments.add(PathSegment("main", currentStroke.toList()))
                                            drawMode = null
                                        }
                                        "drip" -> {
                                            attachingDripSegment = currentStroke.toList()
                                            pendingDripTargets.clear()
                                            drawMode = null
                                        }
                                        "impact_sprinkler" -> {
                                            draftSegments.add(PathSegment("impact_sprinkler", currentStroke.toList()))
                                            drawMode = null
                                        }
                                    }
                                } else {
                                    drawMode = null
                                }
                                currentStroke.clear()
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    rotationZ = rotation,
                    translationX = panOffset.x, translationY = panOffset.y
                )
        ) {
            val mapRotation = remember { getCustomMapRotation(context) }
            AsyncImage(
                model = ImageRequest.Builder(context).data(mapUri).transformations(RotateTransformation(mapRotation.toFloat())).build(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                fun toPx(frac: Offset) = Offset(frac.x * w, frac.y * h)
                fun buildPath(points: List<Offset>): androidx.compose.ui.graphics.Path {
                    val path = androidx.compose.ui.graphics.Path()
                    if (points.isNotEmpty()) {
                        path.moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    }
                    return path
                }

                // Saved paths — dashed, animated to look like flowing water
                paths.forEach { pathEntity ->
                    val color = colorForZone(pathEntity.zone)
                    jsonToSegments(pathEntity.segmentsJson).forEach { seg ->
                        if (seg.type == "sprinkler" && seg.points.isNotEmpty()) {
                            val center = toPx(seg.points[0])
                            val radiusPx = (seg.radius ?: 0.08f) * w
                            drawCircle(color = color.copy(alpha = 0.22f), radius = radiusPx, center = center)
                            drawCircle(color = color, radius = radiusPx, center = center, style = Stroke(width = 3f))
                            drawCircle(color = color, radius = 6f, center = center)
                        } else {
                            val pxPoints = seg.points.map { toPx(it) }
                            if (pxPoints.size >= 2) {
                                val isMain = seg.type == "main"
                                val isImpact = seg.type == "impact_sprinkler"
                                drawPath(
                                    path = buildPath(pxPoints),
                                    color = if (isImpact) color.copy(alpha = 0.35f) else color,
                                    style = Stroke(
                                        width = if (isMain) 12f else if (isImpact) 26f else 5f,
                                        cap = StrokeCap.Round,
                                        pathEffect = when {
                                            isImpact -> null
                                            isMain -> PathEffect.dashPathEffect(floatArrayOf(26f, 14f), phase = 0f)
                                            else -> PathEffect.dashPathEffect(floatArrayOf(9f, 11f), phase = dashPhase)
                                        }
                                    )
                                )
                            }
                        }
                    }
                    val outletPx = toPx(Offset(pathEntity.outletX.toFloat(), pathEntity.outletY.toFloat()))
                    drawCircle(color = color, radius = 10f, center = outletPx)
                    drawCircle(color = Color.White, radius = 10f, center = outletPx, style = Stroke(width = 3f))
                }

                // In-progress draft
                draftSegments.forEachIndexed { idx, seg ->
                    val isSelected = segmentPendingRemovalIndex == idx
                    if (seg.type == "sprinkler" && seg.points.isNotEmpty()) {
                        val center = toPx(seg.points[0])
                        val radiusPx = (seg.radius ?: 0.08f) * w
                        val col = if (isSelected) Color(0xFFE53935) else Color(0xFF888888)
                        drawCircle(color = col.copy(alpha = 0.22f), radius = radiusPx, center = center)
                        drawCircle(color = col, radius = radiusPx, center = center, style = Stroke(width = 3f))
                    } else {
                        val pxPoints = seg.points.map { toPx(it) }
                        if (pxPoints.size >= 2) {
                            drawPath(
                                path = buildPath(pxPoints),
                                color = if (isSelected) Color(0xFFE53935) else Color(0xFF888888),
                                style = Stroke(
                                    width = (if (seg.type == "main") 12f else if (seg.type == "impact_sprinkler") 26f else 5f) + if (isSelected) 4f else 0f,
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                    }
                }
                draftOutlet?.let { o -> drawCircle(color = Color(0xFF3D8FB0), radius = 10f, center = toPx(o)) }
                draftSprinklerCenter?.let { c ->
                    val center = toPx(c)
                    val radiusPx = draftSprinklerRadius * w
                    drawCircle(color = Color(0xFFFF7A45).copy(alpha = 0.25f), radius = radiusPx, center = center)
                    drawCircle(color = Color(0xFFFF7A45), radius = radiusPx, center = center, style = Stroke(width = 3f))
                }

                if (currentStroke.size >= 2) {
                    drawPath(
                        path = buildPath(currentStroke.map { toPx(it) }),
                        color = Color(0xFFFF7A45),
                        style = Stroke(
                            width = when (drawMode) { "main" -> 12f; "impact_sprinkler" -> 26f; else -> 5f },
                            cap = StrokeCap.Round
                        )
                    )
                }
                attachingDripSegment?.let { pts ->
                    if (pts.size >= 2) {
                        drawPath(
                            path = buildPath(pts.map { toPx(it) }),
                            color = Color(0xFFFF7A45),
                            style = Stroke(width = 5f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            plants.forEach { plant ->
                if (plant.mapX != null && plant.mapY != null && containerSize.width > 0) {
                    val xDp = with(density) { (plant.mapX * containerSize.width).toFloat().toDp() }
                    val yDp = with(density) { (plant.mapY * containerSize.height).toFloat().toDp() }
                    val isPendingTarget = attachingDripSegment != null && pendingDripTargets.contains(plant.id)
                    val markerSize = 9.dp
                    val markerColor = categoryMarkerColor(plant.category)
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - markerSize / 2, y = yDp - markerSize / 2)
                            .size(markerSize)
                            .clip(RoundedCornerShape(50))
                            .background(markerColor)
                            .border(if (isPendingTarget) 1.5.dp else 0.75.dp, Color.White, RoundedCornerShape(50))
                            .then(
                                // Only intercepts taps while picking drip-segment targets. Plain
                                // plant lookup/placement taps are handled by the parent's fixed-radius
                                // hit test instead, so this tiny marker's touch target doesn't grow
                                // to match it when zoomed in (see fractionToScreenPoint above).
                                if (attachingDripSegment != null) {
                                    Modifier.clickable {
                                        if (pendingDripTargets.contains(plant.id)) pendingDripTargets.remove(plant.id)
                                        else pendingDripTargets.add(plant.id)
                                    }
                                } else Modifier
                            )
                    )
                }
            }

            pendingFraction?.let { frac ->
                val xDp = with(density) { (frac.x * containerSize.width).toDp() }
                val yDp = with(density) { (frac.y * containerSize.height).toDp() }
                Box(
                    modifier = Modifier.offset(x = xDp - 4.dp, y = yDp - 4.dp).size(8.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xFFFF7A45).copy(alpha = 0.85f))
                        .border(1.dp, Color.White, RoundedCornerShape(50))
                )
            }
        }

        pendingFraction?.let { frac ->
            AlertDialog(
                onDismissRequest = { pendingFraction = null },
                title = { Text("Add plant here?") },
                text = { Text("Place a plant marker at this spot on your drawing.") },
                confirmButton = {
                    TextButton(onClick = {
                        val f = frac
                        pendingFraction = null
                        if (placementModeForPlantId != null) {
                            scope.launch {
                                val plant = viewModel.getById(placementModeForPlantId)
                                if (plant != null) viewModel.save(plant.copy(mapX = f.x.toDouble(), mapY = f.y.toDouble()))
                                onPlacementSaved?.invoke()
                            }
                        } else {
                            onAddPlantAt(f.x.toDouble(), f.y.toDouble())
                        }
                    }) { Text("Add plant here") }
                },
                dismissButton = { TextButton(onClick = { pendingFraction = null }) { Text("Cancel") } }
            )
        }

        tooltipPlant?.let { plant ->
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { /* consume */ } }
            ) {
                PlantTooltipCard(plant = plant, onEdit = { onMarkerClick(plant.id) }, onDismiss = { tooltipPlant = null })
            }
        }

        if (pathPendingDeletion != null) {
            val p = pathPendingDeletion!!
            AlertDialog(
                onDismissRequest = { pathPendingDeletion = null },
                title = { Text("Delete this path?") },
                text = { Text("This removes the \"${p.zone}\" irrigation path. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = { pathViewModel.delete(p.id); pathPendingDeletion = null }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { pathPendingDeletion = null }) { Text("Cancel") } }
            )
        }
        segmentPendingRemovalIndex?.let { idx ->
            if (idx !in draftSegments.indices) {
                segmentPendingRemovalIndex = null
            } else {
                AlertDialog(
                    onDismissRequest = { segmentPendingRemovalIndex = null },
                    title = { Text("Remove this segment?") },
                    text = { Text("Removes the highlighted pipe/drip segment from this path's draft. Use \"Cancel path\" instead if you want to discard all your changes.") },
                    confirmButton = {
                        TextButton(onClick = {
                            draftSegments.removeAt(idx)
                            segmentPendingRemovalIndex = null
                        }) { Text("Remove") }
                    },
                    dismissButton = { TextButton(onClick = { segmentPendingRemovalIndex = null }) { Text("Cancel") } }
                )
            }
        }
        if (placementModeForPlantId == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (!editingPaths) {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { editingPaths = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8FB0))
                        ) { Text("💧 Edit irrigation paths", fontSize = 12.sp) }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            when {
                                placingOutlet -> {
                                    Text("Tap the drawing to mark where the outlet/tap starts 🚰", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { placingOutlet = false; if (draftOutlet == null) resetDraft() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel", fontSize = 12.sp)
                                    }
                                }
                                placingSprinklerCenter -> {
                                    Text("Tap the drawing to place the sprinkler 💧", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { placingSprinklerCenter = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel", fontSize = 12.sp)
                                    }
                                }
                                draftSprinklerCenter != null -> {
                                    Text("Pinch to adjust the spread, then confirm", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                draftSegments.add(PathSegment("sprinkler", listOf(draftSprinklerCenter!!), radius = draftSprinklerRadius))
                                                draftSprinklerCenter = null
                                                draftSprinklerRadius = 0.08f
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Confirm", fontSize = 12.sp) }
                                        OutlinedButton(
                                            onClick = { draftSprinklerCenter = null; draftSprinklerRadius = 0.08f },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Cancel", fontSize = 12.sp) }
                                    }
                                }
                                drawMode != null -> {
                                    Text(
                                        when (drawMode) {
                                            "main" -> "Drawing main pipe — drag along the pipe, lift when done"
                                            "impact_sprinkler" -> "Drawing impact sprinkler sweep — drag along its arc, lift when done"
                                            else -> "Drawing drip line — drag from the pipe toward the plant(s), lift when done"
                                        },
                                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { drawMode = null; currentStroke.clear() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel segment", fontSize = 12.sp)
                                    }
                                }
                                attachingDripSegment != null -> {
                                    Text("Tap the plant(s) this drip line waters", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${pendingDripTargets.size} plant(s) selected", fontSize = 11.sp, color = Color.Gray)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                draftSegments.add(PathSegment("drip", attachingDripSegment!!, pendingDripTargets.toList()))
                                                attachingDripSegment = null
                                                pendingDripTargets.clear()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Confirm", fontSize = 12.sp) }
                                        OutlinedButton(
                                            onClick = { attachingDripSegment = null; pendingDripTargets.clear() },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Discard", fontSize = 12.sp) }
                                    }
                                }
                                isDrafting -> {
                                    Text("Editing path for \"$draftZone\"", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        "${draftSegments.count { it.type == "main" }} main segment(s), ${draftSegments.count { it.type == "drip" }} drip line(s), " +
                                                "${draftSegments.count { it.type == "sprinkler" || it.type == "impact_sprinkler" }} sprinkler(s)",
                                        fontSize = 11.sp, color = Color.Gray
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { drawMode = "main" }, modifier = Modifier.weight(1f)) { Text("Draw main pipe", fontSize = 11.sp) }
                                        Button(onClick = { drawMode = "drip" }, modifier = Modifier.weight(1f)) { Text("Draw drip line", fontSize = 11.sp) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { placingSprinklerCenter = true }, modifier = Modifier.weight(1f)) { Text("Add sprinkler", fontSize = 11.sp) }
                                        Button(onClick = { drawMode = "impact_sprinkler" }, modifier = Modifier.weight(1f)) { Text("Draw impact sprinkler", fontSize = 11.sp) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { if (draftSegments.isNotEmpty()) draftSegments.removeAt(draftSegments.size - 1) },
                                            enabled = draftSegments.isNotEmpty(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Undo last segment", fontSize = 11.sp) }
                                        OutlinedButton(onClick = { placingOutlet = true }, modifier = Modifier.weight(1f)) {
                                            Text("Move outlet", fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                val outlet = draftOutlet
                                                if (outlet != null && draftSegments.isNotEmpty()) {
                                                    pathViewModel.save(
                                                        IrrigationPathEntity(
                                                            id = editingPathId ?: "path-${System.currentTimeMillis()}",   // was: always new id
                                                            zone = draftZone,
                                                            outletX = outlet.x.toDouble(),
                                                            outletY = outlet.y.toDouble(),
                                                            segmentsJson = segmentsToJson(draftSegments)
                                                        )
                                                    )
                                                    resetDraft()
                                                }
                                            },
                                            enabled = draftOutlet != null && draftSegments.isNotEmpty(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Finish path", fontSize = 12.sp) }
                                        OutlinedButton(onClick = { resetDraft() }, modifier = Modifier.weight(1f)) {
                                            Text("Cancel path", fontSize = 12.sp)
                                        }
                                    }
                                    if (draftOutlet == null || draftSegments.isEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Draw at least one segment and set the outlet before finishing. Cancel discards this draft without saving.",
                                            fontSize = 10.sp, color = Color.Gray
                                        )
                                    }
                                }
                                else -> {
                                    Text("Irrigation paths", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = draftZone,
                                        onValueChange = { draftZone = it },
                                        label = { Text("Zone name") },
                                        supportingText = { Text("Match a Tuya zone name for consistent colouring", fontSize = 10.sp) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { if (draftZone.isNotBlank()) placingOutlet = true },
                                            enabled = draftZone.isNotBlank(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Start new path", fontSize = 12.sp) }
                                        OutlinedButton(onClick = { editingPaths = false; segmentPendingRemovalIndex = null }, modifier = Modifier.weight(1f)) {
                                            Text("Done", fontSize = 12.sp)
                                        }
                                    }
                                    if (paths.isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        HorizontalDivider()
                                        Spacer(Modifier.height(6.dp))
                                        paths.forEach { p ->
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colorForZone(p.zone)))
                                                Spacer(Modifier.width(8.dp))
                                                Text(p.zone, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    draftZone = p.zone
                                                    draftOutlet = Offset(p.outletX.toFloat(), p.outletY.toFloat())
                                                    draftSegments.clear()
                                                    draftSegments.addAll(jsonToSegments(p.segmentsJson))
                                                    editingPathId = p.id
                                                    segmentPendingRemovalIndex = null
                                                    isDrafting = true
                                                }) { Text("Edit", fontSize = 11.sp) }
                                                TextButton(onClick = { pathPendingDeletion = p }) { Text("Delete", fontSize = 11.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
