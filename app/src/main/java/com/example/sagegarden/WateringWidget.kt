package com.example.sagegarden

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

// ============================================================================
// WIDGET CONFIG STORAGE
// ============================================================================

data class WidgetConfig(
    val intervalDays: Int = 1,
    val maxPlants: Int = 8,
    val lookaheadDays: Int = 2,
    // Watering defaults on (matches every widget placed before these existed); the others default
    // off so existing widgets keep showing exactly what they showed before.
    val includeWatering: Boolean = true,
    val includePruning: Boolean = false,
    val includeFertilising: Boolean = false,
    val includeFeeding: Boolean = false
)

private fun widgetPrefs(context: Context) = context.getSharedPreferences("garden_mapper_widget_prefs", Context.MODE_PRIVATE)

fun getWidgetConfig(context: Context, appWidgetId: Int): WidgetConfig {
    val prefs = widgetPrefs(context)
    return WidgetConfig(
        intervalDays = prefs.getInt("widget_${appWidgetId}_interval_days", 1),
        maxPlants = prefs.getInt("widget_${appWidgetId}_max_plants", 8),
        lookaheadDays = prefs.getInt("widget_${appWidgetId}_lookahead_days", 2),
        includeWatering = prefs.getBoolean("widget_${appWidgetId}_include_watering", true),
        includePruning = prefs.getBoolean("widget_${appWidgetId}_include_pruning", false),
        includeFertilising = prefs.getBoolean("widget_${appWidgetId}_include_fertilising", false),
        includeFeeding = prefs.getBoolean("widget_${appWidgetId}_include_feeding", false)
    )
}

fun setWidgetConfig(context: Context, appWidgetId: Int, config: WidgetConfig) {
    widgetPrefs(context).edit()
        .putInt("widget_${appWidgetId}_interval_days", config.intervalDays)
        .putInt("widget_${appWidgetId}_max_plants", config.maxPlants)
        .putInt("widget_${appWidgetId}_lookahead_days", config.lookaheadDays)
        .putBoolean("widget_${appWidgetId}_include_watering", config.includeWatering)
        .putBoolean("widget_${appWidgetId}_include_pruning", config.includePruning)
        .putBoolean("widget_${appWidgetId}_include_fertilising", config.includeFertilising)
        .putBoolean("widget_${appWidgetId}_include_feeding", config.includeFeeding)
        .apply()
}

fun clearWidgetConfig(context: Context, appWidgetId: Int) {
    widgetPrefs(context).edit()
        .remove("widget_${appWidgetId}_interval_days")
        .remove("widget_${appWidgetId}_max_plants")
        .remove("widget_${appWidgetId}_lookahead_days")
        .remove("widget_${appWidgetId}_include_watering")
        .remove("widget_${appWidgetId}_include_pruning")
        .remove("widget_${appWidgetId}_include_fertilising")
        .remove("widget_${appWidgetId}_include_feeding")
        .remove("widget_${appWidgetId}_last_refreshed")
        .apply()
}

/** Set every time [WateringWidget.provideGlance] actually runs, so the widget can show the user
 * when its data was last pulled (Weawow-style) — this also makes future "did it actually refresh"
 * questions self-diagnosing: if this timestamp isn't moving, the refresh itself isn't running. */
private fun getWidgetLastRefreshed(context: Context, appWidgetId: Int): Long? =
    widgetPrefs(context).getLong("widget_${appWidgetId}_last_refreshed", -1L).takeIf { it > 0L }

private fun setWidgetLastRefreshed(context: Context, appWidgetId: Int, atMillis: Long) {
    widgetPrefs(context).edit().putLong("widget_${appWidgetId}_last_refreshed", atMillis).apply()
}

/** "Updated just now" / "Updated 5m ago" / "Updated 3h ago" / "Updated 2d ago". */
private fun lastRefreshedLabel(lastRefreshedAt: Long?): String {
    if (lastRefreshedAt == null) return "Not yet updated"
    val minutes = (System.currentTimeMillis() - lastRefreshedAt) / 60_000L
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated ${minutes}m ago"
        minutes < 24 * 60 -> "Updated ${minutes / 60}h ago"
        else -> "Updated ${minutes / (24 * 60)}d ago"
    }
}

// ============================================================================
// REFRESH SCHEDULING (AlarmManager — inexact is fine for a daily-ish widget refresh)
// ============================================================================

private fun widgetRefreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
    val intent = Intent(context, WateringWidgetRefreshReceiver::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    return PendingIntent.getBroadcast(
        context, 3000 + appWidgetId, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun scheduleWidgetRefresh(context: Context, appWidgetId: Int, intervalDays: Int) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerAt = System.currentTimeMillis() + intervalDays.coerceAtLeast(1) * 86_400_000L
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, widgetRefreshPendingIntent(context, appWidgetId))
}

