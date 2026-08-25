import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private fun millisToDateString(millis: Long?): String = millis?.let { dateFormat.format(Date(it)) } ?: ""
private fun dateStringToMillis(s: String): Long? = runCatching { dateFormat.parse(s)?.time }.getOrNull()

@Composable
fun PlantEditScreen(
    existing: Plant?,
    onSave: (Plant) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var sci by remember { mutableStateOf(existing?.sci ?: "") }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var qty by remember { mutableStateOf((existing?.qty ?: 1).toString()) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var native by remember { mutableStateOf(existing?.native ?: "") }
    var pollinator by remember { mutableStateOf(existing?.pollinator ?: "") }
    var frost by remember { mutableStateOf(existing?.frost ?: "") }
    var isIndoor by remember { mutableStateOf(existing?.isIndoor ?: false) }
    var manualWateringOnly by remember { mutableStateOf(existing?.manualWateringOnly ?: false) }

    var wateringFrequency by remember { mutableStateOf(existing?.wateringFrequencyDays?.toString() ?: "") }
    var lastWatered by remember { mutableStateOf(millisToDateString(existing?.lastWateredDate)) }
    var fertiliseFrequency by remember { mutableStateOf(existing?.fertiliseFrequencyDays?.toString() ?: "") }
    var lastFertilised by remember { mutableStateOf(millisToDateString(existing?.lastFertilisedDate)) }
    var pruneFrequency by remember { mutableStateOf(existing?.pruneFrequencyDays?.toString() ?: "") }
    var lastPruned by remember { mutableStateOf(millisToDateString(existing?.lastPrunedDate)) }
    var feedFrequency by remember { mutableStateOf(existing?.feedFrequencyDays?.toString() ?: "") }
    var lastFed by remember { mutableStateOf(millisToDateString(existing?.lastFedDate)) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPhotoPreview by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(if (existing == null) "Add plant" else "Edit plant", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        if (existing?.photoUri != null) {
            PlantThumbnail(existing.photoUri, size = 96.dp, onClick = { showPhotoPreview = true })
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = sci, onValueChange = { sci = it }, label = { Text("Scientific name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Location") }, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() } },
                label = { Text("Quantity") }, modifier = Modifier.width(140.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        Text("Native / Exotic", fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("", "Native", "Exotic").forEach { opt ->
                FilterChip(selected = native == opt, onClick = { native = opt }, label = { Text(opt.ifBlank { "Unset" }, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(14.dp))

        Text("Pollinator-friendly", fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("", "Yes", "No").forEach { opt ->
                FilterChip(selected = pollinator == opt, onClick = { pollinator = opt }, label = { Text(opt.ifBlank { "Unset" }, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(14.dp))

        Text("Frost tolerance", fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("", "Hardy", "Half-hardy", "Tender").forEach { opt ->
                FilterChip(selected = frost == opt, onClick = { frost = opt }, label = { Text(opt.ifBlank { "Unset" }, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isIndoor, onCheckedChange = { isIndoor = it })
            Text("Indoor plant", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = manualWateringOnly, onCheckedChange = { manualWateringOnly = it })
            Text("Requires manual watering (not on an irrigation path)", fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        CareFrequencyRow("Watering", wateringFrequency, { wateringFrequency = it }, lastWatered) { lastWatered = it }
        CareFrequencyRow("Fertilising", fertiliseFrequency, { fertiliseFrequency = it }, lastFertilised) { lastFertilised = it }
        CareFrequencyRow("Pruning", pruneFrequency, { pruneFrequency = it }, lastPruned) { lastPruned = it }
        CareFrequencyRow("Feeding", feedFrequency, { feedFrequency = it }, lastFed) { lastFed = it }

        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val plant = (existing ?: Plant()).copy(
                    name = name, sci = sci, location = location,
                    qty = qty.toIntOrNull() ?: 1, notes = notes,
                    native = native, pollinator = pollinator, frost = frost,
                    isIndoor = isIndoor, manualWateringOnly = manualWateringOnly,
                    wateringFrequencyDays = wateringFrequency.toIntOrNull(),
                    lastWateredDate = dateStringToMillis(lastWatered),
                    fertiliseFrequencyDays = fertiliseFrequency.toIntOrNull(),
                    lastFertilisedDate = dateStringToMillis(lastFertilised),
                    pruneFrequencyDays = pruneFrequency.toIntOrNull(),
                    lastPrunedDate = dateStringToMillis(lastPruned),
                    feedFrequencyDays = feedFrequency.toIntOrNull(),
                    lastFedDate = dateStringToMillis(lastFed)
                )
                onSave(plant)
            }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            if (onDelete != null) {
                Spacer(Modifier.width(20.dp))
                TextButton(onClick = { showDeleteConfirm = true }) { Text("Delete plant") }
            }
        }

        if (showDeleteConfirm && onDelete != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Delete this plant? This can't be undone.", fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onDelete) { Text("Confirm delete") }
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    val existingPhotoUri = existing?.photoUri
    if (showPhotoPreview && existingPhotoUri != null) {
        PhotoPreviewDialog(existingPhotoUri, onDismiss = { showPhotoPreview = false })
    }
}

@Composable
private fun CareFrequencyRow(
    label: String,
    frequency: String, onFrequencyChange: (String) -> Unit,
    lastDone: String, onLastDoneChange: (String) -> Unit
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = frequency, onValueChange = { onFrequencyChange(it.filter { c -> c.isDigit() }) },
            label = { Text("Every N days") }, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = lastDone, onValueChange = onLastDoneChange,
            label = { Text("Last done (yyyy-MM-dd)") }, modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(16.dp))
}
