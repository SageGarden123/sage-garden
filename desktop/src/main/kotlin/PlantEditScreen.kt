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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
    onCancel: () -> Unit,
    onViewHistory: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var sci by remember { mutableStateOf(existing?.sci ?: "") }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var qty by remember { mutableStateOf((existing?.qty ?: 1).toString()) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var sun by remember { mutableStateOf(existing?.sun ?: "") }
    var water by remember { mutableStateOf(existing?.water ?: "") }
    var soil by remember { mutableStateOf(existing?.soil ?: "") }
    var soilPh by remember { mutableStateOf(existing?.soilPh ?: "") }
    var source by remember { mutableStateOf(existing?.source ?: "") }
    var datePlanted by remember { mutableStateOf(existing?.date ?: "") }
    var wateringSystem by remember { mutableStateOf(existing?.wateringSystem ?: "") }
    var native by remember { mutableStateOf(existing?.native ?: "") }
    // Mirrors the Android app's "Other" handling: a pollinator value that isn't one of the fixed
    // options is treated as a free-text description entered under "Other", not lost/blanked.
    var pollinatorChoice by remember {
        mutableStateOf(
            when {
                existing == null -> ""
                pollinatorOptions.contains(existing.pollinator) -> existing.pollinator
                existing.pollinator.isNotBlank() -> "Other"
                else -> ""
            }
        )
    }
    var pollinatorOther by remember {
        mutableStateOf(if (existing != null && !pollinatorOptions.contains(existing.pollinator)) existing.pollinator else "")
    }
    var frost by remember { mutableStateOf(existing?.frost ?: "") }
    var isIndoor by remember { mutableStateOf(existing?.isIndoor ?: false) }
    var manualWateringOnly by remember { mutableStateOf(existing?.manualWateringOnly ?: false) }

    var wateringFrequency by remember { mutableStateOf(existing?.wateringFrequencyDays?.toString() ?: "") }
    var lastWatered by remember { mutableStateOf(millisToDateString(existing?.lastWateredDate)) }
    var summerWateringFrequency by remember { mutableStateOf(existing?.summerWateringFrequencyDays?.toString() ?: "") }
    var winterWateringFrequency by remember { mutableStateOf(existing?.winterWateringFrequencyDays?.toString() ?: "") }
    var fertiliseFrequency by remember { mutableStateOf(existing?.fertiliseFrequencyDays?.toString() ?: "") }
    var lastFertilised by remember { mutableStateOf(millisToDateString(existing?.lastFertilisedDate)) }
    var pruneFrequency by remember { mutableStateOf(existing?.pruneFrequencyDays?.toString() ?: "") }
    var lastPruned by remember { mutableStateOf(millisToDateString(existing?.lastPrunedDate)) }
    var feedFrequency by remember { mutableStateOf(existing?.feedFrequencyDays?.toString() ?: "") }
    var lastFed by remember { mutableStateOf(millisToDateString(existing?.lastFedDate)) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPhotoPreview by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (existing == null) "Add plant" else "Edit plant", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (existing != null && onViewHistory != null) {
                TextButton(onClick = onViewHistory) { Text("📋 Care history") }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (existing?.photoUri != null) {
            PlantThumbnail(existing.photoUri, existing.photoThumbnailBase64, size = 96.dp, onClick = { showPhotoPreview = true })
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

        DropdownField("Category", categoryOptions, category, { category = it }, "What kind of plant this is")
        Spacer(Modifier.height(14.dp))
        DropdownField("Sun", sunOptions, sun, { sun = it }, "Optimal sunlight conditions for your plant")
        Spacer(Modifier.height(14.dp))
        DropdownField("Water", waterOptions, water, { water = it }, "Optimal watering conditions for your plant")
        Spacer(Modifier.height(14.dp))
        DropdownField("Soil", soilOptions, soil, { soil = it }, "Optimal soil conditions for your plant")
        Spacer(Modifier.height(14.dp))
        DropdownField("Soil pH", soilPhOptions, soilPh, { soilPh = it }, "How acidic or alkaline this plant's soil should be")
        Spacer(Modifier.height(16.dp))

        DropdownField("Native / Exotic", nativeOptions, native, { native = it })
        Spacer(Modifier.height(14.dp))

        DropdownField("Pollinator-friendly?", pollinatorOptions, pollinatorChoice, { pollinatorChoice = it })
        if (pollinatorChoice == "Other") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pollinatorOther, onValueChange = { pollinatorOther = it },
                label = { Text("Describe pollinator-friendliness") }, modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))

        DropdownField("Frost tolerance", frostOptions, frost, { frost = it })
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Source (e.g. nursery)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = datePlanted, onValueChange = { datePlanted = it },
            label = { Text("Date planted (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = wateringSystem, onValueChange = { wateringSystem = it }, label = { Text("Watering system") }, modifier = Modifier.fillMaxWidth())
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
        Text("Seasonal watering overrides (leave blank to skip)", fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = summerWateringFrequency, onValueChange = { summerWateringFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Summer (Dec/Jan/Feb) — every N days") }, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = winterWateringFrequency, onValueChange = { winterWateringFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Winter (Jun/Jul/Aug) — every N days") }, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))

        CareFrequencyRow("Fertilising", fertiliseFrequency, { fertiliseFrequency = it }, lastFertilised) { lastFertilised = it }
        CareFrequencyRow("Pruning", pruneFrequency, { pruneFrequency = it }, lastPruned) { lastPruned = it }
        CareFrequencyRow("Feeding", feedFrequency, { feedFrequency = it }, lastFed) { lastFed = it }

        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val finalPollinator = if (pollinatorChoice == "Other") pollinatorOther else pollinatorChoice
                val plant = (existing ?: Plant()).copy(
                    name = name, sci = sci, location = location,
                    qty = qty.toIntOrNull() ?: 1, notes = notes,
                    category = category, sun = sun, water = water, soil = soil, soilPh = soilPh,
                    source = source, date = datePlanted, wateringSystem = wateringSystem,
                    native = native, pollinator = finalPollinator, frost = frost,
                    isIndoor = isIndoor, manualWateringOnly = manualWateringOnly,
                    wateringFrequencyDays = wateringFrequency.toIntOrNull(),
                    lastWateredDate = dateStringToMillis(lastWatered),
                    summerWateringFrequencyDays = summerWateringFrequency.toIntOrNull(),
                    winterWateringFrequencyDays = winterWateringFrequency.toIntOrNull(),
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
        PhotoPreviewDialog(existingPhotoUri, existing.photoThumbnailBase64, onDismiss = { showPhotoPreview = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit, helperText: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Pick an option") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText = helperText?.let { { Text(it, fontSize = 11.sp) } },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
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
