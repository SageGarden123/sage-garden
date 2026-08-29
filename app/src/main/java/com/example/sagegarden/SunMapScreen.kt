@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

/** Persists the Real Map camera across leaving/re-entering SunMapScreen entirely — navigating away
 * (back, or switching bottom-nav tabs) disposes the whole composable, which would otherwise reset
 * cameraPositionState (and with it pan/zoom) back to the default every time. Same rationale as
 * HemisphereState/AdvancedModeState etc. [initialized] guards the one-time GPS-based initial
 * position so re-entering the screen never overrides wherever the user last panned to. */
object SunMapCameraState {
    var lat by mutableStateOf(40.785091)
    var lng by mutableStateOf(-73.968285)
    var zoom by mutableStateOf(18f)
    var initialized by mutableStateOf(false)
}

// ---- Sun zone data + matching helpers (also used by the audit tab) ----

fun sunPointsToJson(points: List<Offset>): String {
    val arr = org.json.JSONArray()
    points.forEach { p ->
        val pair = org.json.JSONArray()
        pair.put(p.x.toDouble()); pair.put(p.y.toDouble())
        arr.put(pair)
    }
    return arr.toString()
}

fun sunJsonToPoints(json: String): List<Offset> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val pair = arr.getJSONArray(i)
            Offset(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
        }
    } catch (_: Exception) { emptyList() }
}

/** Real-map zones store full-precision [lat, lng] pairs — kept separate from the fraction-based helpers above since Float precision isn't enough for real-world coordinates. */
fun latLngPointsToJson(points: List<LatLng>): String {
    val arr = org.json.JSONArray()
    points.forEach { p ->
        val pair = org.json.JSONArray()
        pair.put(p.latitude); pair.put(p.longitude)
        arr.put(pair)
    }
    return arr.toString()
}

fun jsonToLatLngPoints(json: String): List<LatLng> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val pair = arr.getJSONArray(i)
            LatLng(pair.getDouble(0), pair.getDouble(1))
        }
    } catch (_: Exception) { emptyList() }
}

fun pointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if ((pi.y > point.y) != (pj.y > point.y) &&
            point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x
        ) inside = !inside
        j = i
    }
    return inside
}

fun pointInPolygonLatLng(point: LatLng, polygon: List<LatLng>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if ((pi.longitude > point.longitude) != (pj.longitude > point.longitude) &&
            point.latitude < (pj.latitude - pi.latitude) * (point.longitude - pi.longitude) / (pj.longitude - pi.longitude) + pi.latitude
        ) inside = !inside
        j = i
    }
    return inside
}

val sunZoneCategories = listOf(
    "full_sun" to "Full sun",
    "morning_sun" to "Morning sun",
    "afternoon_sun" to "Afternoon sun",
    "part_shade" to "Part shade",
    "full_shade" to "Full shade"
)

fun colorForSunCategory(category: String): Color = when (category) {
    "full_sun" -> Color(0xFFFFC107)
    "morning_sun" -> Color(0xFFFFE082)
    "afternoon_sun" -> Color(0xFFFFB74D)
    "part_shade" -> Color(0xFF90A4AE)
    else -> Color(0xFF546E7A)
}

fun labelForSunCategory(category: String): String =
    sunZoneCategories.firstOrNull { it.first == category }?.second ?: category

private fun sunZoneLevel(category: String): Float = when (category) {
    "full_sun" -> 3f
    "morning_sun", "afternoon_sun" -> 2f
    "part_shade" -> 1f
    else -> 0f
}

private fun plantSunLevel(sun: String): Float? = when (sun) {
    "Full" -> 3f
    "Full-Partial" -> 2.5f
    "Partial" -> 2f
    "Partial-Shade" -> 1f
    "Shade" -> 0f
    else -> null
}

/**
 * Returns a short mismatch label, or null if there's no zone data at this plant's spot, or no
 * meaningful mismatch. Checks "custom" zones against the plant's custom-map position (mapX/mapY)
 * and "real" zones against its real-world position (lat/lng) — a plant only matches the zone type(s)
 * it actually has coordinates for.
 */
