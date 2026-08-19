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

        fun isDue(status: WateringStatus?): Boolean {
            val dueMillis = status?.nextDueMillis ?: return false
            val diffDays = ((dueMillis - now) / 86_400_000L).toInt()
            return if (diffDays >= 0) diffDays in offsets
            else overdueRepeatEnabled && (-diffDays) % overdueRepeatDays == 0
        }

        val duePlants = plants.filter { isDue(computeWateringStatus(it, now)) }
        if (duePlants.isNotEmpty()) {
            val outdoorDuePlants = duePlants.filter { !it.isIndoor }
            var rainWarning = false
            if (getWeatherSkipEnabled(applicationContext) && outdoorDuePlants.isNotEmpty()) {
                getGardenLatLng(applicationContext)?.let { (lat, lng) ->
                    val forecast = WeatherHelper.fetchTodayForecast(lat, lng)
                    if (forecast != null && forecast.maxProbabilityPercent >= getRainProbabilityThreshold(applicationContext)) {
                        rainWarning = true
                    }
                }
            }
            NotificationHelper.showWateringReminder(applicationContext, duePlants, rainWarning)
        }

        if (getFertiliseRemindersEnabled(applicationContext)) {
            val dueFertilise = plants.filter { isDue(computeFertiliseStatus(it, now)) }
            if (dueFertilise.isNotEmpty()) NotificationHelper.showFertiliseReminder(applicationContext, dueFertilise)
        }

        if (getPruneRemindersEnabled(applicationContext)) {
            val duePrune = plants.filter { isDue(computePruneStatus(it, now)) }
            if (duePrune.isNotEmpty()) NotificationHelper.showPruneReminder(applicationContext, duePrune)
        }

        return Result.success()
    }
}