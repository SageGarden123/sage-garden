@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay

// ============================================================================
// REMINDER ALARM, AND DEVICE-WIDE SETTINGS
// ============================================================================
// Per-garden settings live in GardenScopedSettings.kt (GardenSettings).

internal fun gardenPrefs(context: Context) = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)

/** How often a zone should get a fresh progress photo before it's flagged as due — fixed at 3
 * months (approximated as 90 days, consistent with every other day-based frequency in this app)
 * rather than a per-garden setting, since there's no obvious reason different gardens would want
 * a different cadence for this the way they legitimately do for watering. */
const val PROGRESS_PHOTO_REMINDER_DAYS = 90L

/**
 * The reminder TIME and notification STYLE are device-wide, not per garden: there's one daily alarm
 * and one combined notification per care type covering every garden, so reading them from whichever
 * garden happened to be active when the alarm fired made the schedule depend on what was last on
 * screen. They're stored under this device's own garden's keys, so existing values carry over.
 */
fun deviceReminderSettings(context: Context): GardenSettings = GardenSettings.of(context, getOrCreateInstallId(context))

/** Next occurrence (today if still ahead, else tomorrow) of the saved notification time. */
fun nextWateringAlarmTarget(context: Context): Long {
    val target = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, deviceReminderSettings(context).notificationHour)
        set(java.util.Calendar.MINUTE, deviceReminderSettings(context).notificationMinute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (before(java.util.Calendar.getInstance())) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return target.timeInMillis
}

internal fun wateringAlarmPendingIntent(context: Context): android.app.PendingIntent {
    val intent = android.content.Intent(context, WateringReminderReceiver::class.java)
    return android.app.PendingIntent.getBroadcast(
        context, 2001, intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Uses an exact AlarmManager alarm rather than a periodic WorkManager job — periodic work's
 * initial delay is only a lower bound and the OS can defer it well past the requested time
 * (especially under Doze/App Standby), so reminders would silently miss the configured time.
 * The receiver re-arms the next day's alarm each time it fires, and BootReceiver re-arms it
 * after a reboot since exact alarms don't survive a restart.
 */
fun scheduleWateringReminders(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val pendingIntent = wateringAlarmPendingIntent(context)
    val targetMillis = nextWateringAlarmTarget(context)
    val canScheduleExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    if (canScheduleExact) {
        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
    } else {
        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
    }
}
fun cancelWateringReminders(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    alarmManager.cancel(wateringAlarmPendingIntent(context))
}

// ============================================================================
// WEATHER-AWARE REMINDER SKIPPING
// ============================================================================

internal const val MAP_FALLBACK_LAT = 40.785091
internal const val MAP_FALLBACK_LNG = -73.968285

// ============================================================================
// WATER USAGE & COST
// ============================================================================

/** Dollars per kiloliter (1000L) — 0.0 means the user hasn't set a rate yet. */
fun getWaterRatePerKiloliter(context: Context): Double {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getFloat("water_rate_per_kl", 0f).toDouble()
}
fun setWaterRatePerKiloliter(context: Context, value: Double) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putFloat("water_rate_per_kl", value.toFloat()).apply()
}

// ============================================================================
// FIRST-PLANT NOTIFICATION HINT
// ============================================================================

/** Whether the one-time "set up reminders" hint has already been shown, device-wide (never re-shown after that). */
fun hasShownNotificationHint(context: Context): Boolean {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("notification_hint_shown", false)
}
fun setNotificationHintShown(context: Context) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("notification_hint_shown", true).apply()
}
