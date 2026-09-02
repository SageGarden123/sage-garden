package com.example.sagegarden

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user's own manual note of when a zone/outlet is scheduled to run — entered by hand as a
 * fallback/preference to querying the Tuya cloud schedule API (see IrrigationScreen's "Tuya
 * schedule" section). Purely for reference: never sent to or read from Tuya.
 * [daysOfWeek] is a 7-char string of '0'/'1', Monday..Sunday — same format TuyaClient.DeviceTimer
 * already uses for its own (cloud-sourced) loops field, so both can share formatTuyaTimerDays.
 */
@Entity(tableName = "manual_zone_schedules", indices = [Index("zone"), Index("gardenId")])
data class ManualZoneScheduleEntity(
    @PrimaryKey val id: String,
    val zone: String,
    val gardenId: String = "",
    val daysOfWeek: String,
    val durationMinutes: Int,
    val createdAt: Long
)
