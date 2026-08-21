package com.example.sagegarden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Fired by the exact alarm set in [scheduleWateringReminders]. Runs the due-check once, then re-arms tomorrow's alarm. */
class WateringReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (getNotificationsEnabled(context)) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WateringReminderWorker>().build())
            scheduleWateringReminders(context)
        }
    }
}
