@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dropbox.core.v2.files.WriteMode
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// IRRIGATION CSV PERSISTENCE (local folder or Dropbox — reuses photo storage settings)
// ============================================================================
// Tuya only retains ~7 days of device logs, so each sync's results are merged
// into a running irrigation_log.csv (deduped by zone+outlet+startTime) rather
// than overwritten, so history accumulates indefinitely.

val IRRIGATION_CSV_HEADERS = listOf("Zone", "Outlet", "StartTime", "DurationMinutes", "Source")

fun wateringEventsToCsv(events: List<WateringEvent>): String {
    val sb = StringBuilder()
    sb.append(IRRIGATION_CSV_HEADERS.joinToString(",")).append("\n")
    events.forEach { e ->
        val row = listOf(e.zone, e.outlet, e.startTime.toString(), e.durationMinutes.toString(), e.source)
            .joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        sb.append(row).append("\n")
    }
    return sb.toString()
}

sealed class CsvImportResult<out T> {
    data class Success<T>(val items: List<T>, val skippedRows: Int) : CsvImportResult<T>()
    data class MissingColumns(val missing: List<String>, val expected: List<String>) : CsvImportResult<Nothing>()
    data class EmptyFile(val expected: List<String>) : CsvImportResult<Nothing>()
}

data class CsvImportOutcome(val title: String, val message: String)

/** Case-insensitive, order-independent column lookup — a manually edited or re-saved
 *  CSV often reorders or re-cases headers, and this shouldn't break the import. */
internal fun csvFindValue(headers: List<String>, cells: List<String>, key: String): String? {
    val idx = headers.indexOfFirst { it.equals(key, ignoreCase = true) }
    return if (idx >= 0 && idx < cells.size) cells[idx] else null
}

internal val IRRIGATION_DATE_FORMATS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "dd/MM/yyyy HH:mm:ss",
    "dd/MM/yyyy HH:mm",
    "M/d/yyyy H:mm"
)

/** Accepts either epoch milliseconds or a recognised DateTime string; returns null if neither matches. */
fun parseFlexibleDateTime(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    trimmed.toLongOrNull()?.let { return it }
    for (pattern in IRRIGATION_DATE_FORMATS) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            sdf.isLenient = false
            return sdf.parse(trimmed)?.time
        } catch (_: Exception) { /* try next pattern */ }
    }
    return null
}
fun parseIrrigationCsv(text: String): CsvImportResult<WateringEvent> {
    val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
    val required = listOf("Zone", "StartTime", "DurationMinutes")
    if (lines.isEmpty()) return CsvImportResult.EmptyFile(IRRIGATION_CSV_HEADERS)

    val delimiter = detectCsvDelimiter(lines[0])
    val headers = parseCsvLine(lines[0], delimiter).map { it.trim().trim('"') }
    val missing = required.filter { req -> headers.none { it.equals(req, ignoreCase = true) } }
    if (missing.isNotEmpty()) return CsvImportResult.MissingColumns(missing, IRRIGATION_CSV_HEADERS)
    if (lines.size < 2) return CsvImportResult.Success(emptyList(), 0)

    val out = mutableListOf<WateringEvent>()
    var skipped = 0
    for (i in 1 until lines.size) {
        val cells = parseCsvLine(lines[i], delimiter)   // was: parseCsvLine(lines[i])
        val zone = csvFindValue(headers, cells, "Zone")
        val outlet = csvFindValue(headers, cells, "Outlet") ?: "1"
        val start = csvFindValue(headers, cells, "StartTime")?.let { parseFlexibleDateTime(it) }
        val duration = csvFindValue(headers, cells, "DurationMinutes")?.trim()?.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
        if (zone.isNullOrBlank() || start == null || duration == null) { skipped++; continue }
        out.add(
            WateringEvent(
                id = "$zone-$outlet-$start", zone = zone, outlet = outlet,
                startTime = start, durationMinutes = duration,
                source = csvFindValue(headers, cells, "Source") ?: "Tuya"
            )
        )
    }
    return CsvImportResult.Success(out, skipped)
}

