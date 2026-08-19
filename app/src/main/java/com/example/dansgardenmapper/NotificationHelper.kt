package com.example.dansgardenmapper

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

    fun showWateringReminder(context: Context, duePlants: List<PlantEntity>, rainWarning: Boolean) {
        if (duePlants.isEmpty()) return
        val style = getNotificationStyle(context)
        val channelId = if (style == "popup" || style == "both") CHANNEL_POPUP else CHANNEL_LOCKSCREEN

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (duePlants.size == 1) "${duePlants[0].name} needs watering" else "${duePlants.size} plants need watering"
        val body = duePlants.take(5).joinToString(", ") { it.name } +
                (if (duePlants.size > 5) " and ${duePlants.size - 5} more" else "") +
                (if (rainWarning) "\n🌧 Rain expected — consider skipping" else "")

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
        if (canPost) NotificationManagerCompat.from(context).notify(1001, notification)
    }
}