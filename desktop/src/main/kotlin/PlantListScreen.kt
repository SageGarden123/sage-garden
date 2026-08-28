import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlantThumbnail(photoUri: String?, photoThumbnailBase64: String? = null, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: (() -> Unit)? = null) {
    val bitmap = rememberPlantPhoto(photoUri, photoThumbnailBase64)
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE3DDCF))
            .let { if (bitmap != null && onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("🌿")
        }
    }
}

@Composable
fun PlantListScreen(
    plants: List<Plant>,
    onAdd: () -> Unit,
    onEdit: (Plant) -> Unit,
    onHistory: (Plant) -> Unit,
    onLogCare: (Plant, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var previewPlant by remember { mutableStateOf<Plant?>(null) }
    val filtered = plants.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.sci.contains(query, ignoreCase = true) || it.location.contains(query, ignoreCase = true)
    }.sortedBy { it.name.lowercase() }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Plants (${plants.size})", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onAdd) { Text("+ Add plant") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search by name, species or location") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            Text("No plants yet — add your first one above.", fontSize = 13.sp)
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                    items(filtered, key = { it.id }) { plant ->
                        val now = System.currentTimeMillis()
                        val status = computeWateringStatus(plant, now)
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlantThumbnail(plant.photoUri, plant.photoThumbnailBase64, onClick = { previewPlant = plant })
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(plant.name.ifBlank { "(unnamed)" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    val subtitle = listOfNotNull(
                                        plant.sci.ifBlank { null },
                                        plant.location.ifBlank { null }
                                    ).joinToString(" · ")
                                    if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp)
                                    status?.let { Text(it.label, fontSize = 11.sp) }
                                }
                                Row {
                                    TextButton(onClick = { onLogCare(plant, "watering") }) { Text("💧 Log") }
                                    TextButton(onClick = { onHistory(plant) }) { Text("History") }
                                    OutlinedButton(onClick = { onEdit(plant) }) { Text("Edit") }
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(listState)
                )
            }
        }
    }

    previewPlant?.let { p -> p.photoUri?.let { uri -> PhotoPreviewDialog(uri, p.photoThumbnailBase64, onDismiss = { previewPlant = null }) } }
}
