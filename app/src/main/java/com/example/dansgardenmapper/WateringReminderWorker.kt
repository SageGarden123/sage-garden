package com.example.dansgardenmapper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WateringReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!getNotificationsEnabled(applicationContext)) return Result.success()

        val plants = AppDatabase.getInstance(applicationContext).plantDao().getAllOnce()
        val offsets = getNotificationOffsets(applicationContext)
        val now = System.currentTimeMillis()

        val duePlants = plants.filter { plant ->
            val status = computeWateringStatus(plant, now) ?: return@filter false
            val dueMillis = status.nextDueMillis ?: return@filter false
            val diffDays = ((dueMillis - now) / 86_400_000L).toInt()
            diffDays in offsets || (diffDays < 0 && 0 in offsets) // overdue plants still count toward "on the day"
        }

        NotificationHelper.showWateringReminder(applicationContext, duePlants)
        return Result.success()
    }
}