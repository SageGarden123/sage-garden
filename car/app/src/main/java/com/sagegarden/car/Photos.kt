package com.sagegarden.car

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A photo synced from the phone: an Extra Photo or Growth Timeline photo of a plant, or a Progress
 * Photo of a zone (see the phone app's GardenSyncClient "photos"). Only Dropbox-uploaded photos ever
 * sync, so [uri] is always a viewable https link. Read-only here, like everything else in this app.
 */
data class GardenPhoto(
    val id: String,
    val kind: String, // "extra" | "progress" | "growth"
    val plantId: String,
    val location: String,
    val uri: String,
    val label: String,
    val takenAt: Long,
)

fun jsonToPhoto(o: JSONObject): GardenPhoto? {
    val id = o.optString("id", ""); val uri = o.optString("uri", "")
    if (id.isBlank() || uri.isBlank()) return null
    return GardenPhoto(id, o.optString("kind", ""), o.optString("plantId", ""), o.optString("location", ""), uri, o.optString("label", ""), o.optLong("takenAt", 0L))
}

/** Same last-fetch cache idea as the plants cache, so photos show immediately on launch. */
private fun photoCacheFile(context: Context) = java.io.File(context.filesDir, "photos_cache.json")

fun loadCachedPhotos(context: Context): List<GardenPhoto> = try {
    val file = photoCacheFile(context)
    if (!file.exists()) emptyList() else JSONArray(file.readText()).let { arr -> (0 until arr.length()).mapNotNull { jsonToPhoto(arr.getJSONObject(it)) } }
} catch (_: Exception) { emptyList() }

fun saveCachedPhotos(context: Context, photos: List<GardenPhoto>) {
    val arr = JSONArray()
    photos.forEach { p ->
        arr.put(JSONObject().apply {
            put("id", p.id); put("kind", p.kind); put("plantId", p.plantId); put("location", p.location)
            put("uri", p.uri); put("label", p.label); put("takenAt", p.takenAt)
        })
    }
    photoCacheFile(context).writeText(arr.toString())
}

private val photoDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
private fun PhotoPreview(photo: GardenPhoto, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            AsyncImage(model = photo.uri, contentDescription = photo.label.ifBlank { null }, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A titled, horizontally scrolling row of thumbnails; tap to enlarge. Shows nothing when [photos] is empty. */
@Composable
fun PhotoStrip(title: String, photos: List<GardenPhoto>) {
    if (photos.isEmpty()) return
    var preview by remember { mutableStateOf<GardenPhoto?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("$title (${photos.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            photos.forEach { photo ->
                Column(Modifier.width(120.dp)) {
                    AsyncImage(
                        model = photo.uri, contentDescription = photo.label.ifBlank { null }, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22888888)).clickable { preview = photo }
                    )
                    Text(photo.label.ifBlank { photoDateFormat.format(Date(photo.takenAt)) }, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                }
            }
        }
    }
    preview?.let { PhotoPreview(it) { preview = null } }
}

/** Then-vs-now cross-fade between a zone's progress photos, oldest to newest — mirrors the phone's slider. */
@Composable
private fun ProgressPhotoSlider(photos: List<GardenPhoto>) {
    if (photos.size < 2) return
    val maxIndex = (photos.size - 1).toFloat()
    var position by remember(photos.size) { mutableStateOf(maxIndex) }
    val lower = position.toInt().coerceIn(0, photos.size - 1)
    val upper = (lower + 1).coerceAtMost(photos.size - 1)
    val blend = (position - lower).coerceIn(0f, 1f)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))) {
            AsyncImage(model = photos[lower].uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (upper != lower) {
                AsyncImage(model = photos[upper].uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(blend))
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(value = position, onValueChange = { position = it }, valueRange = 0f..maxIndex)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(photoDateFormat.format(Date(photos.first().takenAt)), fontSize = 12.sp, color = Color.Gray)
            Text(
                photoDateFormat.format(Date(if (blend < 0.5f) photos[lower].takenAt else photos[upper].takenAt)),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
            )
            Text(photoDateFormat.format(Date(photos.last().takenAt)), fontSize = 12.sp, color = Color.Gray)
        }
    }
}

/** Zones that have progress photos; tapping one opens [ZoneProgressPhotosScreen]. */
@Composable
fun ProgressPhotosScreen(photos: List<GardenPhoto>, onOpenZone: (String) -> Unit, onBack: () -> Unit) {
    val byZone = remember(photos) { photos.filter { it.kind == "progress" && it.location.isNotBlank() }.groupBy { it.location } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Progress photos", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (byZone.isEmpty()) {
            Text("No progress photos yet. Photos appear here once they're uploaded to Dropbox on the phone.", fontSize = 13.sp, color = Color.Gray)
        }
        byZone.keys.sortedBy { it.lowercase() }.forEach { zone ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenZone(zone) }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(zone, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${byZone.getValue(zone).size} ›", fontSize = 13.sp, color = Color.Gray)
            }
            HorizontalDivider(color = Color(0x22888888))
        }
    }
}

@Composable
fun ZoneProgressPhotosScreen(zone: String, photos: List<GardenPhoto>, onBack: () -> Unit) {
    val zonePhotos = remember(photos, zone) { photos.filter { it.kind == "progress" && it.location == zone }.sortedBy { it.takenAt } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(zone, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (zonePhotos.size == 1) Text("Only one photo so far — add another on the phone to compare then vs now.", fontSize = 13.sp, color = Color.Gray)
        ProgressPhotoSlider(zonePhotos)
        Spacer(Modifier.height(12.dp))
        PhotoStrip("All photos", zonePhotos.reversed())
    }
}
