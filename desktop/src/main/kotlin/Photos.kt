import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A photo record synced from the phone (see the Android app's GardenSyncClient "photos"): an Extra
 * Photo or Growth Timeline photo of a plant, or a Progress Photo of a zone. Only Dropbox-uploaded
 * photos ever sync, so [uri] is always a viewable https link. Read-only on desktop.
 */
data class GardenPhoto(
    val id: String,
    val kind: String, // "extra" | "progress" | "growth"
    val plantId: String = "",
    val location: String = "",
    val uri: String,
    val label: String = "",
    val takenAt: Long = 0L,
    val updatedAt: Long = 0L,
)

private val photoDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
private fun PhotoThumb(photo: GardenPhoto, size: Int, onClick: () -> Unit) {
    val bitmap = rememberNetworkImage(photo.uri)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = photo.label.ifBlank { null }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A titled, horizontally scrolling row of photo thumbnails (with date/label); click to enlarge. Shows nothing when [photos] is empty. */
@Composable
fun PhotoStrip(title: String, photos: List<GardenPhoto>) {
    if (photos.isEmpty()) return
    var preview by remember { mutableStateOf<GardenPhoto?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("$title (${photos.size})", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            photos.forEach { photo ->
                Column(Modifier.width(110.dp)) {
                    PhotoThumb(photo, 110) { preview = photo }
                    Text(
                        photo.label.ifBlank { photoDateFormat.format(Date(photo.takenAt)) },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
    }
    preview?.let { PhotoPreviewDialog(it.uri, onDismiss = { preview = null }) }
}

/** Then-vs-now cross-fade between a zone's progress photos, oldest to newest — mirrors the phone's LocationPhotoSlider. */
@Composable
fun ProgressPhotoSlider(photos: List<GardenPhoto>, modifier: Modifier = Modifier) {
    if (photos.size < 2) return
    val maxIndex = (photos.size - 1).toFloat()
    var position by remember(photos.size) { mutableStateOf(maxIndex) }
    val lower = position.toInt().coerceIn(0, photos.size - 1)
    val upper = (lower + 1).coerceAtMost(photos.size - 1)
    val blend = (position - lower).coerceIn(0f, 1f)
    val lowerBitmap = rememberNetworkImage(photos[lower].uri)
    val upperBitmap = rememberNetworkImage(photos[upper].uri)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (lowerBitmap == null && upperBitmap == null) CircularProgressIndicator()
            lowerBitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (upper != lower) upperBitmap?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(blend))
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(value = position, onValueChange = { position = it }, valueRange = 0f..maxIndex)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(photoDateFormat.format(Date(photos.first().takenAt)), style = MaterialTheme.typography.labelMedium, color = muted)
            Text(
                photoDateFormat.format(Date(if (blend < 0.5f) photos[lower].takenAt else photos[upper].takenAt)),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
            )
            Text(photoDateFormat.format(Date(photos.last().takenAt)), style = MaterialTheme.typography.labelMedium, color = muted)
        }
    }
}

/**
 * Progress photos by zone: zones on the left (those with photos first), the selected zone's
 * before/after slider and every photo on the right. Read-only — photos are added on the phone.
 */
@Composable
fun ProgressPhotosScreen(photos: List<GardenPhoto>, zones: List<String>) {
    val progress = remember(photos) { photos.filter { it.kind == "progress" } }
    val byZone = remember(progress) { progress.groupBy { it.location }.mapValues { (_, v) -> v.sortedBy { it.takenAt } } }
    val allZones = remember(byZone, zones) {
        (byZone.keys.sortedBy { it.lowercase() } + zones.filter { it !in byZone }.sortedBy { it.lowercase() }).filter { it.isNotBlank() }
    }
    var selected by remember(allZones) { mutableStateOf(allZones.firstOrNull()) }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Progress photos", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Photos of each zone over time, synced from your phone. Only photos uploaded to Dropbox on the phone appear here.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (allZones.isEmpty()) {
            Text("No zones yet.", color = cs.onSurfaceVariant)
            return@Column
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                allZones.forEach { zone ->
                    val count = byZone[zone]?.size ?: 0
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (zone == selected) cs.secondaryContainer else cs.surface)
                            .clickable { selected = zone }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(zone, modifier = Modifier.weight(1f), color = if (count == 0) cs.onSurfaceVariant else cs.onSurface)
                        Text("$count", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            val zonePhotos = byZone[selected].orEmpty()
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text(selected ?: "", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                when {
                    zonePhotos.isEmpty() -> Text("No progress photos for this zone yet.", color = cs.onSurfaceVariant)
                    zonePhotos.size == 1 -> Text("Add a second photo of this zone on the phone to compare then vs now.", color = cs.onSurfaceVariant)
                    else -> ProgressPhotoSlider(zonePhotos, Modifier.widthIn(max = 720.dp))
                }
                if (zonePhotos.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = cs.outlineVariant)
                    // Newest first, matching the phone's list.
                    PhotoStrip("All photos", zonePhotos.reversed())
                }
            }
        }
    }
}
