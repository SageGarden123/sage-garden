package com.example.dansgardenmapper

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
    val entries by remember(plantId) { careViewModel.getForPlant(plantId) }.collectAsState()
    var pendingLogType by remember { mutableStateOf<String?>(null) }
    var logDate by remember { mutableStateOf("") }
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text("Watering, fertilising, feeding & pruning history", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pendingLogType = "watering"; logDate = "" }, modifier = Modifier.weight(1f)) { Text("💧 Log watering", fontSize = 12.sp) }
            Button(onClick = { pendingLogType = "fertilise"; logDate = "" }, modifier = Modifier.weight(1f)) { Text("🌱 Log fertilising", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pendingLogType = "feed"; logDate = "" }, modifier = Modifier.weight(1f)) { Text("🍽️ Log feeding", fontSize = 12.sp) }
            Button(onClick = { pendingLogType = "prune"; logDate = "" }, modifier = Modifier.weight(1f)) { Text("✂️ Log pruning", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(20.dp))

        if (entries.isEmpty()) {
            Text("No entries yet — log watering, fertilising, feeding, or pruning above.", color = Color.Gray, fontSize = 13.sp)
        } else {
            entries.forEach { entry ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(careTypeIcon(entry.type), fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(careTypeLabel(entry.type), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(sdf.format(Date(entry.date)), fontSize = 11.sp, color = Color.Gray)
                        }
                        TextButton(onClick = { careViewModel.delete(entry.id) }) { Text("Delete", fontSize = 11.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    pendingLogType?.let { type ->
        AlertDialog(
            onDismissRequest = { pendingLogType = null },
            title = { Text("Log ${careTypeLabel(type).lowercase()}") },
            text = {
                Column {
                    Text("Pick the date — defaults to today if left blank.", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(10.dp))
                    DatePickerField("Date", logDate, { logDate = it }, restrictToPastOrToday = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateStringToMillis(logDate) ?: System.currentTimeMillis()
                    careViewModel.logCare(plantId, type, millis)
                    pendingLogType = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingLogType = null }) { Text("Cancel") } }
        )
    }
}

fun careTypeIcon(type: String) = when (type) {
    "watering" -> "💧"
    "fertilise" -> "🌱"
    "feed" -> "🍽️"
    else -> "✂️"
}

fun careTypeLabel(type: String) = when (type) {
    "watering" -> "Watered"
    "fertilise" -> "Fertilised"
    "feed" -> "Fed"
    else -> "Pruned"
}