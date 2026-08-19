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

        val overdueRepeatEnabled = getOverdueRepeatEnabled(applicationContext)
        val overdueRepeatDays = getOverdueRepeatDays(applicationContext)

        val duePlants = plants.filter { plant ->
            val status = computeWateringStatus(plant, now) ?: return@filter false
            val dueMillis = status.nextDueMillis ?: return@filter false
            val diffDays = ((dueMillis - now) / 86_400_000L).toInt()
            if (diffDays >= 0) {
                diffDays in offsets
            } else {
                val overdueDays = -diffDays
                overdueRepeatEnabled && overdueDays % overdueRepeatDays == 0
            }
        }
        if (duePlants.isEmpty()) return Result.success()

        val outdoorDuePlants = duePlants.filter { !it.isIndoor }

        var rainWarning = false
        if (getWeatherSkipEnabled(applicationContext) && outdoorDuePlants.isNotEmpty()) {
            getGardenLatLng(applicationContext)?.let { (lat, lng) ->
                val forecast = WeatherHelper.fetchTodayForecast(lat, lng)
                if (forecast != null && forecast.maxProbabilityPercent >= getRainProbabilityThreshold(
                        applicationContext
                    )
                ) {
                    rainWarning = true
                }
            }
        }

        NotificationHelper.showWateringReminder(applicationContext, duePlants, rainWarning)
        return Result.success()
    }}