@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.android.libraries.places.api.Places
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

// ============================================================================
// MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit rather than relying on the OS default: targeting SDK 35 means Android 15+
        // already forces edge-to-edge regardless, but a device on an older Android version (or
        // certain OEM skins) falls back to the traditional letterboxed layout instead — which is
        // exactly the kind of "some phones show more top whitespace than others" inconsistency this
        // removes, since Scaffold/TopAppBar below already inset for the status bar correctly either
        // way once this is turned on consistently everywhere.
        enableEdgeToEdge()
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY)
            DropboxAuthState.checkAndRefresh(applicationContext)
        }
        AppCheckClient.init(applicationContext)
        // Synced here (synchronously, before the first composition) rather than in a LaunchedEffect
        // inside GardenMapperApp — these are plain SharedPreferences reads, and doing them before
        // setContent avoids a startup window where a deep-linked route (e.g. a notification opening
        // straight into "audit") could evaluate FeatureVisibility.shouldShow() against these
        // singletons' default values before an effect had a chance to sync them.
        SageEnabledState.enabled = FeatureVisibility.isSageChatEnabled(applicationContext)
        AdvancedModeState.enabled = FeatureVisibility.isAdvancedModeEnabled(applicationContext)
        // Always cold-start on this device's own default garden (null), never whatever garden was
        // last active — otherwise leaving the app open on someone else's shared garden and closing
        // it means the next launch silently stays in their garden until you notice and switch back.
        GardenMembershipStore.setActiveGardenId(applicationContext, null)
        ActiveGardenSettingsObserver.install(applicationContext)
        EntitlementLiveState.value = EntitlementManager.getCached(applicationContext)
        NotificationHelper.createChannels(applicationContext)
        if (anyGardenNotificationsEnabled(applicationContext)) scheduleWateringReminders(applicationContext)
        scheduleIrrigationHistorySync(applicationContext)
        PendingNotificationState.type = intent.getStringExtra("notification_type")
        PendingPlantEditState.plantId = intent.getStringExtra("widget_plant_id")
        PendingPlantEditState.gardenId = intent.getStringExtra("widget_garden_id")
        setContent {
            MaterialTheme {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
                    GardenMapperApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DropboxAuthState.checkAndRefresh(applicationContext)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingNotificationState.type = intent.getStringExtra("notification_type")
        PendingPlantEditState.plantId = intent.getStringExtra("widget_plant_id")
        PendingPlantEditState.gardenId = intent.getStringExtra("widget_garden_id")
    }
}