fun sunMismatchLabel(plant: PlantEntity, zones: List<SunZoneEntity>): String? {
    val plantLevel = plantSunLevel(plant.sun) ?: return null

    val customZone = if (plant.mapX != null && plant.mapY != null) {
        val point = Offset(plant.mapX.toFloat(), plant.mapY.toFloat())
        zones.firstOrNull { it.mapType == "custom" && pointInPolygon(point, sunJsonToPoints(it.pointsJson)) }
    } else null

    val realZone = if (plant.lat != null && plant.lng != null) {
        val point = LatLng(plant.lat, plant.lng)
        zones.firstOrNull { it.mapType == "real" && pointInPolygonLatLng(point, jsonToLatLngPoints(it.pointsJson)) }
    } else null

    val zone = customZone ?: realZone ?: return null
    val diff = sunZoneLevel(zone.category) - plantLevel
    return when {
        diff >= 1.5f -> "Getting more sun than it needs"
        diff <= -1.5f -> "Getting less sun than it needs"
        else -> null
    }
}

// ---- Screen ----

@Composable
fun SunMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sunViewModel: SunZoneViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val zones by sunViewModel.zones.collectAsState()
    val mapUri = remember { getCustomMapUri(context) }
    val hasCustomMap = mapUri != null

    // Real Map is the default base layer — users can switch to their uploaded drawing if they have one.
    var showingRealMap by remember { mutableStateOf(true) }

    var selectedCategory by remember { mutableStateOf("full_sun") }
    var drawMode by remember { mutableStateOf<String?>(null) } // null | "freehand" | "tap"
    var strokeComplete by remember { mutableStateOf(false) } // freehand only: finger lifted, awaiting confirm
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var selectedZoneId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Custom-map (image) drawing state
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageIntrinsicSize by remember { mutableStateOf<androidx.compose.ui.geometry.Size?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val currentStroke = remember { mutableStateListOf<Offset>() }

    // Real-map (Google Map) drawing state
    val currentStrokeReal = remember { mutableStateListOf<LatLng>() }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(SunMapCameraState.lat, SunMapCameraState.lng), SunMapCameraState.zoom)
    }

    // Only ever runs once per process (not on every re-entry to this screen), so it never overrides
    // wherever the user last panned/zoomed to — see SunMapCameraState's doc comment. Prefers the
    // garden address configured in Help (the same one the real Map tab centers on) over raw device
    // GPS, since that's a deliberate choice about which garden this is, not just "wherever this
    // phone happens to be standing right now" — falls back to GPS only when no garden address is set.
    LaunchedEffect(Unit) {
        if (SunMapCameraState.initialized) return@LaunchedEffect
        SunMapCameraState.initialized = true
        val gardenLatLng = getGardenLatLng(context)
        if (gardenLatLng != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(gardenLatLng.first, gardenLatLng.second), 18f)
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(location.latitude, location.longitude), 18f)
                    }
                }
            } catch (_: SecurityException) { /* permission revoked mid-flight, ignore */ }
        }
    }

    // Mirrors every camera move back into the persistent singleton above.
    LaunchedEffect(cameraPositionState.position) {
        val pos = cameraPositionState.position
        SunMapCameraState.lat = pos.target.latitude
        SunMapCameraState.lng = pos.target.longitude
        SunMapCameraState.zoom = pos.zoom
    }

    // The map image is shown with ContentScale.Fit, so unless its aspect ratio exactly matches
    // the container's, it's letterboxed (blank margins on two sides). Zone points are stored as
    // fractions of the actual IMAGE content, not the raw container — otherwise a zone traced
    // against a feature in the photo would land in the wrong place once redrawn, and any letterbox
    // margin would skew the shape (which is what made confirmed zones look "off").
    fun fittedImageRect(): androidx.compose.ui.geometry.Rect {
        val cw = containerSize.width.toFloat(); val ch = containerSize.height.toFloat()
        val intrinsic = imageIntrinsicSize
        if (intrinsic == null || cw <= 0f || ch <= 0f || intrinsic.width <= 0f || intrinsic.height <= 0f) {
            return androidx.compose.ui.geometry.Rect(0f, 0f, cw, ch)
        }
        val containerAspect = cw / ch
        val imageAspect = intrinsic.width / intrinsic.height
        return if (imageAspect > containerAspect) {
            val fh = cw / imageAspect
            val oy = (ch - fh) / 2f
            androidx.compose.ui.geometry.Rect(0f, oy, cw, oy + fh)
        } else {
            val fw = ch * imageAspect
            val ox = (cw - fw) / 2f
            androidx.compose.ui.geometry.Rect(ox, 0f, ox + fw, ch)
        }
    }

    fun screenPointToFraction(tap: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val unscaled = (tap - panOffset - center) / scale + center
        val rect = fittedImageRect()
        if (rect.width <= 0f || rect.height <= 0f) return Offset(0.5f, 0.5f)
        return Offset(
            ((unscaled.x - rect.left) / rect.width).coerceIn(0f, 1f),
            ((unscaled.y - rect.top) / rect.height).coerceIn(0f, 1f)
        )
    }

    // Inverse of screenPointToFraction, solved for the panOffset that puts a given fraction-space
    // point at the container's center — used to bring a selected zone into view when tapped/picked.
    fun centerOnFraction(frac: Offset) {
        val rect = fittedImageRect()
        val unscaled = Offset(rect.left + frac.x * rect.width, rect.top + frac.y * rect.height)
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        panOffset = (center - unscaled) * scale
    }

    fun centroidOf(points: List<Offset>): Offset =
        if (points.isEmpty()) Offset(0.5f, 0.5f)
        else Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

    fun centroidOfLatLng(points: List<LatLng>): LatLng =
        if (points.isEmpty()) LatLng(SunMapCameraState.lat, SunMapCameraState.lng)
        else LatLng(points.map { it.latitude }.average(), points.map { it.longitude }.average())

    val strokeSize = if (showingRealMap) currentStrokeReal.size else currentStroke.size

    fun clearStrokes() {
        currentStroke.clear()
        currentStrokeReal.clear()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Spacer(Modifier.weight(1f))
            Text("Sun exposure map", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
        }

        if (hasCustomMap) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showingRealMap = true; drawMode = null; clearStrokes() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showingRealMap) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                        contentColor = if (showingRealMap) Color.White else Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Real Map", fontSize = 12.sp) }
                Button(
                    onClick = { showingRealMap = false; drawMode = null; clearStrokes() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!showingRealMap) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                        contentColor = if (!showingRealMap) Color.White else Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("My Drawing", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Always composed (never behind an `if`) so the native map view — and with it
            // cameraPositionState's pan/zoom — survives toggling to "My Drawing" and back.
            // It used to be conditionally composed, which tore the map down (resetting zoom to
            // the default) every time the user switched away from Real Map and back.
            GoogleMap(
                    modifier = Modifier.fillMaxSize().alpha(if (showingRealMap) 1f else 0f),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(mapType = MapType.HYBRID),
                    uiSettings = MapUiSettings(
                        scrollGesturesEnabled = showingRealMap && drawMode != "freehand",
                        zoomGesturesEnabled = showingRealMap && drawMode != "freehand",
                        tiltGesturesEnabled = false,
                        rotationGesturesEnabled = false
                    ),
                    onMapClick = { latLng ->
                        if (showingRealMap && drawMode == "tap") {
                            currentStrokeReal.add(latLng)
                        } else if (showingRealMap && drawMode == null) {
                            val hit = zones.filter { it.mapType == "real" }
                                .firstOrNull { pointInPolygonLatLng(latLng, jsonToLatLngPoints(it.pointsJson)) }
                            if (hit != null) {
                                selectedZoneId = hit.id
                                scope.launch {
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLng(centroidOfLatLng(jsonToLatLngPoints(hit.pointsJson))))
                                }
                            }
                        }
                    }
                ) {
                    zones.filter { it.mapType == "real" }.forEach { zone ->
                        val pts = jsonToLatLngPoints(zone.pointsJson)
                        if (pts.size >= 3) {
                            val isSelected = zone.id == selectedZoneId
                            Polygon(
                                points = pts,
                                fillColor = colorForSunCategory(zone.category).copy(alpha = if (isSelected) 0.6f else 0.35f),
                                strokeColor = if (isSelected) Color.White else colorForSunCategory(zone.category),
                                strokeWidth = if (isSelected) 7f else 4f,
                                clickable = true,
                                onClick = {
                                    selectedZoneId = zone.id
                                    scope.launch { cameraPositionState.animate(CameraUpdateFactory.newLatLng(centroidOfLatLng(pts))) }
                                }
                            )
                        }
                    }

                    if (currentStrokeReal.size >= 2) {
                        val shouldClose = (drawMode == "tap" || strokeComplete) && currentStrokeReal.size >= 3
                        if (shouldClose) {
                            Polygon(
                                points = currentStrokeReal.toList(),
                                fillColor = colorForSunCategory(selectedCategory).copy(alpha = 0.35f),
                                strokeColor = colorForSunCategory(selectedCategory),
                                strokeWidth = 4f
                            )
                        } else {
                            Polyline(points = currentStrokeReal.toList(), color = colorForSunCategory(selectedCategory), width = 6f)
                        }
                    }
                    if (drawMode == "tap") {
                        currentStrokeReal.forEach { p ->
                            Circle(center = p, radius = 0.4, fillColor = colorForSunCategory(selectedCategory), strokeColor = Color.White, strokeWidth = 2f)
                        }
                    }
                }

                if (showingRealMap && drawMode == "freehand") {
                    var lastDragScreenPoint by remember { mutableStateOf<Offset?>(null) }
                    Box(
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentStrokeReal.clear()
                                    strokeComplete = false
                                    lastDragScreenPoint = offset
                                    cameraPositionState.projection
                                        ?.fromScreenLocation(android.graphics.Point(offset.x.toInt(), offset.y.toInt()))
                                        ?.let { currentStrokeReal.add(it) }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val last = lastDragScreenPoint
                                    if (last == null || (change.position - last).getDistance() > 8f) {
                                        lastDragScreenPoint = change.position
                                        cameraPositionState.projection
                                            ?.fromScreenLocation(android.graphics.Point(change.position.x.toInt(), change.position.y.toInt()))
                                            ?.let { currentStrokeReal.add(it) }
                                    }
                                },
                                onDragEnd = { strokeComplete = currentStrokeReal.size >= 3 }
                            )
                        }
                    )
                }

            if (!showingRealMap && mapUri != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .onGloballyPositioned { containerSize = it.size }
                        .then(
                            when (drawMode) {
                                "freehand" -> Modifier.pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentStroke.clear()
                                            strokeComplete = false
                                            currentStroke.add(screenPointToFraction(offset))
                                        },
                                        onDrag = { change, _ ->
                                            val frac = screenPointToFraction(change.position)
                                            val last = currentStroke.lastOrNull()
                                            if (last == null || (frac - last).getDistance() > 0.004f) currentStroke.add(frac)
                                        },
                                        onDragEnd = { strokeComplete = currentStroke.size >= 3 }
                                    )
                                }
                                "tap" -> Modifier
                                    .pointerInput(Unit) {
                                        detectTapGestures(onTap = { offset -> currentStroke.add(screenPointToFraction(offset)) })
                                    }
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(0.5f, 6f)
                                            panOffset += pan
                                        }
                                    }
                                else -> Modifier
                                    .pointerInput(zones) {
                                        detectTapGestures(onTap = { offset ->
                                            val frac = screenPointToFraction(offset)
                                            val hit = zones.filter { it.mapType == "custom" }
                                                .firstOrNull { pointInPolygon(frac, sunJsonToPoints(it.pointsJson)) }
                                            if (hit != null) {
                                                selectedZoneId = hit.id
                                                centerOnFraction(centroidOf(sunJsonToPoints(hit.pointsJson)))
                                            }
                                        })
                                    }
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(0.5f, 6f)
                                            panOffset += pan
                                        }
                                    }
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = panOffset.x, translationY = panOffset.y
                        )
                    ) {
                        val mapRotation = remember { getCustomMapRotation(context) }
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(mapUri).transformations(RotateTransformation(mapRotation.toFloat())).build(),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                            onSuccess = { state ->
                                val d = state.result.drawable
                                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                    imageIntrinsicSize = androidx.compose.ui.geometry.Size(d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
                                }
                            }
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val rect = fittedImageRect()
                            fun toPx(p: Offset) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)

                            zones.filter { it.mapType == "custom" }.forEach { zone ->
                                val pts = sunJsonToPoints(zone.pointsJson).map { toPx(it) }
                                if (pts.size >= 3) {
                                    val isSelected = zone.id == selectedZoneId
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(pts[0].x, pts[0].y)
                                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                        close()
                                    }
                                    drawPath(path, color = colorForSunCategory(zone.category).copy(alpha = if (isSelected) 0.6f else 0.35f), style = Fill)
                                    drawPath(
                                        path,
                                        color = if (isSelected) Color.White else colorForSunCategory(zone.category),
                                        style = Stroke(width = if (isSelected) 6f else 3f)
                                    )
                                }
                            }

                            if (currentStroke.size >= 2) {
                                val pts = currentStroke.map { toPx(it) }
                                val shouldClose = drawMode == "tap" || strokeComplete
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                    if (shouldClose && pts.size >= 3) close()
                                }
                                if (shouldClose && pts.size >= 3) {
                                    drawPath(path, color = colorForSunCategory(selectedCategory).copy(alpha = 0.35f), style = Fill)
                                }
                                drawPath(path, color = colorForSunCategory(selectedCategory), style = Stroke(width = 4f, cap = StrokeCap.Round))
                            }
                            if (drawMode == "tap") {
                                currentStroke.map { toPx(it) }.forEach { p ->
                                    drawCircle(color = colorForSunCategory(selectedCategory), radius = 9f, center = p)
                                    drawCircle(color = Color.White, radius = 9f, center = p, style = Stroke(width = 2f))
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (drawMode != null) {
                Text(
                    if (drawMode == "tap") "Tap to place each corner, then confirm.${if (!showingRealMap) " Pinch to zoom." else ""}"
                    else "Drag to trace the zone's outline, then confirm.",
                    fontSize = 12.sp, color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (drawMode == "tap") {
                        OutlinedButton(
                            onClick = {
                                if (showingRealMap) { if (currentStrokeReal.isNotEmpty()) currentStrokeReal.removeAt(currentStrokeReal.lastIndex) }
                                else { if (currentStroke.isNotEmpty()) currentStroke.removeAt(currentStroke.lastIndex) }
                            },
                            modifier = Modifier.weight(1f), enabled = strokeSize > 0
                        ) { Text("Undo point", fontSize = 12.sp) }
                    }
                    Button(
                        onClick = {
                            if (strokeSize >= 3) {
                                val zone = if (showingRealMap) {
                                    SunZoneEntity(
                                        id = "SZ-${System.currentTimeMillis()}", category = selectedCategory,
                                        pointsJson = latLngPointsToJson(currentStrokeReal.toList()), mapType = "real"
                                    )
                                } else {
                                    SunZoneEntity(
                                        id = "SZ-${System.currentTimeMillis()}", category = selectedCategory,
                                        pointsJson = sunPointsToJson(currentStroke.toList()), mapType = "custom"
                                    )
                                }
                                sunViewModel.save(zone)
                            }
                            clearStrokes()
                            strokeComplete = false
                            drawMode = null
                        },
                        modifier = Modifier.weight(1f), enabled = strokeSize >= 3
                    ) { Text("Confirm zone", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { clearStrokes(); strokeComplete = false; drawMode = null },
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel", fontSize = 12.sp) }
                }
            } else {
                Text("Zone type", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    sunZoneCategories.forEach { (key, label) ->
                        val selected = selectedCategory == key
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) colorForSunCategory(key) else colorForSunCategory(key).copy(alpha = 0.25f))
                                .then(
                                    if (selected) Modifier.border(2.dp, Color(0xFF233821), RoundedCornerShape(8.dp))
                                    else Modifier
                                )
                                .clickable { selectedCategory = key }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(label, fontSize = 10.sp, color = Color.Black, textAlign = TextAlign.Center) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { clearStrokes(); drawMode = "freehand" }, modifier = Modifier.weight(1f)) { Text("✏️ Draw freehand", fontSize = 12.sp) }
                    Button(onClick = { clearStrokes(); drawMode = "tap" }, modifier = Modifier.weight(1f)) { Text("📍 Tap points", fontSize = 12.sp) }
                }

                val visibleZones = zones.filter { it.mapType == (if (showingRealMap) "real" else "custom") }
                if (visibleZones.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    val zoneListScrollState = rememberScrollState()
                    val zoneRowPositions = remember { mutableStateMapOf<String, Float>() }
                    // Jumps the list to whichever zone was just selected — including from a tap on
                    // the map itself, not just a click within the list — so a long list of
                    // same-named zones doesn't leave the user hunting for the one that just lit up.
                    LaunchedEffect(selectedZoneId) {
                        selectedZoneId?.let { id -> zoneRowPositions[id]?.let { y -> zoneListScrollState.animateScrollTo(y.toInt()) } }
                    }
                    Column(Modifier.heightIn(max = 140.dp).verticalScroll(zoneListScrollState)) {
                        visibleZones.forEach { zone ->
                            val isSelected = zone.id == selectedZoneId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { zoneRowPositions[zone.id] = it.positionInParent().y }
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(if (isSelected) Modifier.background(Color(0xFF3A5A40).copy(alpha = 0.15f)) else Modifier)
                                    .clickable {
                                        selectedZoneId = zone.id
                                        if (showingRealMap) {
                                            val pts = jsonToLatLngPoints(zone.pointsJson)
                                            scope.launch { cameraPositionState.animate(CameraUpdateFactory.newLatLng(centroidOfLatLng(pts))) }
                                        } else {
                                            centerOnFraction(centroidOf(sunJsonToPoints(zone.pointsJson)))
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colorForSunCategory(zone.category)))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    labelForSunCategory(zone.category), fontSize = 12.sp, modifier = Modifier.weight(1f),
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                TextButton(onClick = { pendingDeleteId = zone.id }) { Text("Delete", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this zone?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { sunViewModel.delete(id); pendingDeleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } }
        )
    }
}
