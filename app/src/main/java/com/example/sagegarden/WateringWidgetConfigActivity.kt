package com.example.sagegarden

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WateringWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        com.example.sagegarden.ui.theme.AppearanceState.load(applicationContext)
        setContent {
            com.example.sagegarden.ui.theme.SageGardenTheme {
                WidgetConfigScreen(
                    initialConfig = getWidgetConfig(this, appWidgetId),
                    onSave = { config ->
                        setWidgetConfig(this, appWidgetId, config)
                        scheduleWidgetRefresh(this, appWidgetId, config.intervalDays)
                        lifecycleScope.launch {
                            // Actually pulls fresh data for every known garden before repainting —
                            // previously this only called refreshWateringWidgets(), which just
                            // re-renders whatever was already cached in Room. That meant hitting
                            // Save here could never be the thing that fixes a garden showing empty;
                            // it could only ever reflect a sync that had (or hadn't) already happened
                            // some other way.
                            GardenSyncClient.syncAllKnownGardens(this@WateringWidgetConfigActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigScreen(initialConfig: WidgetConfig, onSave: (WidgetConfig) -> Unit) {
    val context = LocalContext.current
    val maxPlantsOptions = remember { listOf(4, 8, 12, 20) }

    var intervalDays by remember { mutableStateOf(initialConfig.intervalDays) }
    var maxPlants by remember { mutableStateOf(initialConfig.maxPlants) }
    var lookaheadDays by remember { mutableStateOf(initialConfig.lookaheadDays) }
    var includeWatering by remember { mutableStateOf(initialConfig.includeWatering) }
    var includePruning by remember { mutableStateOf(initialConfig.includePruning) }
    var includeFertilising by remember { mutableStateOf(initialConfig.includeFertilising) }
    var includeFeeding by remember { mutableStateOf(initialConfig.includeFeeding) }

    // Only shown when this device actually knows about more than one garden — a single-garden
    // install keeps the exact widget config screen it always had, no new clutter.
    var knownGardens by remember { mutableStateOf(knownGardensIncludingOwn(context)) }
    // This activity is launched fresh from the home screen (not from within MainActivity's Help
    // screen), so it only ever sees whatever known-gardens cache happened to already be on disk from
    // an earlier session — stale/incomplete for a device that hadn't recently opened Help's "Sync
    // with other devices" section (very plausible right after first placing the widget).
    //
    // null means "no explicit choice made — follow every garden this device has, including any
    // joined/created later" (WidgetConfig.selectedGardenIds' own "empty means all" sentinel). The
    // previous version eagerly resolved that into a concrete Set the instant this screen composed,
    // using whatever knownGardens happened to be at that exact moment — on a stale cache that meant
    // just this device's own garden, and since the checklist below only renders once knownGardens.size
    // > 1, the user saw no checklist at all and had no idea a restriction was being silently saved.
    // Worse, initialConfig.selectedGardenIds was then permanently non-empty from that point on, so
    // this never self-corrected even after the cache caught up on a later visit. Keeping the "no
    // explicit choice" state as a live null — not resolved into a snapshot until the user actually
    // taps a checkbox — fixes both: nothing is saved unless the user genuinely chose it, and "all
    // gardens" keeps meaning ALL, forever, including ones that don't exist yet.
    var selectedGardenIds by remember { mutableStateOf(initialConfig.selectedGardenIds.ifEmpty { null }) }
    // onSave triggers a full syncAllKnownGardens() before this activity finishes — a real network
    // round trip across every known garden, not instant — so without this a slow connection invited
    // repeat taps, each queuing up its own sync behind the last (harmless since GardenSyncClient's
    // per-garden mutex serializes them, but wasteful and confusing).
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        GardenMembershipClient.refreshKnownGardens(context)
        knownGardens = knownGardensIncludingOwn(context)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Widget settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("Plants requiring care", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        Text("Refresh every", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1 day", 2 to "2 days", 3 to "3 days", 7 to "7 days").forEach { (days, label) ->
                FilterChip(selected = intervalDays == days, onClick = { intervalDays = days }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Show up to", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            maxPlantsOptions.forEach { n ->
                FilterChip(selected = maxPlants == n, onClick = { maxPlants = n }, label = { Text("$n", fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Include plants due within", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Today only", 2 to "2 days", 5 to "5 days", 7 to "7 days").forEach { (days, label) ->
                FilterChip(selected = lookaheadDays == days, onClick = { lookaheadDays = days }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Show plants due for", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = includeWatering, onClick = { includeWatering = !includeWatering }, label = { Text("💧 Watering", fontSize = 12.sp) })
            FilterChip(selected = includePruning, onClick = { includePruning = !includePruning }, label = { Text("✂️ Pruning", fontSize = 12.sp) })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = includeFertilising, onClick = { includeFertilising = !includeFertilising }, label = { Text("🌱 Fertilising", fontSize = 12.sp) })
            FilterChip(selected = includeFeeding, onClick = { includeFeeding = !includeFeeding }, label = { Text("🍽️ Feeding", fontSize = 12.sp) })
        }
        if (!includeWatering && !includePruning && !includeFertilising && !includeFeeding) {
            Spacer(Modifier.height(4.dp))
            Text("Pick at least one, or the widget will have nothing to show.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        if (knownGardens.size > 1) {
            Spacer(Modifier.height(20.dp))
            Text("Show plants from", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            knownGardens.forEach { garden ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        // No explicit selection yet (null) reads as every garden checked — the "all,
                        // always" default — rather than materializing a snapshot just to render this.
                        checked = selectedGardenIds?.contains(garden.gardenId) ?: true,
                        onCheckedChange = { checked ->
                            val current = selectedGardenIds ?: knownGardens.map { it.gardenId }.toSet()
                            selectedGardenIds = if (checked) current + garden.gardenId else current - garden.gardenId
                        }
                    )
                    Text(garden.name, fontSize = 13.sp)
                }
            }
            if (selectedGardenIds?.isEmpty() == true) {
                Text("Pick at least one garden, or the widget will have nothing to show.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                saving = true
                onSave(
                    WidgetConfig(
                        intervalDays, maxPlants, lookaheadDays,
                        includeWatering, includePruning, includeFertilising, includeFeeding,
                        selectedGardenIds ?: emptySet()
                    )
                )
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Saving, please wait…" else "Save") }
    }
}
