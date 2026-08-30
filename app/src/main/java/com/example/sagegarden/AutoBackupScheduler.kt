package com.example.sagegarden

import android.content.Context

/**
 * A safety net beneath the existing manual "Export to device" / Dropbox backup features, both of
 * which require the user to remember to trigger them. Call [runIfDue] once per app open (see
 * GardenMapperApp) — it no-ops unless it's been at least a day since the last run for that garden.
 * Always writes a local on-device snapshot (zero setup needed), and additionally pushes to Dropbox
 * if already connected. See BackupHelper's "AUTOMATIC BACKUP SAFETY NET" section for the actual
 * read/write and the weekday-slot rotation scheme.
 */
object AutoBackupScheduler {
    private const val PREFS = "auto_backup_scheduler_prefs"
    private const val KEY_LAST_RUN_PREFIX = "last_run_at."

    // Slightly under 24h so opening the app a little earlier than exactly this time yesterday still
    // counts as "today's" run, rather than silently slipping a day later each time.
    private const val MIN_INTERVAL_MILLIS = 20 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun runIfDue(
        context: Context,
        gardenId: String,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>
    ) {
        // Nothing worth protecting yet, and avoids overwriting a real snapshot with an empty one
        // before Room has finished its first load for this composition.
        if (plants.isEmpty()) return

        val key = KEY_LAST_RUN_PREFIX + gardenId
        val last = prefs(context).getLong(key, 0L)
        if (System.currentTimeMillis() - last < MIN_INTERVAL_MILLIS) return

        BackupHelper.createLocalAutoBackup(context, gardenId, plants, paths, events)
        // Best-effort: silently does nothing if Dropbox isn't connected, same as any other Dropbox
        // action in this app when it isn't linked — this is a bonus on top of the local snapshot
        // above, never a replacement for it.
        runCatching {
            BackupHelper.createBackup(context, plants, paths, events, BackupHelper.dropboxAutoBackupFileName(context, gardenId))
        }

        prefs(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }
}
