package com.example.sagegarden

import androidx.compose.ui.res.stringResource

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*

import androidx.compose.material3.MaterialTheme

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CareHistoryScreen(plantId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val careViewModel: CareLogViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    // Resolved from the plant's own record, not effectiveGardenId(context) — see the identical
    // comment in GrowthTimelineScreen. This screen can be reached (via FormScreen's "View care
    // history") for a plant belonging to a garden other than whichever one is active in the UI.
    var gardenId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(plantId) {
        gardenId = resolvePlantById(context, plantId)?.gardenId ?: effectiveGardenId(context)
    }
    val canEdit = remember(ActiveGardenState.activeGardenId, gardenId) { gardenId?.let { hasWriteAccessToGarden(context, it) } ?: false }
    val entries by remember(plantId, gardenId) { careViewModel.getForPlant(plantId, gardenId ?: "") }.collectAsState()
    var pendingLogType by remember { mutableStateOf<String?>(null) }
    var logDate by remember { mutableStateOf("") }
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.care_back)) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.care_watering_fertilising_feeding_pruning_history), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(14.dp))

        if (canEdit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pendingLogType = "watering"; logDate = "" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.care_log_watering), fontSize = 12.sp) }
            Button(onClick = { pendingLogType = "fertilise"; logDate = "" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.care_log_fertilising), fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pendingLogType = "feed"; logDate = "" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.care_log_feeding), fontSize = 12.sp) }
            Button(onClick = { pendingLogType = "prune"; logDate = "" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.care_log_pruning), fontSize = 12.sp) }
        }
        Spacer(Modifier.height(20.dp))
        }

        if (entries.isEmpty()) {
            Text(stringResource(R.string.care_no_entries_yet_log_watering_fertilising), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        } else {
            entries.forEach { entry ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(careTypeIcon(entry.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(careTypeLabel(entry.type), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(sdf.format(Date(entry.date)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canEdit) {
                            TextButton(onClick = { careViewModel.delete(entry.id) }) { Text(stringResource(R.string.care_delete), fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    pendingLogType?.let { type ->
        AlertDialog(
            onDismissRequest = { pendingLogType = null },
            title = { Text(stringResource(R.string.care_log, careTypeLabel(type).lowercase())) },
            text = {
                Column {
                    Text(stringResource(R.string.care_pick_the_date_defaults_to_today), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    DatePickerField("Date", logDate, { logDate = it }, restrictToPastOrToday = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateStringToMillis(logDate) ?: System.currentTimeMillis()
                    careViewModel.logCare(plantId, gardenId ?: effectiveGardenId(context), type, millis)
                    pendingLogType = null
                }) { Text(stringResource(R.string.care_save)) }
            },
            dismissButton = { TextButton(onClick = { pendingLogType = null }) { Text(stringResource(R.string.care_cancel)) } }
        )
    }
}

fun careTypeIcon(type: String) = when (type) {
    "watering" -> Icons.Outlined.WaterDrop
    "fertilise" -> Icons.Outlined.Grass
    "feed" -> Icons.Outlined.Restaurant
    else -> Icons.Outlined.ContentCut
}

fun careTypeLabel(type: String) = when (type) {
    "watering" -> "Watered"
    "fertilise" -> "Fertilised"
    "feed" -> "Fed"
    else -> "Pruned"
}