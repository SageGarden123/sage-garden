package com.example.dansgardenmapper

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
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

/** Returns a short mismatch label, or null if there's no zone data at this plant's spot, or no meaningful mismatch. */
fun sunMismatchLabel(plant: PlantEntity, zones: List<SunZoneEntity>): String? {
    val x = plant.mapX ?: return null
    val y = plant.mapY ?: return null
    val plantLevel = plantSunLevel(plant.sun) ?: return null
    val zone = zones.firstOrNull { pointInPolygon(Offset(x.toFloat(), y.toFloat()), sunJsonToPoints(it.pointsJson)) } ?: return null
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
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var selectedCategory by remember { mutableStateOf("full_sun") }
    var drawing by remember { mutableStateOf(false) }
    val currentStroke = remember { mutableStateListOf<Offset>() }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    if (mapUri == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Spacer(Modifier.height(20.dp))
            Text("Upload a custom map in Help first to map sun exposure.", color = Color.Gray)
        }
        return
    }

    fun screenPointToFraction(tap: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val unscaled = (tap - panOffset - center) / scale + center
        return Offset(
            (unscaled.x / containerSize.width).coerceIn(0f, 1f),
            (unscaled.y / containerSize.height).coerceIn(0f, 1f)
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Spacer(Modifier.weight(1f))
            Text("Sun exposure map", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { containerSize = it.size }
                .then(
                    if (!drawing) {
                        Modifier.pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 6f)
                                panOffset += pan
                            }
                        }
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke.clear(); currentStroke.add(screenPointToFraction(offset)) },
                                onDrag = { change, _ ->
                                    val frac = screenPointToFraction(change.position)
                                    val last = currentStroke.lastOrNull()
                                    if (last == null || (frac - last).getDistance() > 0.004f) currentStroke.add(frac)
                                },
                                onDragEnd = { }
                            )
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
                    contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    fun toPx(p: Offset) = Offset(p.x * w, p.y * h)

                    zones.forEach { zone ->
                        val pts = sunJsonToPoints(zone.pointsJson).map { toPx(it) }
                        if (pts.size >= 3) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                close()
                            }
                            drawPath(path, color = colorForSunCategory(zone.category).copy(alpha = 0.35f), style = Fill)
                            drawPath(path, color = colorForSunCategory(zone.category), style = Stroke(width = 3f))
                        }
                    }

                    if (currentStroke.size >= 2) {
                        val pts = currentStroke.map { toPx(it) }
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        }
                        drawPath(path, color = colorForSunCategory(selectedCategory), style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (drawing) {
                Text("Drag to trace the zone's outline, then confirm.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (currentStroke.size >= 3) {
                                sunViewModel.save(
                                    SunZoneEntity(id = "SZ-${System.currentTimeMillis()}", category = selectedCategory, pointsJson = sunPointsToJson(currentStroke.toList()))
                                )
                            }
                            currentStroke.clear()
                            drawing = false
                        },
                        modifier = Modifier.weight(1f), enabled = currentStroke.size >= 3
                    ) { Text("Confirm zone", fontSize = 12.sp) }
                    OutlinedButton(onClick = { currentStroke.clear(); drawing = false }, modifier = Modifier.weight(1f)) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                }
            } else {
                Text("Zone type", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    sunZoneCategories.forEach { (key, label) ->
                        val selected = selectedCategory == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) colorForSunCategory(key) else colorForSunCategory(key).copy(alpha = 0.25f))
                                .clickable { selectedCategory = key }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(label, fontSize = 10.sp, color = Color.Black, textAlign = TextAlign.Center) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { drawing = true }, modifier = Modifier.fillMaxWidth()) { Text("Draw zone", fontSize = 13.sp) }

                if (zones.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                        zones.forEach { zone ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colorForSunCategory(zone.category)))
                                Spacer(Modifier.width(8.dp))
                                Text(labelForSunCategory(zone.category), fontSize = 12.sp, modifier = Modifier.weight(1f))
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