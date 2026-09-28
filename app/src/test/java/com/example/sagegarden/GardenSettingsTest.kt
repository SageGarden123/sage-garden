package com.example.sagegarden

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-garden settings must never leak between gardens — the bug class behind the garden-address,
 * irrigation and reminder leaks. Uses Robolectric for real SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GardenSettingsTest {
    private lateinit var context: Context
    private lateinit var ownId: String
    private val sharedId = "shared-garden-polana"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ownId = getOrCreateInstallId(context)
        GardenMembershipStore.setActiveGardenId(context, null)
    }

    @Test fun `writing one garden's address never touches another's`() {
        GardenSettings.of(context, ownId).address = "1 Dan St"
        GardenSettings.of(context, sharedId).address = "9 Polana Rd"
        GardenSettings.of(context, sharedId).setLatLng(-33.9, 151.2)

        assertEquals("1 Dan St", GardenSettings.of(context, ownId).address)
        assertNull(GardenSettings.of(context, ownId).latLng)
        assertEquals("9 Polana Rd", GardenSettings.of(context, sharedId).address)
    }

    @Test fun `active follows the garden on screen`() {
        GardenSettings.of(context, sharedId).notificationsEnabled = true
        assertEquals(false, GardenSettings.active(context).notificationsEnabled)
        GardenMembershipStore.setActiveGardenId(context, sharedId)
        assertEquals(true, GardenSettings.active(context).notificationsEnabled)
    }

    @Test fun `legacy unscoped values only carry over to this device's own garden`() {
        context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE).edit()
            .putString("garden_address", "Legacy address").commit()
        assertEquals("Legacy address", GardenSettings.of(context, ownId).address)
        assertEquals("", GardenSettings.of(context, sharedId).address)
    }

    @Test fun `observer only reflects the active garden`() {
        ActiveGardenSettingsObserver.install(context)
        GardenSettings.of(context, ownId).address = "1 Dan St"
        GardenSettings.of(context, sharedId).address = "9 Polana Rd"   // a background sync of another garden
        ActiveGardenSettingsObserver.refresh(context)
        assertEquals("1 Dan St", GardenAddressState.address)
    }

    @Test fun `zones distinguish never-set from explicitly empty`() {
        assertNull(GardenSettings.of(context, sharedId).locations)
        GardenSettings.of(context, sharedId).locations = emptyList()
        assertEquals(emptyList<String>(), GardenSettings.of(context, sharedId).locations)
        GardenSettings.of(context, sharedId).locations = listOf("Front bed", "Veggie patch")
        assertEquals(listOf("Front bed", "Veggie patch"), GardenSettings.of(context, sharedId).locations)
    }

    @Test fun `reminder time and style are device-wide`() {
        GardenMembershipStore.setActiveGardenId(context, sharedId)
        deviceReminderSettings(context).setNotificationTime(18, 30)
        assertEquals(18, GardenSettings.of(context, ownId).notificationHour)
        assertEquals(30, deviceReminderSettings(context).notificationMinute)
    }
}