fun csvImportResultToOutcome(result: CsvImportResult<WateringEvent>): CsvImportOutcome = when (result) {
    is CsvImportResult.Success -> CsvImportOutcome(
        "Import complete",
        if (result.skippedRows > 0)
            "Imported ${result.items.size} irrigation event(s). Skipped ${result.skippedRows} row(s) with missing or invalid data."
        else "Imported ${result.items.size} irrigation event(s)."
    )
    is CsvImportResult.MissingColumns -> CsvImportOutcome(
        "Missing column(s)",
        "This file is missing required column(s): ${result.missing.joinToString(", ")}.\n\nExpected columns: ${result.expected.joinToString(", ")}"
    )
    is CsvImportResult.EmptyFile -> CsvImportOutcome(
        "Nothing to import",
        "That file is empty.\n\nExpected columns: ${result.expected.joinToString(", ")}"
    )
}

/**
 * Shared by both the local-file "Choose CSV file" picker and "Choose CSV from Dropbox" — parses
 * [text] as a plant-import CSV and saves each row into the active garden, matching an existing
 * plant by its "Plant ID" column when present (carrying its photo fields forward) or generating a
 * fresh id otherwise.
 */
suspend fun importPlantsCsv(context: Context, text: String, plants: List<PlantEntity>, viewModel: PlantViewModel): CsvImportOutcome {
    val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        return CsvImportOutcome("Nothing to import", "That file is empty.\n\nExpected columns: ${CSV_HEADERS.joinToString(", ")}")
    }
    val headers = lines[0].split(",").map { it.trim().trim('"') }
    if (headers.none { it.equals("Plant", ignoreCase = true) }) {
        return CsvImportOutcome(
            "Missing column",
            "This file is missing a \"Plant\" column (plant name), which is required.\n\nExpected columns: ${CSV_HEADERS.joinToString(", ")}"
        )
    }

    val workingPlants = plants.toMutableList()
    // Every id on the device (every garden, not just the active one) — a fresh id generated here
    // only needs to avoid colliding with the active garden's OWN plants for update-matching
    // purposes (see `existing` below), but avoiding a cross-garden id collision entirely is what
    // actually matters: two gardens' plants share one local table keyed on plain `id`, so reusing
    // another garden's id would silently overwrite that garden's plant (see PlantDao.upsert).
    val allDeviceIds = viewModel.getAllPlantsOnDevice().map { it.id }.toMutableSet()
    val importPrefix = plantIdPrefixForGarden(context, effectiveGardenId(context))
    var imported = 0
    var skipped = 0
    for (i in 1 until lines.size) {
        val cells = parseCsvLine(lines[i])
        val plantName = csvFindValue(headers, cells, "Plant")
        if (plantName.isNullOrBlank()) { skipped++; continue }
        val csvId = csvFindValue(headers, cells, "Plant ID")?.trim()
        val existing = workingPlants.firstOrNull { it.id == csvId }
        val resolvedId = if (!csvId.isNullOrBlank()) csvId else nextPlantId(allDeviceIds, importPrefix)
        allDeviceIds.add(resolvedId)
        val plant = PlantEntity(
            id = resolvedId,
            name = plantName,
            sci = csvFindValue(headers, cells, "Scientific name") ?: "",
            location = csvFindValue(headers, cells, "Location") ?: "",
            sun = csvFindValue(headers, cells, "Sun") ?: "",
            water = csvFindValue(headers, cells, "Water") ?: "",
            soil = csvFindValue(headers, cells, "Soil") ?: "",
            soilPh = csvFindValue(headers, cells, "Soil pH") ?: "",
            category = csvFindValue(headers, cells, "Category") ?: "",
            frost = csvFindValue(headers, cells, "Frost") ?: "",
            native = csvFindValue(headers, cells, "Native/Exotic") ?: "Native (Aus)",
            pollinator = csvFindValue(headers, cells, "Pollinator-Friendly") ?: "",
            source = csvFindValue(headers, cells, "Source") ?: "",
            date = csvFindValue(headers, cells, "Date planted") ?: "",
            qty = csvFindValue(headers, cells, "Amount")?.toIntOrNull() ?: 1,
            notes = csvFindValue(headers, cells, "Notes") ?: "",
            wateringSystem = csvFindValue(headers, cells, "Watering System") ?: "",
            lat = csvFindValue(headers, cells, "Latitude")?.toDoubleOrNull(),
            lng = csvFindValue(headers, cells, "Longitude")?.toDoubleOrNull(),
            // Falls back to whatever's already stored (like the photo fields below) rather than
            // defaulting to false, so importing a CSV from before these two columns existed doesn't
            // silently clear them on an existing plant.
            manualWateringOnly = csvFindValue(headers, cells, "Manual Watering Only")?.equals("Yes", ignoreCase = true)
                ?: existing?.manualWateringOnly ?: false,
            isIndoor = csvFindValue(headers, cells, "Indoor Plant")?.equals("Yes", ignoreCase = true)
                ?: existing?.isIndoor ?: false,
            photoUri = existing?.photoUri,
            photoUris = existing?.photoUris ?: emptyList(),
            photoThumbnailBase64 = existing?.photoThumbnailBase64,
            // CSV is deliberately a lighter species/location catalog — it never carries these
            // fields, so an import must preserve them from the existing record rather than the
            // PlantEntity constructor's defaults, or every plant in the file loses its watering
            // schedule, seasonal overrides, and map position the moment it's re-imported.
            gardenId = existing?.gardenId ?: "",
            mapX = existing?.mapX,
            mapY = existing?.mapY,
            lastWateredDate = existing?.lastWateredDate,
            wateringFrequencyDays = existing?.wateringFrequencyDays,
            summerWateringFrequencyDays = existing?.summerWateringFrequencyDays,
            winterWateringFrequencyDays = existing?.winterWateringFrequencyDays,
            lastFertilisedDate = existing?.lastFertilisedDate,
            fertiliseFrequencyDays = existing?.fertiliseFrequencyDays,
            lastPrunedDate = existing?.lastPrunedDate,
            pruneFrequencyDays = existing?.pruneFrequencyDays,
            lastFedDate = existing?.lastFedDate,
            feedFrequencyDays = existing?.feedFrequencyDays
        )
        viewModel.save(plant)
        workingPlants.removeAll { it.id == resolvedId }
        workingPlants.add(plant)
        imported++
    }
    return CsvImportOutcome(
        "Import complete",
        if (skipped > 0) "Imported $imported plant(s). Skipped $skipped row(s) missing a plant name."
        else "Imported $imported plant(s)."
    )
}

