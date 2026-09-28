@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.material3.MaterialTheme

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.maps.android.compose.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// IRRIGATION TAB
// ============================================================================
// Shows every watering event (location, start, end, duration), with filters
// by zone and by date. Data comes from Room (populated by WateringZoneViewModel
// syncs against the Tuya Cloud API), and is separately backed up to an
// irrigation_log.csv in the user's chosen photo storage location since Tuya
// only retains ~7 days of logs at any given moment.

/** [days] uses 1=Monday..7=Sunday, matching the format manual schedule entries are stored in. */
fun formatTuyaTimerDays(days: Set<Int>): String {
    if (days.isEmpty()) return "One-time (no repeat days set)"
    if (days.size == 7) return "Daily"
    val labels = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
    return days.sorted().mapNotNull { labels[it] }.joinToString(", ")
}

/** 7-char '0'/'1' string, Monday..Sunday — same format Tuya's own "loops" field uses, so a manual
 * entry and a fetched Tuya timer can share [formatTuyaTimerDays] for display. */
fun daysToLoopString(days: Set<Int>): String = (1..7).joinToString("") { if (it in days) "1" else "0" }
fun loopStringToDays(loops: String): Set<Int> = loops.mapIndexedNotNull { idx, c -> if (c == '1') idx + 1 else null }.toSet()

fun formatDurationMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** [minutesSinceMidnight] (0..1439) as a 12-hour clock time, e.g. "6:00 AM" — matches the AM/PM
 * convention already used for actual watering-history timestamps elsewhere on this screen. */
fun formatStartTime(minutesSinceMidnight: Int): String {
    val h24 = (minutesSinceMidnight / 60) % 24
    val m = minutesSinceMidnight % 60
    val amPm = if (h24 < 12) "AM" else "PM"
    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
    return String.format("%d:%02d %s", h12, m, amPm)
}