fun cancelWidgetRefresh(context: Context, appWidgetId: Int) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(widgetRefreshPendingIntent(context, appWidgetId))
}

// Glance reuses an already-running "session" for a placed widget rather than re-invoking
// provideGlance() on every update()/updateAll() call — confirmed via logcat, where repeated config
// saves and refresh-button taps never re-triggered our data-fetch logic at all, only the *first*
// composition after the widget was placed did. update() on an existing session only pushes whatever
// was last composed; the documented way to make it recompute is to change the state the session
// observes (see WateringWidget.stateDefinition below) and let Compose react to that, not to call
// update() and hope. Bumping this timestamp is that "something changed" signal.
private val refreshTriggerKey = longPreferencesKey("refresh_trigger")

private suspend fun bumpRefreshTrigger(context: Context, glanceId: GlanceId) {
    updateAppWidgetState(context, glanceId) { prefs -> prefs[refreshTriggerKey] = System.currentTimeMillis() }
}

/** Refreshes every placed instance immediately — called after anything that could change what's "due" (saving a plant, logging care, restoring a backup). */
suspend fun refreshWateringWidgets(context: Context) {
    val ids = GlanceAppWidgetManager(context).getGlanceIds(WateringWidget::class.java)
    ids.forEach { bumpRefreshTrigger(context, it) }
    WateringWidget().updateAll(context)
}

class WateringWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (appWidgetId == -1) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WateringWidgetRefreshWorker>()
                .setInputData(Data.Builder().putInt("appWidgetId", appWidgetId).build())
                .build()
        )
        scheduleWidgetRefresh(context, appWidgetId, getWidgetConfig(context, appWidgetId).intervalDays)
    }
}

class WateringWidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt("appWidgetId", -1)
        if (appWidgetId == -1) return Result.success()
        try {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            bumpRefreshTrigger(applicationContext, glanceId)
            WateringWidget().update(applicationContext, glanceId)
        } catch (_: Exception) {
            // widget was removed since the alarm was scheduled — nothing to refresh
        }
        return Result.success()
    }
}

// ============================================================================
// WIDGET
// ============================================================================

/** One row's worth of due-care info — the widget can mix care types together in one list. */
private data class WidgetDueItem(val plant: PlantEntity, val status: WateringStatus, val careIcon: String, val careLabel: String)

private data class WidgetLoadResult(
    val noPlantsAtAll: Boolean,
    val noCareTypesSelected: Boolean,
    val dueSoon: List<WidgetDueItem>,
    val lastRefreshedAt: Long
)

private suspend fun loadWidgetData(context: Context, appWidgetId: Int): WidgetLoadResult {
    val config = getWidgetConfig(context, appWidgetId)
    val now = System.currentTimeMillis()
    val cutoff = now + config.lookaheadDays * 86_400_000L

    val allPlants = AppDatabase.getInstance(context).plantDao().getAllOnce()
    // Fresh prefs read, not HemisphereState — this can run in a cold-started process with no synced singleton.
    val hemisphere = getHemisphere(context)

    val careTypes = buildList {
        if (config.includeWatering) add(Triple("💧", "Water", { p: PlantEntity, t: Long -> computeWateringStatus(p, t, hemisphere) }))
        if (config.includePruning) add(Triple("✂️", "Prune", ::computePruneStatus))
        if (config.includeFertilising) add(Triple("🌱", "Fertilise", ::computeFertiliseStatus))
        if (config.includeFeeding) add(Triple("🍽️", "Feed", ::computeFeedStatus))
    }

    val dueSoon = allPlants
        .flatMap { p ->
            careTypes.mapNotNull { (icon, label, compute) ->
                compute(p, now)?.let { status -> WidgetDueItem(p, status, icon, label) }
            }
        }
        .filter { it.status.nextDueMillis == null || it.status.nextDueMillis <= cutoff }
        .sortedBy { it.status.sortKey() }
        .take(config.maxPlants)

    setWidgetLastRefreshed(context, appWidgetId, now)
    return WidgetLoadResult(allPlants.isEmpty(), careTypes.isEmpty(), dueSoon, now)
}

class WateringWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    // Makes the composition below observe refreshTriggerKey reactively — required for update() on
    // an already-running session to actually recompute anything (see refreshTriggerKey's doc comment).
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            val refreshTrigger = currentState(refreshTriggerKey) ?: 0L

            // Not keyed on refreshTrigger — deliberately keeps showing the previous result while a
            // newer load is in flight, rather than flashing back to a loading state on every refresh.
            var result by remember { mutableStateOf<WidgetLoadResult?>(null) }

            // Re-running this effect is what actually recomputes the widget's content. Keying on
            // refreshTrigger means a new trigger value (bumpRefreshTrigger, called by every refresh
            // path) cancels whatever load is still in flight and starts a fresh one — so the most
            // recently requested config is always what ends up on screen, never an older, slower
            // load finishing after it and clobbering the result.
            LaunchedEffect(refreshTrigger) {
                result = loadWidgetData(context, appWidgetId)
            }

            val loaded = result
            if (loaded == null) {
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(Color(0xFFF5F5F0)).padding(10.dp)
                ) {
                    Text("Loading…", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray)))
                }
            } else {
                WateringWidgetContent(appWidgetId, loaded.noPlantsAtAll, loaded.noCareTypesSelected, loaded.dueSoon, loaded.lastRefreshedAt)
            }
        }
    }
}

/** Wired to the widget's own refresh button — recomputes [WateringWidget]'s content for just this instance immediately, rather than waiting for the next scheduled alarm. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        bumpRefreshTrigger(context, glanceId)
        WateringWidget().update(context, glanceId)
    }
}

@Composable
private fun WateringWidgetContent(
    appWidgetId: Int,
    noPlantsAtAll: Boolean,
    noCareTypesSelected: Boolean,
    dueSoon: List<WidgetDueItem>,
    lastRefreshedAt: Long?
) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Color(0xFFF5F5F0)).padding(10.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🌿 Care due",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ColorProvider(Color(0xFF233821))),
                modifier = GlanceModifier.defaultWeight()
            )
            Box(
                modifier = GlanceModifier.size(26.dp).clickable(actionRunCallback<RefreshWidgetAction>()),
                contentAlignment = Alignment.Center
            ) { Text("🔄", style = TextStyle(fontSize = 13.sp)) }
            Box(
                modifier = GlanceModifier.size(26.dp).clickable(
                    actionStartActivity(
                        Intent(context, WateringWidgetConfigActivity::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            // Launched from a widget click (no Activity context), and needs its own
                            // task so Save/finish() drops back to the home screen instead of
                            // resurfacing MainActivity if the app already has a task in the background.
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                ),
                contentAlignment = Alignment.Center
            ) { Text("⚙️", style = TextStyle(fontSize = 13.sp)) }
        }
        Text(
            lastRefreshedLabel(lastRefreshedAt),
            style = TextStyle(fontSize = 9.sp, color = ColorProvider(Color.Gray))
        )
        Spacer(GlanceModifier.height(6.dp))

        // Kept as a single always-present LazyColumn (never swapped for a plain Text and back) —
        // Glance's collection view is backed by a separate RemoteViewsFactory the launcher queries
        // independently of the rest of the widget's RemoteViews tree, and removing/re-adding that
        // collection from composition (rather than just changing its item count) could plausibly
        // leave a stale/unbound factory behind until a later, unrelated refresh re-established it —
        // matching the "watering re-enabled doesn't reappear until I hit refresh" report.
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            when {
                noPlantsAtAll -> item { Text("Add plants in the app to get started.", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray))) }
                noCareTypesSelected -> item { Text("Edit this widget to choose what to show.", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray))) }
                dueSoon.isEmpty() -> item { Text("All caught up 🌿", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color(0xFF3A5A40)))) }
                else -> items(dueSoon, itemId = { item -> (item.plant.id.hashCode() * 31 + item.careLabel.hashCode()).toLong() }) { item ->
                    WateringWidgetRow(item)
                }
            }
        }
    }
}

@Composable
private fun WateringWidgetRow(item: WidgetDueItem) {
    val context = LocalContext.current
    val (plant, status, careIcon, careLabel) = item
    val overdue = status.label.startsWith("Overdue") || status.label.startsWith("Never")
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("widget_plant_id", plant.id)
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier.size(36.dp).background(Color(0xFFE3DDCF)).cornerRadius(18.dp),
            contentAlignment = Alignment.Center
        ) { Text(careIcon, style = TextStyle(fontSize = 15.sp)) }
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                plant.name, maxLines = 1,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ColorProvider(Color(0xFF233821)))
            )
            Text(
                "$careLabel: ${status.label}", maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(if (overdue) Color(0xFFB23B3B) else Color.Gray))
            )
        }
    }
}

class WateringWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WateringWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { id ->
            cancelWidgetRefresh(context, id)
            clearWidgetConfig(context, id)
        }
    }
}
