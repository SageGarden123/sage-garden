package com.example.sagegarden

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

        val weatherSkipEnabled = getWeatherSkipEnabled(applicationContext)
        val frostWarningsEnabled = getFrostWarningsEnabled(applicationContext)
        val forecast = if (weatherSkipEnabled || frostWarningsEnabled) {
            getGardenLatLng(applicationContext)?.let { (lat, lng) -> WeatherHelper.fetchTodayForecast(lat, lng) }
        } else null

        val hemisphere = getHemisphere(applicationContext) // fresh prefs read, not HemisphereState — this worker may run in a cold-started process with no synced singleton
        val duePlants = plants.filter { isDue(computeWateringStatus(it, now, hemisphere)) }
        if (duePlants.isNotEmpty()) {
            val outdoorDuePlants = duePlants.filter { !it.isIndoor }
            val rainWarningMm = if (weatherSkipEnabled && outdoorDuePlants.isNotEmpty() && forecast != null &&
                forecast.maxProbabilityPercent >= getRainProbabilityThreshold(applicationContext) &&
                forecast.totalPrecipitationMm >= getRainAmountThreshold(applicationContext)
            ) forecast.totalPrecipitationMm else null
            NotificationHelper.showWateringReminder(applicationContext, duePlants, rainWarningMm)
        }

        if (getFertiliseRemindersEnabled(applicationContext)) {
            val dueFertilise = plants.filter { isDue(computeFertiliseStatus(it, now)) }
            if (dueFertilise.isNotEmpty()) NotificationHelper.showFertiliseReminder(applicationContext, dueFertilise)
        }

        if (getPruneRemindersEnabled(applicationContext)) {
            val duePrune = plants.filter { isDue(computePruneStatus(it, now)) }
            if (duePrune.isNotEmpty()) NotificationHelper.showPruneReminder(applicationContext, duePrune)
        }

        if (getFeedRemindersEnabled(applicationContext)) {
            val dueFeed = plants.filter { isDue(computeFeedStatus(it, now)) }
            if (dueFeed.isNotEmpty()) NotificationHelper.showFeedReminder(applicationContext, dueFeed)
        }

        if (frostWarningsEnabled) {
            val minTemp = forecast?.minTempCelsius
            if (minTemp != null && minTemp <= getFrostTempThreshold(applicationContext)) {
                val atRisk = frostTenderOutdoorPlants(plants)
                if (atRisk.isNotEmpty()) NotificationHelper.showFrostWarning(applicationContext, atRisk)
            }
        }

        refreshWateringWidgets(applicationContext)
        return Result.success()
    }
}