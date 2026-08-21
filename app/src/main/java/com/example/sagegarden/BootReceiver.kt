package com.example.sagegarden

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Alarms don't survive a reboot, so re-arm the watering reminder alarm and any placed widgets' refresh alarms on startup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (getNotificationsEnabled(context)) scheduleWateringReminders(context)

        val appWidgetIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WateringWidgetReceiver::class.java))
        appWidgetIds.forEach { id -> scheduleWidgetRefresh(context, id, getWidgetConfig(context, id).intervalDays) }
    }
}
