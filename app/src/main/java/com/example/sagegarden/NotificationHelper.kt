package com.example.sagegarden

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CHANNEL_LOCKSCREEN = "watering_reminders_lockscreen"
    const val CHANNEL_POPUP = "watering_reminders_popup"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOCKSCREEN, "Watering reminders (lock screen)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Quiet reminders shown in your notification shade and lock screen"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_POPUP, "Watering reminders (pop-up)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders that pop up on screen"
            }
        )
    }

    fun showWateringReminder(context: Context, duePlants: List<PlantEntity>, rainWarningMm: Double? = null) {
        if (duePlants.isEmpty()) return
        val title = if (duePlants.size == 1) "${duePlants[0].name} needs watering" else "${duePlants.size} plants need watering"
        val body = groupedDueBody(duePlants) +
                (if (rainWarningMm != null) "\n🌧 ~${"%.1f".format(rainWarningMm)}mm rain expected — consider skipping" else "")
        postCareNotification(context, notificationId = 1001, title = title, body = body, type = "watering")
    }

    fun showFertiliseReminder(context: Context, duePlants: List<PlantEntity>) {
        if (duePlants.isEmpty()) return
        val title = if (duePlants.size == 1) "${duePlants[0].name} needs fertilising" else "${duePlants.size} plants need fertilising"
        postCareNotification(context, notificationId = 1002, title = title, body = groupedDueBody(duePlants), type = "fertilise")
    }

    fun showPruneReminder(context: Context, duePlants: List<PlantEntity>) {
        if (duePlants.isEmpty()) return
        val title = if (duePlants.size == 1) "${duePlants[0].name} needs pruning" else "${duePlants.size} plants need pruning"
        postCareNotification(context, notificationId = 1003, title = title, body = groupedDueBody(duePlants), type = "prune")
    }

    fun showFeedReminder(context: Context, duePlants: List<PlantEntity>) {
        if (duePlants.isEmpty()) return
        val title = if (duePlants.size == 1) "${duePlants[0].name} needs feeding" else "${duePlants.size} plants need feeding"
        postCareNotification(context, notificationId = 1005, title = title, body = groupedDueBody(duePlants), type = "feed")
    }

    fun showFrostWarning(context: Context, atRiskPlants: List<PlantEntity>) {
        if (atRiskPlants.isEmpty()) return
        val title = "❄️ Frost expected — protect ${atRiskPlants.size} plant(s)"
        postCareNotification(context, notificationId = 1004, title = title, body = groupedDueBody(atRiskPlants), type = "frost")
    }

    /** Once a single location has more than this many plants due at once (a whole irrigation zone coming due together, most commonly), name the location instead of every plant in it. */
    private const val LOCATION_GROUP_THRESHOLD = 10

    /**
     * Builds a notification body that names individual plants when there are only a few, but
     * collapses a location with many plants due at once into one line ("All 14 plants in Front
     * Garden") instead of spelling out every name — the common case for a whole irrigation zone
     * (or a whole garden bed) coming due together, where a flat plant list becomes unreadable.
     */
    private fun groupedDueBody(duePlants: List<PlantEntity>): String {
        val parts = mutableListOf<String>()
        duePlants.groupBy { it.location }.forEach { (location, plantsHere) ->
            if (plantsHere.size > LOCATION_GROUP_THRESHOLD && location.isNotBlank()) {
                parts.add("All ${plantsHere.size} plants in $location")
            } else {
                parts.addAll(plantsHere.map { it.name })
            }
        }
        return parts.take(5).joinToString(", ") + (if (parts.size > 5) " and ${parts.size - 5} more" else "")
    }

    private fun postCareNotification(context: Context, notificationId: Int, title: String, body: String, type: String) {
        val style = getNotificationStyle(context)
        val channelId = if (style == "popup" || style == "both") CHANNEL_POPUP else CHANNEL_LOCKSCREEN

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("notification_type", type)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, type.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_leaf)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (channelId == CHANNEL_POPUP) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}