package com.example.dansgardenmapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Exact alarms don't survive a reboot, so re-arm the watering reminder alarm on startup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && getNotificationsEnabled(context)) {
            scheduleWateringReminders(context)
        }
    }
}