/** Compact multi-select day-of-week row (1=Monday..7=Sunday) — tap a day to toggle it. */
@Composable
fun DayOfWeekPicker(selectedDays: Set<Int>, onToggleDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 7 to "Su")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { (day, label) ->
            val selected = day in selectedDays
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggleDay(day) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A single scrolling "wheel" of numbers that snaps to whichever value sits in the centre row —
 * the building block for [DurationWheelPicker] below, styled after iOS's Timer duration picker.
 * Untested on a real device (no way to verify scroll/snap feel or performance here) — worth a
 * close look on first use.
 */
@Composable
fun NumberWheel(range: IntRange, selected: Int, onSelectedChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val itemHeight = 40.dp
    val visibleItems = 5 // odd, so exactly one row sits dead-centre
    val padItems = visibleItems / 2
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selected - range.first).coerceIn(0, range.last - range.first))
    val flingBehavior = rememberSnapFlingBehavior(listState)

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val centered = info.visibleItemsInfo.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }
            centered?.let { item ->
                val value = (range.first + item.index - padItems).coerceIn(range.first, range.last)
                if (value != selected) onSelectedChange(value)
            }
        }
    }

    Box(modifier = modifier.height(itemHeight * visibleItems), contentAlignment = Alignment.Center) {
        LazyColumn(state = listState, flingBehavior = flingBehavior, modifier = Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            items(padItems) { Spacer(Modifier.height(itemHeight)) }
            items(range.last - range.first + 1) { i ->
                val value = range.first + i
                Box(Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "%02d".format(value),
                        fontSize = if (value == selected) 20.sp else 16.sp,
                        color = if (value == selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            items(padItems) { Spacer(Modifier.height(itemHeight)) }
        }
        Box(
            Modifier.fillMaxWidth().height(itemHeight).align(Alignment.Center)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
        )
    }
}

/** Hour/minute duration picker, two [NumberWheel]s side by side (0-5h, 0-59m). */
@Composable
fun DurationWheelPicker(totalMinutes: Int, onTotalMinutesChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        NumberWheel(range = 0..5, selected = hours, onSelectedChange = { onTotalMinutesChange(it * 60 + minutes) }, modifier = Modifier.width(56.dp))
        Text("hr", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        NumberWheel(range = 0..59, selected = minutes, onSelectedChange = { onTotalMinutesChange(hours * 60 + it) }, modifier = Modifier.width(56.dp))
        Text("min", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrrigationScreen(wateringEvents: List<WateringEvent>, plants: List<PlantEntity>, onPlantClick: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zoneFilter by remember { mutableStateOf("All") }
    var dateFilter by remember { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    val zones = remember(wateringEvents) { listOf("All") + wateringEvents.map { it.zone }.distinct().sorted() }
    val sdfDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    val now = remember { System.currentTimeMillis() }
    val statused = remember(plants, now) {
        plants.mapNotNull { p -> computeWateringStatus(p, now)?.let { p to it } }
    }
    // +1 day then -1ms: matches the truncated-days math computeWateringStatus's label uses ("Due in
    // 3 day(s)" covers anything from 72h up to just under 96h away), so a plant the app itself labels
    // "due in 3 days" doesn't get excluded by an exact-hours cutoff a few hours short of it.
    val cutoff = now + 4 * 86_400_000L - 1
    val dueOrOverdue = remember(statused, now) {
        statused.filter { (_, status) -> status.nextDueMillis != null && status.nextDueMillis <= cutoff }
            .sortedBy { (_, status) -> status.sortKey() }
    }
    val unscheduled = remember(statused) {
        statused.filter { (_, status) -> status.nextDueMillis == null }
    }

    val filtered = wateringEvents.filter { e ->
        (zoneFilter == "All" || e.zone == zoneFilter) &&
                (dateFilter.isBlank() || sdfDate.format(Date(e.startTime)) == dateFilter)
    }.sortedByDescending { it.startTime }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).imePadding()) {
        Text("Irrigation", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(12.dp))

        ExpandableSection(title = "Water now/soon (${dueOrOverdue.size})", initiallyExpanded = true) {
            if (dueOrOverdue.isEmpty()) {
                Text("Nothing due within the next 3 days.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                dueOrOverdue.forEach { (plant, status) ->
                    val overdue = status.nextDueMillis!! <= now
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onPlantClick(plant.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    plant.location.ifBlank { "No location" },
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                status.label, fontSize = 12.sp,
                                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (unscheduled.isNotEmpty()) {
            Text("Unscheduled (never watered)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                unscheduled.forEach { (plant, _) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onPlantClick(plant.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    plant.location.ifBlank { "No location" },
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("Never watered", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (FeatureVisibility.shouldShow(context, Feature.COST_WATER_TRACKING)) {
        ExpandableSection(title = "Water usage & cost (estimated)") {
            val flowRateViewModel: WaterFlowRateViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            )
            val flowRates by flowRateViewModel.flowRates.collectAsState()
            val flowRateByKey = remember(flowRates) { flowRates.associateBy { it.zone to it.outlet } }

            var waterRate by remember { mutableStateOf(getWaterRatePerKiloliter(context)) }
            var waterRateText by remember { mutableStateOf(if (waterRate > 0) waterRate.toString() else "") }

            Text(
                "Estimated from your logged watering durations and a flow rate you calibrate per zone/outlet — not a metered reading.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = waterRateText,
                onValueChange = { new ->
                    waterRateText = new.filter { it.isDigit() || it == '.' }
                    waterRateText.toDoubleOrNull()?.let { waterRate = it; setWaterRatePerKiloliter(context, it) }
                },
                label = { Text("Water rate (\$ per kL)") },
                supportingText = {
                    Text(
                        "What your water utility charges per 1,000 litres (1 kilolitre) — check a recent water bill, usually shown as \"\$/kL\" or \"\$/1000L\".",
                        fontSize = 11.sp
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))

            val zoneOutlets = remember(wateringEvents) {
                wateringEvents.map { it.zone to it.outlet }.distinct().sortedBy { it.first + it.second }
            }
            Text("Flow rate calibration", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "For each zone/outlet below: put a 1-litre container under it, run the water, time how many seconds it takes to fill, then enter that number and tap Save. This converts to a flow rate (litres/minute) used to turn logged watering durations into litres used.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (zoneOutlets.isEmpty()) {
                Text("No watering events logged yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                zoneOutlets.forEach { (zone, outlet) ->
                    val existing = flowRateByKey[zone to outlet]
                    var secondsText by remember(zone, outlet) {
                        mutableStateOf(existing?.let { "%.1f".format(60.0 / it.litersPerMinute) } ?: "")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("$zone — outlet $outlet", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (existing != null) {
                                Text("${"%.2f".format(existing.litersPerMinute)} L/min", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("Not calibrated", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedTextField(
                            value = secondsText,
                            onValueChange = { new -> secondsText = new.filter { it.isDigit() || it == '.' } },
                            label = { Text("Secs/1L") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(100.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            secondsText.toDoubleOrNull()?.takeIf { it > 0 }?.let { seconds ->
                                flowRateViewModel.save(zone, outlet, 60.0 / seconds)
                            }
                        }) { Text("Save") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            val monthStart = remember {
                java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            val monthEvents = remember(wateringEvents, monthStart) { wateringEvents.filter { it.startTime >= monthStart } }
            val calibratedEvents = remember(monthEvents, flowRateByKey) { monthEvents.filter { flowRateByKey.containsKey(it.zone to it.outlet) } }
            val uncalibratedCount = monthEvents.size - calibratedEvents.size
            val totalLiters = calibratedEvents.sumOf { e -> e.durationMinutes * (flowRateByKey[e.zone to e.outlet]?.litersPerMinute ?: 0.0) }
            val totalCost = totalLiters / 1000.0 * waterRate

            val allTimeCalibrated = remember(wateringEvents, flowRateByKey) { wateringEvents.filter { flowRateByKey.containsKey(it.zone to it.outlet) } }
            val allTimeLiters = allTimeCalibrated.sumOf { e -> e.durationMinutes * (flowRateByKey[e.zone to e.outlet]?.litersPerMinute ?: 0.0) }
            val allTimeCost = allTimeLiters / 1000.0 * waterRate

            Text("This month so far", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("${"%.0f".format(totalLiters)} L", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (waterRate > 0) {
                Text("≈ \$${"%.2f".format(totalCost)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Enter a water rate above to see an estimated cost.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                monthEvents.isEmpty() && flowRates.isEmpty() -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No zones calibrated yet — enter a flow rate above for at least one zone/outlet.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                monthEvents.isEmpty() -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No watering events logged since the start of this month — that's why this reads 0, not a calibration problem. See \"All time\" below.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uncalibratedCount > 0 -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$uncalibratedCount event(s) this month excluded — calibrate the zone/outlet above to include them.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val byZone = calibratedEvents.groupBy { it.zone }
            if (byZone.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                byZone.forEach { (zone, zoneEvents) ->
                    val liters = zoneEvents.sumOf { e -> e.durationMinutes * (flowRateByKey[e.zone to e.outlet]?.litersPerMinute ?: 0.0) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(zone, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${"%.0f".format(liters)} L", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("All time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("${"%.0f".format(allTimeLiters)} L", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (waterRate > 0) {
                Text("≈ \$${"%.2f".format(allTimeCost)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        }
        Spacer(Modifier.height(16.dp))

        if (TuyaZoneMappingState.mappings.isNotEmpty()) {
        ExpandableSection(title = "My watering schedule (manual reference)", initiallyExpanded = false) {
            Text(
                "Your own record of what days/times each zone runs — entered by hand, purely for your reference. Doesn't read from or write to Tuya.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            val scheduleGardenId = remember { effectiveGardenId(context) }
            val scheduleViewModel: ManualZoneScheduleViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            )
            var addingForZone by remember { mutableStateOf<String?>(null) }
            var editingEntry by remember { mutableStateOf<ManualZoneScheduleEntity?>(null) }

            TuyaZoneMappingState.mappings.forEachIndexed { index, mapping ->
                val entries by remember(mapping.zone) { scheduleViewModel.getForZone(mapping.zone, scheduleGardenId) }.collectAsState()
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(mapping.zone, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { addingForZone = mapping.zone }) { Text("+ Add", fontSize = 12.sp) }
                    }
                    if (entries.isEmpty()) {
                        Text("No schedule entered yet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        entries.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatTuyaTimerDays(loopStringToDays(entry.daysOfWeek)), fontSize = 12.sp)
                                    Text(
                                        "${formatStartTime(entry.startTimeMinutes)} • ${formatDurationMinutes(entry.durationMinutes)}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { editingEntry = entry }) { Text("Edit", fontSize = 11.sp) }
                                TextButton(onClick = { scheduleViewModel.delete(entry.id) }) { Text("Delete", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                if (index < TuyaZoneMappingState.mappings.lastIndex) HorizontalDivider()
            }

            val dialogZone = addingForZone ?: editingEntry?.zone
            if (dialogZone != null) {
                val zone = dialogZone
                val existing = editingEntry
                fun closeDialog() { addingForZone = null; editingEntry = null }
                var selectedDays by remember(existing) { mutableStateOf(existing?.let { loopStringToDays(it.daysOfWeek) } ?: setOf()) }
                var startHour by remember(existing) { mutableStateOf(existing?.let { it.startTimeMinutes / 60 } ?: 6) }
                var startMinute by remember(existing) { mutableStateOf(existing?.let { it.startTimeMinutes % 60 } ?: 0) }
                var showStartTimeDialog by remember { mutableStateOf(false) }
                var totalMinutes by remember(existing) { mutableStateOf(existing?.durationMinutes ?: 15) }
                AlertDialog(
                    onDismissRequest = { closeDialog() },
                    title = { Text(if (existing != null) "Edit schedule for \"$zone\"" else "Add schedule for \"$zone\"") },
                    text = {
                        Column {
                            Text("Days", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            DayOfWeekPicker(
                                selectedDays = selectedDays,
                                onToggleDay = { day -> selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day }
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Start time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { showStartTimeDialog = true }) {
                                Text(formatStartTime(startHour * 60 + startMinute))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Duration", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            DurationWheelPicker(totalMinutes = totalMinutes, onTotalMinutesChange = { totalMinutes = it }, modifier = Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (existing != null) {
                                    scheduleViewModel.update(existing, daysToLoopString(selectedDays), startHour * 60 + startMinute, totalMinutes)
                                } else {
                                    scheduleViewModel.add(zone, scheduleGardenId, daysToLoopString(selectedDays), startHour * 60 + startMinute, totalMinutes)
                                }
                                closeDialog()
                            },
                            enabled = selectedDays.isNotEmpty() && totalMinutes > 0
                        ) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { closeDialog() }) { Text("Cancel") } }
                )
                if (showStartTimeDialog) {
                    val timeState = rememberTimePickerState(initialHour = startHour, initialMinute = startMinute)
                    Dialog(onDismissRequest = { showStartTimeDialog = false }) {
                        Card {
                            Column(Modifier.padding(16.dp)) {
                                TimePicker(state = timeState)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { showStartTimeDialog = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                    Button(
                                        onClick = {
                                            startHour = timeState.hour; startMinute = timeState.minute
                                            showStartTimeDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Set") }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        }

        if (FeatureVisibility.shouldShow(context, Feature.WATERING_HISTORY)) {
        ExpandableSection(title = "Watering history (${filtered.size})") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    DropdownField(label = "Zone", options = zones, selected = zoneFilter, onSelect = { zoneFilter = it })
                }
                Box(Modifier.weight(1f)) { DatePickerField("Date", dateFilter, { dateFilter = it }) }
            }
            if (dateFilter.isNotBlank()) {
                TextButton(onClick = { dateFilter = "" }) { Text("Clear date filter") }
            }
            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                val irrigationSystemName = when (GardenSettings.active(context).irrigationSystem) {
                    IrrigationSystem.RACHIO -> "Rachio"
                    else -> "Tuya"
                }
                Text("No irrigation data yet — connect $irrigationSystemName zones and sync in Help.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                filtered.forEach { e ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(e.zone, fontWeight = FontWeight.SemiBold)
                            val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", locale)
                            val end = e.startTime + e.durationMinutes * 60_000L
                            Text("Start: ${sdf.format(Date(e.startTime))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("End: ${sdf.format(Date(end))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Duration: ${e.durationMinutes} min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(20.dp))
    }
}
