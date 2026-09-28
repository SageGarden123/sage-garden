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
            if (!GardenSettings.of(applicationContext, gardenId).notificationsEnabled) continue

            val plants = plantDao.getAllOnceForGarden(gardenId)
            if (plants.isEmpty()) continue

            val offsets = GardenSettings.of(applicationContext, gardenId).notificationOffsets
            val overdueRepeatEnabled = GardenSettings.of(applicationContext, gardenId).overdueRepeatEnabled
            val overdueRepeatDays = GardenSettings.of(applicationContext, gardenId).overdueRepeatDays

            fun isDue(status: WateringStatus?): Boolean {
                val dueMillis = status?.nextDueMillis ?: return false
                val diffDays = ((dueMillis - now) / 86_400_000L).toInt()
                return if (diffDays >= 0) diffDays in offsets
                else overdueRepeatEnabled && (-diffDays) % overdueRepeatDays == 0
            }

            val weatherSkipEnabled = GardenSettings.of(applicationContext, gardenId).weatherSkipEnabled
            val frostWarningsEnabled = GardenSettings.of(applicationContext, gardenId).frostWarningsEnabled
            val forecast = if (weatherSkipEnabled || frostWarningsEnabled) {
                GardenSettings.of(applicationContext, gardenId).latLng?.let { (lat, lng) -> WeatherHelper.fetchTodayForecast(lat, lng) }
            } else null

            val hemisphere = GardenSettings.of(applicationContext, gardenId).hemisphere
            val duePlants = plants.filter { isDue(computeWateringStatus(it, now, hemisphere)) }
            if (duePlants.isNotEmpty()) {
                val outdoorDuePlants = duePlants.filter { !it.isIndoor }
                if (weatherSkipEnabled && outdoorDuePlants.isNotEmpty() && forecast != null &&
                    forecast.maxProbabilityPercent >= GardenSettings.of(applicationContext, gardenId).rainProbabilityThreshold &&
                    forecast.totalPrecipitationMm >= GardenSettings.of(applicationContext, gardenId).rainAmountThresholdMm
                ) {
                    totalRainWarningMm = (totalRainWarningMm ?: 0.0) + forecast.totalPrecipitationMm
                }
                allDueWatering.addAll(duePlants)
            }

            if (GardenSettings.of(applicationContext, gardenId).fertiliseRemindersEnabled) {
                allDueFertilise.addAll(plants.filter { isDue(computeFertiliseStatus(it, now)) })
            }
            if (GardenSettings.of(applicationContext, gardenId).pruneRemindersEnabled) {
                allDuePrune.addAll(plants.filter { isDue(computePruneStatus(it, now)) })
            }
            if (GardenSettings.of(applicationContext, gardenId).feedRemindersEnabled) {
                allDueFeed.addAll(plants.filter { isDue(computeFeedStatus(it, now)) })
            }

            if (frostWarningsEnabled) {
                val minTemp = forecast?.minTempCelsius
                if (minTemp != null && minTemp <= GardenSettings.of(applicationContext, gardenId).frostTempThreshold) {
                    allFrostAtRisk.addAll(frostTenderOutdoorPlants(plants))
                }
            }

            val gardenSettings = GardenSettings.of(applicationContext, gardenId)
            if (gardenSettings.progressPhotoRemindersEnabled) {
                // Enabled before the enable time was recorded — start the clock now instead of treating every never-photographed zone as due forever.
                if (gardenSettings.progressPhotoRemindersEnabledAt == 0L) gardenSettings.progressPhotoRemindersEnabledAt = now
                val photos = locationPhotoDao.getAllOnceForGarden(gardenId)
                allDueProgressPhotoZones.addAll(
                    progressPhotoZonesToNotify(
                        plants, photos, now, gardenSettings.progressPhotoRemindersEnabledAt,
                        gardenSettings.overdueRepeatEnabled, gardenSettings.overdueRepeatDays
                    )
                )
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
