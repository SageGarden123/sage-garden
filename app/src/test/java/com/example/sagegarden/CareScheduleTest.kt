package com.example.sagegarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CareScheduleTest {
    private val day = 86_400_000L

    private fun plant(
        location: String = "Front bed",
        lastWatered: Long? = null,
        freq: Int? = null,
        summer: Int? = null,
        winter: Int? = null,
    ) = PlantEntity(
        id = "P0001", name = "Rosemary", sci = "", location = location, sun = "", water = "", soil = "",
        frost = "", native = "", pollinator = "", source = "", date = "", qty = 1, notes = "",
        wateringSystem = "", lat = null, lng = null, photoUri = null,
        lastWateredDate = lastWatered, wateringFrequencyDays = freq,
        summerWateringFrequencyDays = summer, winterWateringFrequencyDays = winter,
    )

    private fun dateMillis(year: Int, month: Int, dayOfMonth: Int): Long = Calendar.getInstance(TimeZone.getDefault()).apply {
        clear(); set(year, month, dayOfMonth, 12, 0)
    }.timeInMillis

    @Test fun `no frequency means nothing to schedule`() {
        assertNull(computeWateringStatus(plant(), 0L, Hemisphere.SOUTHERN))
    }

    @Test fun `never watered is due now`() {
        val status = computeWateringStatus(plant(freq = 3), dateMillis(2026, Calendar.MARCH, 10), Hemisphere.SOUTHERN)!!
        assertNull(status.nextDueMillis)
        assertEquals("Never watered — water now", status.label)
    }

    @Test fun `due today, due soon and overdue labels`() {
        val now = dateMillis(2026, Calendar.MARCH, 10)
        assertEquals("Due today", computeWateringStatus(plant(lastWatered = now - 3 * day, freq = 3), now, Hemisphere.SOUTHERN)!!.label)
        assertEquals("Due in 2 day(s)", computeWateringStatus(plant(lastWatered = now - 1 * day, freq = 3), now, Hemisphere.SOUTHERN)!!.label)
        assertEquals("Overdue by 2 day(s)", computeWateringStatus(plant(lastWatered = now - 5 * day, freq = 3), now, Hemisphere.SOUTHERN)!!.label)
    }

    @Test fun `a picked date counts by calendar day, not 24-hour periods`() {
        val sydney = TimeZone.getTimeZone("Australia/Sydney")
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(sydney)
        try {
            // Watered "28 Sep" in the date picker = stored as UTC midnight; 2-day frequency → due 30 Sep.
            val picked = java.time.LocalDate.of(2026, 9, 28).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val eveningOf29th = Calendar.getInstance(sydney).apply { clear(); set(2026, Calendar.SEPTEMBER, 29, 20, 0) }.timeInMillis
            val morningOf30th = Calendar.getInstance(sydney).apply { clear(); set(2026, Calendar.SEPTEMBER, 30, 7, 0) }.timeInMillis
            assertEquals("Due in 1 day(s)", computeWateringStatus(plant(lastWatered = picked, freq = 2), eveningOf29th, Hemisphere.SOUTHERN)!!.label)
            assertEquals("Due today", computeWateringStatus(plant(lastWatered = picked, freq = 2), morningOf30th, Hemisphere.SOUTHERN)!!.label)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun `seasonal frequency follows the hemisphere`() {
        val p = plant(freq = 7, summer = 2, winter = 14)
        val january = dateMillis(2026, Calendar.JANUARY, 15)
        val july = dateMillis(2026, Calendar.JULY, 15)
        val april = dateMillis(2026, Calendar.APRIL, 15)
        assertEquals(2, effectiveWateringFrequencyDays(p, january, Hemisphere.SOUTHERN))   // southern summer
        assertEquals(14, effectiveWateringFrequencyDays(p, july, Hemisphere.SOUTHERN))     // southern winter
        assertEquals(14, effectiveWateringFrequencyDays(p, january, Hemisphere.NORTHERN))  // northern winter
        assertEquals(2, effectiveWateringFrequencyDays(p, july, Hemisphere.NORTHERN))      // northern summer
        assertEquals(7, effectiveWateringFrequencyDays(p, april, Hemisphere.SOUTHERN))     // shoulder season
    }

    @Test fun `blank seasonal override falls back to the normal frequency`() {
        val p = plant(freq = 7)
        assertEquals(7, effectiveWateringFrequencyDays(p, dateMillis(2026, Calendar.JANUARY, 15), Hemisphere.SOUTHERN))
    }

    // ---- Progress-photo reminders: notify on the due day, then only on the overdue-repeat cadence.

    private fun photo(zone: String, takenAt: Long) = LocationPhotoEntity(id = "$zone-$takenAt", location = zone, uri = "", takenAt = takenAt)

    @Test fun `progress photo notifies on the day it becomes due, not every day after`() {
        val taken = dateMillis(2026, Calendar.JANUARY, 1)
        val dueAt = taken + PROGRESS_PHOTO_REMINDER_DAYS * day
        val plants = listOf(plant(location = "Front bed"))
        val photos = listOf(photo("Front bed", taken))
        fun notifies(daysAfterDue: Int, repeat: Boolean = true) =
            progressPhotoZonesToNotify(plants, photos, dueAt + daysAfterDue * day, enabledAt = 0L, overdueRepeatEnabled = repeat, overdueRepeatDays = 3)

        assertEquals(emptyList<String>(), progressPhotoZonesToNotify(plants, photos, dueAt - day, 0L, true, 3))
        assertEquals(listOf("Front bed"), notifies(0))
        assertEquals(emptyList<String>(), notifies(1))
        assertEquals(emptyList<String>(), notifies(2))
        assertEquals(listOf("Front bed"), notifies(3))
        assertEquals(listOf("Front bed"), notifies(6))
        assertEquals(emptyList<String>(), notifies(3, repeat = false))
    }

    @Test fun `never-photographed zone counts from when reminders were enabled`() {
        val enabledAt = dateMillis(2026, Calendar.MARCH, 1)
        val plants = listOf(plant(location = "Veggie patch"))
        assertEquals(listOf("Veggie patch"), progressPhotoZonesToNotify(plants, emptyList(), enabledAt, enabledAt, true, 3))
        assertEquals(emptyList<String>(), progressPhotoZonesToNotify(plants, emptyList(), enabledAt + day, enabledAt, true, 3))
        // It's still listed as due (for the details screen) the whole time.
        assertEquals(listOf("Veggie patch"), dueProgressPhotoZones(plants, emptyList(), enabledAt + day))
    }
}