suspend fun saveIrrigationCsvLocal(context: Context, newEvents: List<WateringEvent>): Boolean = withContext(Dispatchers.IO) {
    try {
        val folder = getIrrigationLogFolderUri(context)?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext false
        val existingFile = folder.findFile("irrigation_log.csv")
        val existingText = existingFile?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
        }
        val existingEvents = existingText?.let { text ->
            (parseIrrigationCsv(text) as? CsvImportResult.Success)?.items ?: emptyList()
        } ?: emptyList()
        val merged = (existingEvents + newEvents)
            .associateBy { "${it.zone}|${it.outlet}|${it.startTime}" }
            .values.sortedByDescending { it.startTime }
        val target = existingFile ?: folder.createFile("text/csv", "irrigation_log.csv") ?: return@withContext false
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(wateringEventsToCsv(merged).toByteArray()) }
        true
    } catch (_: Exception) { false }
}

suspend fun saveIrrigationCsvDropbox(context: Context, newEvents: List<WateringEvent>): Boolean = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext false
        val filePath = "${getIrrigationLogDropboxFolderPath(context) ?: ""}/irrigation_log.csv".replace("//", "/")
        val existingText = try {
            val out = java.io.ByteArrayOutputStream()
            client.files().download(filePath).download(out)
            out.toString("UTF-8")
        } catch (_: Exception) { null }
        val existingEvents = existingText?.let { text ->
            (parseIrrigationCsv(text) as? CsvImportResult.Success)?.items ?: emptyList()
        } ?: emptyList()
        val merged = (existingEvents + newEvents)
            .associateBy { "${it.zone}|${it.outlet}|${it.startTime}" }
            .values.sortedByDescending { it.startTime }
        // Same rolling-snapshot intent as the main Dropbox backup — overwrite in place rather than
        // erroring on every sync after the first, since this always re-uploads to the same path.
        client.files().uploadBuilder(filePath).withMode(WriteMode.OVERWRITE).uploadAndFinish(wateringEventsToCsv(merged).toByteArray().inputStream())
        true
    } catch (_: Exception) { false }
}

suspend fun fetchIrrigationCsvFromDropbox(context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext null
        val filePath = "${getIrrigationLogDropboxFolderPath(context) ?: ""}/irrigation_log.csv".replace("//", "/")
        val out = java.io.ByteArrayOutputStream()
        client.files().download(filePath).download(out)
        out.toString("UTF-8")
    } catch (_: Exception) { null }
}
