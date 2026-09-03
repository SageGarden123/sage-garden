package com.example.sagegarden

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs once at the scheduled daily time (see WateringReminderReceiver) and checks EVERY garden this
 * device has access to — not just whichever one happens to be "active" in the UI — so someone
 * watching a friend's shared garden while they're away still gets reminders for it alongside their
 * own. Each garden's own notification settings (enabled toggle, offsets, hemisphere, weather/frost
 * thresholds) apply to that garden's own plants; due plants across every garden are combined into
 * one notification per care type (watering/fertilise/prune/feed/frost), matching the single daily
 * check time this worker already ran at before multi-garden sharing existed — genuinely independent
 * per-garden schedule times would need per-garden WorkManager scheduling, which is a bigger change
 * than this pass covers.
 */
class WateringReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Refreshes every known garden's local data first — MainActivity's foreground auto-sync
        // loop only keeps the ACTIVE garden fresh, so without this a garden you're a member of but
        // haven't opened recently would be checked against stale (or entirely empty) local plants.
        GardenSyncClient.syncAllKnownGardens(applicationContext)

        val plantDao = AppDatabase.getInstance(applicationContext).plantDao()
        val now = System.currentTimeMillis()

        val allDueWatering = mutableListOf<PlantEntity>()
        val allDueFertilise = mutableListOf<PlantEntity>()
        val allDuePrune = mutableListOf<PlantEntity>()
        val allDueFeed = mutableListOf<PlantEntity>()
        val allFrostAtRisk = mutableListOf<PlantEntity>()
        val allDueProgressPhotoZones = mutableListOf<String>()
        var totalRainWarningMm: Double? = null
        val locationPhotoDao = AppDatabase.getInstance(applicationContext).locationPhotoDao()

        for (gardenId in allKnownGardenIds(applicationContext)) {
            if (!getNotificationsEnabledFor(applicationContext, gardenId)) continue

            val plants = plantDao.getAllOnceForGarden(gardenId)
            if (plants.isEmpty()) continue

            val offsets = getNotificationOffsetsFor(applicationContext, gardenId)
            val overdueRepeatEnabled = getOverdueRepeatEnabledFor(applicationContext, gardenId)
            val overdueRepeatDays = getOverdueRepeatDaysFor(applicationContext, gardenId)

            fun isDue(status: WateringStatus?): Boolean {
                val dueMillis = status?.nextDueMillis ?: return false
                val diffDays = ((dueMillis - now) / 86_400_000L).toInt()
                return if (diffDays >= 0) diffDays in offsets
                else overdueRepeatEnabled && (-diffDays) % overdueRepeatDays == 0
            }

            val weatherSkipEnabled = getWeatherSkipEnabledFor(applicationContext, gardenId)
            val frostWarningsEnabled = getFrostWarningsEnabledFor(applicationContext, gardenId)
            val forecast = if (weatherSkipEnabled || frostWarningsEnabled) {
                getGardenLatLngFor(applicationContext, gardenId)?.let { (lat, lng) -> WeatherHelper.fetchTodayForecast(lat, lng) }
            } else null

            val hemisphere = getHemisphereFor(applicationContext, gardenId)
            val duePlants = plants.filter { isDue(computeWateringStatus(it, now, hemisphere)) }
            if (duePlants.isNotEmpty()) {
                val outdoorDuePlants = duePlants.filter { !it.isIndoor }
                if (weatherSkipEnabled && outdoorDuePlants.isNotEmpty() && forecast != null &&
                    forecast.maxProbabilityPercent >= getRainProbabilityThresholdFor(applicationContext, gardenId) &&
                    forecast.totalPrecipitationMm >= getRainAmountThresholdFor(applicationContext, gardenId)
                ) {
                    totalRainWarningMm = (totalRainWarningMm ?: 0.0) + forecast.totalPrecipitationMm
                }
                allDueWatering.addAll(duePlants)
            }

            if (getFertiliseRemindersEnabledFor(applicationContext, gardenId)) {
                allDueFertilise.addAll(plants.filter { isDue(computeFertiliseStatus(it, now)) })
            }
            if (getPruneRemindersEnabledFor(applicationContext, gardenId)) {
                allDuePrune.addAll(plants.filter { isDue(computePruneStatus(it, now)) })
            }
            if (getFeedRemindersEnabledFor(applicationContext, gardenId)) {
                allDueFeed.addAll(plants.filter { isDue(computeFeedStatus(it, now)) })
            }

            if (frostWarningsEnabled) {
                val minTemp = forecast?.minTempCelsius
                if (minTemp != null && minTemp <= getFrostTempThresholdFor(applicationContext, gardenId)) {
                    allFrostAtRisk.addAll(frostTenderOutdoorPlants(plants))
                }
            }

            if (getProgressPhotoRemindersEnabledFor(applicationContext, gardenId)) {
                val photos = locationPhotoDao.getAllOnceForGarden(gardenId)
                allDueProgressPhotoZones.addAll(dueProgressPhotoZones(plants, photos, now))
            }
        }

        if (allDueWatering.isNotEmpty()) NotificationHelper.showWateringReminder(applicationContext, allDueWatering, totalRainWarningMm)
        if (allDueFertilise.isNotEmpty()) NotificationHelper.showFertiliseReminder(applicationContext, allDueFertilise)
        if (allDuePrune.isNotEmpty()) NotificationHelper.showPruneReminder(applicationContext, allDuePrune)
        if (allDueFeed.isNotEmpty()) NotificationHelper.showFeedReminder(applicationContext, allDueFeed)
        if (allFrostAtRisk.isNotEmpty()) NotificationHelper.showFrostWarning(applicationContext, allFrostAtRisk)
        if (allDueProgressPhotoZones.isNotEmpty()) NotificationHelper.showProgressPhotoReminder(applicationContext, allDueProgressPhotoZones)

        refreshWateringWidgets(applicationContext)
        return Result.success()
    }
}
