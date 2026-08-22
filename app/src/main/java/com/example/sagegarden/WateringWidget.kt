package com.example.sagegarden

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
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
        .apply()
}

/** All widget instance IDs currently placed on a home screen, for the in-app "manage widgets" list. */
fun getPlacedWidgetIds(context: Context): List<Int> =
    android.appwidget.AppWidgetManager.getInstance(context)
        .getAppWidgetIds(android.content.ComponentName(context, WateringWidgetReceiver::class.java))
        .toList()

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

/** Refreshes every placed instance immediately — called after anything that could change what's "due" (saving a plant, logging care, restoring a backup). */
suspend fun refreshWateringWidgets(context: Context) {
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

class WateringWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = getWidgetConfig(context, appWidgetId)
        val now = System.currentTimeMillis()
        val cutoff = now + config.lookaheadDays * 86_400_000L

        val allPlants = AppDatabase.getInstance(context).plantDao().getAllOnce()
        // Fresh prefs read, not HemisphereState — provideGlance can run in a cold-started process with no synced singleton.
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

        provideContent {
            WateringWidgetContent(allPlants.isEmpty(), careTypes.isEmpty(), dueSoon)
        }
    }
}

@Composable
private fun WateringWidgetContent(
    noPlantsAtAll: Boolean,
    noCareTypesSelected: Boolean,
    dueSoon: List<WidgetDueItem>
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Color(0xFFF5F5F0)).padding(10.dp)
    ) {
        Text(
            "🌿 Care due",
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ColorProvider(Color(0xFF233821)))
        )
        Spacer(GlanceModifier.height(6.dp))

        when {
            noPlantsAtAll -> Text("Add plants in the app to get started.", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray)))
            noCareTypesSelected -> Text("Edit this widget to choose what to show.", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray)))
            dueSoon.isEmpty() -> Text("All caught up 🌿", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color(0xFF3A5A40))))
            else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(dueSoon, itemId = { item -> (item.plant.id.hashCode() * 31 + item.careLabel.hashCode()).toLong() }) { item ->
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
