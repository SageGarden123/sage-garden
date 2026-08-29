// ============================================================================
// Sage Garden — MainActivity.kt
// ============================================================================
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.WriteMode
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FetchPlaceResponse
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsResponse
import com.google.maps.android.compose.*
import java.text.DecimalFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ============================================================================
// DATA LAYER (Room database)
// ============================================================================

// Room entities moved to separate files

@OptIn(ExperimentalCoroutinesApi::class)
class PlantViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).plantDao()

    // Re-queries whenever the active garden changes (see ActiveGardenState/effectiveGardenId in
    // GardenMembershipClient.kt) rather than once at construction, so switching gardens updates
    // every screen reading this without needing to recreate the ViewModel graph.
    val plants: StateFlow<List<PlantEntity>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getAll(gardenId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filters = MutableStateFlow(DashboardFilters())
    val filters: StateFlow<DashboardFilters> = _filters.asStateFlow()

    val filteredPlants: StateFlow<List<PlantEntity>> = combine(plants, _filters) { list, f ->
        list.filter { p ->
            (f.location == "All" || p.location == f.location) &&
                    (f.source == "All" || p.source == f.source) &&
                    (f.plant == "All" || p.name == f.plant) &&
                    (f.sun == "All" || p.sun == f.sun) &&
                    (f.soil == "All" || p.soil == f.soil) &&
                    (f.soilPh == "All" || p.soilPh == f.soilPh) &&
                    (f.category == "All" || p.category == f.category) &&
                    (f.water == "All" || p.water == f.water) &&
                    (f.frost == "All" || p.frost == f.frost)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilters(newFilters: DashboardFilters) { _filters.value = newFilters }

    /**
     * Every plant on this device across EVERY garden — used only to pick a freshly auto-generated
     * plant id (see generateNextPlantId) that's guaranteed not to collide with a plant in a
     * DIFFERENT garden. [plants] above is scoped to just the active garden, which used to be what
     * new-plant id generation checked against — a brand new second garden's first plant then always
     * got "P0001" again, colliding with (and silently overwriting, via upsert's REPLACE conflict
     * strategy) the original garden's own "P0001" plant, since `id` alone is this table's primary
     * key and both gardens' plants share one local table.
     */
    suspend fun getAllPlantsOnDevice(): List<PlantEntity> = dao.getAllOnce()

    /** Suspends until the write (and widget refresh) complete — for callers that need to sequence further work after the save actually lands. */
    suspend fun saveSync(plant: PlantEntity) {
        // A brand-new plant (built fresh by FormScreen, never carrying a garden id forward) is
        // stamped with whichever garden is currently active; an edit of an existing plant already
        // carries its real gardenId through FormScreen's own state and is left untouched.
        val stamped = if (plant.gardenId.isBlank()) plant.copy(gardenId = effectiveGardenId(getApplication())) else plant
        dao.upsert(stamped.copy(updatedAt = System.currentTimeMillis()))
        refreshWateringWidgets(getApplication())
    }
    fun save(plant: PlantEntity) = viewModelScope.launch { saveSync(plant) }
    fun delete(id: String) = viewModelScope.launch {
        val gardenId = dao.getById(id)?.gardenId ?: effectiveGardenId(getApplication())
        GardenSyncStore.recordPlantDeleted(getApplication(), gardenId, id)
        dao.deleteById(id)
    }
    fun resetAll() = viewModelScope.launch {
        val gardenId = effectiveGardenId(getApplication())
        dao.getAllOnceForGarden(gardenId).forEach { GardenSyncStore.recordPlantDeleted(getApplication(), gardenId, it.id) }
        dao.deleteForGarden(gardenId)
    }

    fun runDropboxAutoLink(context: Context, folderPath: String) {
        if (DropboxLinkState.linking) return
        DropboxLinkState.start()
        viewModelScope.launch {
            val currentPlants = plants.value
            val result = autoLinkDropboxPhotos(
                context, folderPath, currentPlants,
                onProgress = { c, t -> DropboxLinkState.updateProgress(c, t) }
            ) { save(it) }
            val message = if (result.errorMessage == null) {
                "${result.linkedCount} of ${result.matchedCount} plants linked to photos"
            } else {
                "Linking unsuccessful due to ${result.errorMessage}"
            }
            setLastDropboxLinkResult(context, message)
            DropboxLinkState.finish(message)
        }
    }

    suspend fun getById(id: String): PlantEntity? = dao.getById(id)
}

// ============================================================================
// DROPDOWN OPTIONS
// ============================================================================

val sunOptions = listOf("Full", "Full-Partial", "Partial", "Partial-Shade", "Shade", "Unknown")
val waterOptions = listOf("Low", "Moderate", "High", "Unknown")
val soilOptions = listOf(
    "Sandy", "Loamy", "Clay", "Silty", "Peaty", "Chalky", "Rocky/Stony", "Potting Mix", "Other", "Unknown"
)
val soilPhOptions = listOf("Acidic", "Acidic–Neutral", "Neutral", "Neutral–Alkaline", "Alkaline", "Acidic–Alkaline", "Unknown")
val categoryOptions = listOf(
    "Trees", "Shrubs", "Ground Cover", "Climbers/Vines", "Grasses", "Ferns", "Perennials",
    "Annuals", "Bulbs", "Succulents", "Palms/Cycads", "Aquatic", "Herbs", "Other"
)

/** Emoji used as this category's map marker — falls back to a plain pin for "Other"/unset. */
fun categoryMarkerEmoji(category: String): String = when (category) {
    "Trees" -> "🌳"
    "Shrubs" -> "🪴"
    "Ground Cover" -> "🍀"
    "Climbers/Vines" -> "🍃"
    "Grasses" -> "🌾"
    "Ferns" -> "🌿"
    "Perennials" -> "🌸"
    "Annuals" -> "🌻"
    "Bulbs" -> "🌷"
    "Succulents" -> "🌵"
    "Palms/Cycads" -> "🌴"
    "Aquatic" -> "🪷"
    "Herbs" -> "🌱"
    else -> "📍"
}
val frostOptions = listOf("Hardy", "Half-hardy", "Tender", "Tender (indoor only)", "Unknown")
val nativeOptions = listOf("Native (Aus)", "Exotic")
val pollinatorOptions = listOf(
    "Yes - bees", "Yes - butterflies", "Yes - bees & butterflies",
    "Yes - birds", "No", "Other"
)

// ============================================================================
// SIMPLE PREFERENCES (photo storage mode: "local" or "cloud")
// ============================================================================

fun getPhotoStorageMode(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("photo_storage_mode", "local") ?: "local"
}

fun setPhotoStorageMode(context: Context, mode: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("photo_storage_mode", mode).apply()
}

/**
 * Which map image/rotation/on-off-toggle is in effect — per-garden, via the same
 * gardenScopedString/Boolean/Int helpers (defined below) used for hemisphere/notification settings,
 * so switching gardens in Help swaps to that garden's own drawing instead of showing garden A's
 * custom map while looking at garden B's plants.
 */
fun getCustomMapUri(context: Context): Uri? {
    val raw = gardenScopedString(context, "custom_map_uri", "")
    return raw.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
}
fun setCustomMapUri(context: Context, uri: Uri?) {
    setGardenScopedString(context, "custom_map_uri", uri?.toString() ?: "")
}
fun isUsingCustomMap(context: Context): Boolean = gardenScopedBoolean(context, "use_custom_map", false)
fun getCustomMapRotation(context: Context): Int = gardenScopedInt(context, "custom_map_rotation", 0)
fun setCustomMapRotation(context: Context, degrees: Int) {
    setGardenScopedInt(context, "custom_map_rotation", ((degrees % 360) + 360) % 360)
}
fun setUsingCustomMap(context: Context, value: Boolean) {
    setGardenScopedBoolean(context, "use_custom_map", value)
}

// ============================================================================
// IRRIGATION SYSTEM SELECTION
// ============================================================================
// Pure visibility toggle for the Help screen's Irrigation section — switching
// it never deletes either vendor's stored credentials or zone mappings below.

enum class IrrigationSystem { NONE, TUYA, RACHIO }

/**
 * Which irrigation vendor is active, plus every zone mapping and credential below, is per-garden —
 * two shared gardens are two different physical properties, plausibly with entirely different
 * irrigation controllers, so garden B seeing garden A's Tuya zones (or vice versa) is exactly the
 * kind of cross-garden bleed this scoping (mirroring hemisphere/notification settings) closes.
 * Defaults to TUYA when the device already has non-blank Tuya credentials saved for this garden
 * (pre-existing testers see zero change), else NONE.
 */
fun getIrrigationSystem(context: Context): IrrigationSystem {
    val stored = gardenScopedString(context, "irrigation_system", "")
    if (stored.isNotBlank()) return IrrigationSystem.entries.firstOrNull { it.name == stored } ?: IrrigationSystem.NONE
    return if (getTuyaClientId(context).isNotBlank() && getTuyaClientSecret(context).isNotBlank()) IrrigationSystem.TUYA else IrrigationSystem.NONE
}
fun setIrrigationSystem(context: Context, value: IrrigationSystem) {
    setGardenScopedString(context, "irrigation_system", value.name)
}

// ============================================================================
// TUYA ZONE MAPPING (outlet-level granularity)
// ============================================================================
// Each physical Tuya device has up to two independent outlets. A "zone" is a
// friendly, user-chosen name for ONE outlet on ONE device (e.g. "Front Garden"
// = deviceId X, outlet "1"; "Front Patch" = same deviceId X, outlet "2").
// Stored as: "zoneA=deviceId:outlet|zoneB=deviceId:outlet|..."

/**
 * Dedicated prefs file for actual secrets (OAuth tokens, API client secrets) — kept separate from
 * "garden_mapper_prefs" so it alone can be excluded from Android's backup/device-transfer (see
 * data_extraction_rules.xml / backup_rules.xml), without losing ordinary settings on restore.
 * [migrateCredential] is a one-time fallback for any install with an already-installed build that
 * wrote a given key into the old general prefs file, so existing testers aren't silently signed out.
 */
private fun credentialPrefs(context: Context) = context.getSharedPreferences("garden_mapper_credential_prefs", Context.MODE_PRIVATE)

private fun migrateCredential(context: Context, key: String): String? {
    val creds = credentialPrefs(context)
    creds.getString(key, null)?.let { return it }
    val general = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    val legacy = general.getString(key, null) ?: return null
    creds.edit().putString(key, legacy).apply()
    general.edit().remove(key).apply()
    return legacy
}

data class TuyaZoneMapping(val zone: String, val deviceId: String, val outlet: String)

fun getTuyaZoneMappings(context: Context): List<TuyaZoneMapping> {
    val raw = gardenScopedString(context, "tuya_device_mapping", "")
    return raw.split("|").filter { it.contains("=") }.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val (zone, rest) = parts
        val restParts = rest.split(":")
        val deviceId = restParts.getOrNull(0) ?: return@mapNotNull null
        val outlet = restParts.getOrNull(1) ?: "1"
        if (zone.isBlank() || deviceId.isBlank()) null else TuyaZoneMapping(zone, deviceId, outlet)
    }
}

fun setTuyaZoneMappings(context: Context, mappings: List<TuyaZoneMapping>) {
    val raw = mappings.joinToString("|") { "${it.zone}=${it.deviceId}:${it.outlet}" }
    setGardenScopedString(context, "tuya_device_mapping", raw)
}

/** Each user connects their own Tuya Cloud project — nothing is shared between installs. migrateCredential is a one-time, garden-independent hop from the old general prefs file into credentialPrefs; gardenScopedString's own legacy-key fallback then takes it from there per garden. */
fun getTuyaClientId(context: Context): String {
    migrateCredential(context, "tuya_client_id")
    return gardenScopedString(context, "tuya_client_id", "", credentialPrefs(context))
}
fun setTuyaClientId(context: Context, value: String) {
    setGardenScopedString(context, "tuya_client_id", value, credentialPrefs(context))
}
fun getTuyaClientSecret(context: Context): String {
    migrateCredential(context, "tuya_client_secret")
    return gardenScopedString(context, "tuya_client_secret", "", credentialPrefs(context))
}
fun setTuyaClientSecret(context: Context, value: String) {
    setGardenScopedString(context, "tuya_client_secret", value, credentialPrefs(context))
}

// ============================================================================
// RACHIO ZONE MAPPING
// ============================================================================
// Unlike Tuya, a Rachio zone already carries its own vendor-assigned name and
// id — a "zone" here is just that zone on a given device (deviceId + zoneId).
// Stored as: "zoneA=deviceId:zoneId|zoneB=deviceId:zoneId|..."

data class RachioZoneMapping(val zone: String, val deviceId: String, val zoneId: String)

fun getRachioZoneMappings(context: Context): List<RachioZoneMapping> {
    val raw = gardenScopedString(context, "rachio_device_mapping", "")
    return raw.split("|").filter { it.contains("=") }.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val (zone, rest) = parts
        val restParts = rest.split(":")
        val deviceId = restParts.getOrNull(0) ?: return@mapNotNull null
        val zoneId = restParts.getOrNull(1) ?: ""
        if (zone.isBlank() || deviceId.isBlank() || zoneId.isBlank()) null else RachioZoneMapping(zone, deviceId, zoneId)
    }
}

fun setRachioZoneMappings(context: Context, mappings: List<RachioZoneMapping>) {
    val raw = mappings.joinToString("|") { "${it.zone}=${it.deviceId}:${it.zoneId}" }
    setGardenScopedString(context, "rachio_device_mapping", raw)
}

/** Each user connects their own Rachio account via a personal API token — nothing is shared between installs. */
fun getRachioApiToken(context: Context): String = gardenScopedString(context, "rachio_api_token", "", credentialPrefs(context))
fun setRachioApiToken(context: Context, value: String) {
    setGardenScopedString(context, "rachio_api_token", value, credentialPrefs(context))
}

// ============================================================================
// AI PLANT IDENTIFICATION (Pl@ntNet API)
// ============================================================================

const val PLANTNET_API_KEY = BuildConfig.PLANTNET_API_KEY

// Free PlantNet accounts are capped at 500 requests/day, shared across every install of the app —
// without a per-device limit, a handful of trial users could exhaust that budget for everyone.
// Only real Pro-equivalent access (promo code or an explicit override) gets the full daily budget;
// the trial itself (and a lapsed trial with no promo/override) is capped much lower.
const val PLANTNET_TRIAL_DAILY_LIMIT = 5
const val PLANTNET_PRO_DAILY_LIMIT = 500

private fun plantIdPrefs(context: Context) = context.getSharedPreferences("garden_mapper_plant_id_prefs", Context.MODE_PRIVATE)

private fun todayKey(): String {
    val cal = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}

fun plantIdDailyLimit(context: Context): Int =
    when (EntitlementManager.getCached(context).source) {
        EntitlementSource.PROMO_CODE, EntitlementSource.OVERRIDE -> PLANTNET_PRO_DAILY_LIMIT
        else -> PLANTNET_TRIAL_DAILY_LIMIT
    }

fun plantIdCallsUsedToday(context: Context): Int {
    val prefs = plantIdPrefs(context)
    return if (prefs.getString("id_day", null) == todayKey()) prefs.getInt("id_count", 0) else 0
}

private fun recordPlantIdCall(context: Context) {
    val prefs = plantIdPrefs(context)
    val today = todayKey()
    val current = if (prefs.getString("id_day", null) == today) prefs.getInt("id_count", 0) else 0
    prefs.edit().putString("id_day", today).putInt("id_count", current + 1).apply()
}

sealed class PlantIdResult {
    data class Success(val commonName: String, val scientificName: String) : PlantIdResult()
    data object Failed : PlantIdResult()
    data class DailyLimitReached(val limit: Int, val isProLimit: Boolean) : PlantIdResult()
}

suspend fun identifyPlantFromUri(context: Context, uri: Uri): PlantIdResult {
    val limit = plantIdDailyLimit(context)
    if (plantIdCallsUsedToday(context) >= limit) {
        return PlantIdResult.DailyLimitReached(limit, isProLimit = limit == PLANTNET_PRO_DAILY_LIMIT)
    }
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()

            // Dropbox-chosen photos are stored as a remote https:// link, not a local content
            // URI — the ContentResolver can't read those, so fetch the bytes over the network instead.
            val bytes = if (uri.scheme == "http" || uri.scheme == "https") {
                client.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext PlantIdResult.Failed
                    response.body?.bytes()
                } ?: return@withContext PlantIdResult.Failed
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext PlantIdResult.Failed
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "images", "photo.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .addFormDataPart("organs", "auto")
                .build()

            val request = Request.Builder()
                .url("https://my-api.plantnet.org/v2/identify/all?api-key=$PLANTNET_API_KEY")
                .post(requestBody)
                .build()

            recordPlantIdCall(context) // counts against the daily budget regardless of outcome — it's still a billed PlantNet call

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext PlantIdResult.Failed
                val body = response.body?.string() ?: return@withContext PlantIdResult.Failed
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return@withContext PlantIdResult.Failed
                if (results.length() == 0) return@withContext PlantIdResult.Failed

                val top = results.getJSONObject(0)
                val species = top.optJSONObject("species")
                val sciName = species?.optString("scientificNameWithoutAuthor") ?: ""
                val commonNames = species?.optJSONArray("commonNames")
                val commonName = if (commonNames != null && commonNames.length() > 0) {
                    commonNames.getString(0)
                } else sciName

                PlantIdResult.Success(commonName, sciName)
            }
        } catch (_: Exception) {
            PlantIdResult.Failed
        }
    }
}

// ============================================================================
// NOTIFICATIONS/REMINDERS
// ============================================================================

private fun gardenPrefs(context: Context) = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)

/**
 * Per-garden settings (hemisphere, plant notifications, weather-aware reminders, garden
 * address/zones, custom map, irrigation setup) are stored under a key suffixed by the active
 * garden's id, so switching gardens in Help's picker swaps out an entirely different set of
 * values. The FIRST read for the device's OWN ORIGINAL default garden (gardenId == installId)
 * falls back to the legacy unscoped key — this is what makes the switch from single-garden to
 * multi-garden invisible to existing users: their current settings simply become their default
 * garden's settings, with no explicit migration step.
 *
 * That fallback deliberately does NOT extend to any other garden (one you just created, or one
 * you joined) — those never had a "legacy" value to inherit, so a not-yet-configured setting on a
 * brand-new garden must show its own plain default (blank/off), not silently reuse whatever your
 * default garden happens to have. Skipping this distinction was a real bug: a freshly created or
 * joined garden showed the owner's existing custom map image and Tuya/Rachio credentials/zones
 * before either had ever been set for it, which looked exactly like cross-garden data leakage even
 * though nothing had actually been written to the new garden's own key yet.
 */
/** [gardenIdOverride] lets a caller read/write a SPECIFIC garden's setting regardless of which garden is currently active — used by WateringReminderWorker/widgets to apply each garden's own settings to that garden's plants, without touching the live ActiveGardenState singleton (which would risk a visible flicker if the UI happened to be open at the same moment a background worker runs). */
private fun gardenScopedKey(context: Context, baseKey: String, gardenIdOverride: String? = null) = "$baseKey.${gardenIdOverride ?: effectiveGardenId(context)}"

private fun canFallBackToLegacyKey(context: Context, gardenIdOverride: String? = null) = (gardenIdOverride ?: effectiveGardenId(context)) == getOrCreateInstallId(context)

private fun gardenScopedBoolean(context: Context, baseKey: String, default: Boolean, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): Boolean {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getBoolean(scopedKey, default)
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) prefs.getBoolean(baseKey, default) else default
}
private fun setGardenScopedBoolean(context: Context, baseKey: String, value: Boolean, prefs: android.content.SharedPreferences = gardenPrefs(context)) {
    prefs.edit().putBoolean(gardenScopedKey(context, baseKey), value).apply()
}
private fun gardenScopedInt(context: Context, baseKey: String, default: Int, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): Int {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getInt(scopedKey, default)
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) prefs.getInt(baseKey, default) else default
}
private fun setGardenScopedInt(context: Context, baseKey: String, value: Int, prefs: android.content.SharedPreferences = gardenPrefs(context)) {
    prefs.edit().putInt(gardenScopedKey(context, baseKey), value).apply()
}
private fun gardenScopedFloat(context: Context, baseKey: String, default: Float, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): Float {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getFloat(scopedKey, default)
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) prefs.getFloat(baseKey, default) else default
}
private fun setGardenScopedFloat(context: Context, baseKey: String, value: Float, prefs: android.content.SharedPreferences = gardenPrefs(context)) {
    prefs.edit().putFloat(gardenScopedKey(context, baseKey), value).apply()
}
/** [prefs] lets a scoped setting live in a different backing file than "garden_mapper_prefs" (e.g. credentialPrefs for Tuya/Rachio secrets) while still keying off the same active-garden id. */
private fun gardenScopedString(context: Context, baseKey: String, default: String, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): String {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getString(scopedKey, default) ?: default
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) (prefs.getString(baseKey, default) ?: default) else default
}
private fun setGardenScopedString(context: Context, baseKey: String, value: String, prefs: android.content.SharedPreferences = gardenPrefs(context)) {
    prefs.edit().putString(gardenScopedKey(context, baseKey), value).apply()
}

/**
 * Auto-detected from the garden's coordinates (latitude >= 0 is Northern) whenever an address has
 * been set, rather than a separate manual toggle the user has to remember to keep in sync with
 * where their garden actually is — this is what drives the seasonal-watering override's idea of
 * "summer" vs "winter". Falls back to the old manually-stored value (default Southern, matching
 * every install's behaviour before this setting existed) only when no garden address/coordinates
 * have been configured yet, so a garden with no address still has SOME sensible current behaviour.
 */
fun getHemisphere(context: Context): Hemisphere {
    getGardenLatLng(context)?.let { (lat, _) -> return if (lat >= 0) Hemisphere.NORTHERN else Hemisphere.SOUTHERN }
    val raw = gardenScopedString(context, "garden_hemisphere", Hemisphere.SOUTHERN.name)
    return if (raw == Hemisphere.NORTHERN.name) Hemisphere.NORTHERN else Hemisphere.SOUTHERN
}
/** Manual fallback only — once a garden address is set, getHemisphere() derives from its coordinates instead and ignores this. Kept so a garden with no address yet still has a settable default. */
fun setHemisphere(context: Context, value: Hemisphere) {
    setGardenScopedString(context, "garden_hemisphere", value.name)
    HemisphereState.value = getHemisphere(context)
}
/** Reads a SPECIFIC garden's hemisphere regardless of which garden is currently active — see gardenScopedKey's gardenIdOverride. Used by WateringReminderWorker to apply each garden's own hemisphere to that garden's plants when checking due dates across every garden this device has access to, not just the active one. */
fun getHemisphereFor(context: Context, gardenId: String): Hemisphere {
    getGardenLatLngFor(context, gardenId)?.let { (lat, _) -> return if (lat >= 0) Hemisphere.NORTHERN else Hemisphere.SOUTHERN }
    val raw = gardenScopedString(context, "garden_hemisphere", Hemisphere.SOUTHERN.name, gardenIdOverride = gardenId)
    return if (raw == Hemisphere.NORTHERN.name) Hemisphere.NORTHERN else Hemisphere.SOUTHERN
}

fun getNotificationsEnabled(context: Context): Boolean = gardenScopedBoolean(context, "notifications_enabled", false)
fun setNotificationsEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "notifications_enabled", value)
fun getNotificationsEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "notifications_enabled", false, gardenIdOverride = gardenId)

/** "lockscreen", "popup", or "both" */
fun getNotificationStyle(context: Context): String = gardenScopedString(context, "notification_style", "lockscreen")
fun setNotificationStyle(context: Context, value: String) = setGardenScopedString(context, "notification_style", value)

/** Comma-separated "days before due" offsets, e.g. "0,2" = day-of AND 2 days before */
fun getNotificationOffsets(context: Context): Set<Int> {
    val raw = gardenScopedString(context, "notification_offsets", "0")
    return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(0) }
}
fun setNotificationOffsets(context: Context, offsets: Set<Int>) =
    setGardenScopedString(context, "notification_offsets", offsets.sorted().joinToString(","))
fun getNotificationOffsetsFor(context: Context, gardenId: String): Set<Int> {
    val raw = gardenScopedString(context, "notification_offsets", "0", gardenIdOverride = gardenId)
    return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(0) }
}

fun getOverdueRepeatEnabled(context: Context): Boolean = gardenScopedBoolean(context, "overdue_repeat_enabled", true)
fun setOverdueRepeatEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "overdue_repeat_enabled", value)
fun getOverdueRepeatEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "overdue_repeat_enabled", true, gardenIdOverride = gardenId)
fun getOverdueRepeatDays(context: Context): Int = gardenScopedInt(context, "overdue_repeat_days", 3)
fun setOverdueRepeatDays(context: Context, value: Int) = setGardenScopedInt(context, "overdue_repeat_days", value.coerceAtLeast(1))
fun getOverdueRepeatDaysFor(context: Context, gardenId: String): Int =
    gardenScopedInt(context, "overdue_repeat_days", 3, gardenIdOverride = gardenId)

fun getFertiliseRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "fertilise_reminders_enabled", false)
fun setFertiliseRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "fertilise_reminders_enabled", value)
fun getFertiliseRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "fertilise_reminders_enabled", false, gardenIdOverride = gardenId)
fun getPruneRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "prune_reminders_enabled", false)
fun setPruneRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "prune_reminders_enabled", value)
fun getPruneRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "prune_reminders_enabled", false, gardenIdOverride = gardenId)
fun getFeedRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "feed_reminders_enabled", false)
fun setFeedRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "feed_reminders_enabled", value)
fun getFeedRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "feed_reminders_enabled", false, gardenIdOverride = gardenId)

fun getNotificationHour(context: Context): Int = gardenScopedInt(context, "notification_hour", 8)
fun getNotificationMinute(context: Context): Int = gardenScopedInt(context, "notification_minute", 0)
fun setNotificationTime(context: Context, hour: Int, minute: Int) {
    setGardenScopedInt(context, "notification_hour", hour)
    setGardenScopedInt(context, "notification_minute", minute)
}

/** Next occurrence (today if still ahead, else tomorrow) of the saved notification time. */
fun nextWateringAlarmTarget(context: Context): Long {
    val target = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, getNotificationHour(context))
        set(java.util.Calendar.MINUTE, getNotificationMinute(context))
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (before(java.util.Calendar.getInstance())) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return target.timeInMillis
}

private fun wateringAlarmPendingIntent(context: Context): android.app.PendingIntent {
    val intent = android.content.Intent(context, WateringReminderReceiver::class.java)
    return android.app.PendingIntent.getBroadcast(
        context, 2001, intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Uses an exact AlarmManager alarm rather than a periodic WorkManager job — periodic work's
 * initial delay is only a lower bound and the OS can defer it well past the requested time
 * (especially under Doze/App Standby), so reminders would silently miss the configured time.
 * The receiver re-arms the next day's alarm each time it fires, and BootReceiver re-arms it
 * after a reboot since exact alarms don't survive a restart.
 */
fun scheduleWateringReminders(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val pendingIntent = wateringAlarmPendingIntent(context)
    val targetMillis = nextWateringAlarmTarget(context)
    val canScheduleExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    if (canScheduleExact) {
        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
    } else {
        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
    }
}
fun cancelWateringReminders(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    alarmManager.cancel(wateringAlarmPendingIntent(context))
}

// ============================================================================
// WEATHER-AWARE REMINDER SKIPPING
// ============================================================================

fun getGardenLatLng(context: Context): Pair<Double, Double>? {
    val lat = gardenScopedString(context, "garden_lat", "").toDoubleOrNull()
    val lng = gardenScopedString(context, "garden_lng", "").toDoubleOrNull()
    return if (lat != null && lng != null) lat to lng else null
}
fun getGardenLatLngFor(context: Context, gardenId: String): Pair<Double, Double>? {
    val lat = gardenScopedString(context, "garden_lat", "", gardenIdOverride = gardenId).toDoubleOrNull()
    val lng = gardenScopedString(context, "garden_lng", "", gardenIdOverride = gardenId).toDoubleOrNull()
    return if (lat != null && lng != null) lat to lng else null
}
fun setGardenLatLng(context: Context, lat: Double, lng: Double) {
    setGardenScopedString(context, "garden_lat", lat.toString())
    setGardenScopedString(context, "garden_lng", lng.toString())
    // This always writes to whichever garden is currently active (setGardenScopedString resolves via
    // effectiveGardenId internally, same as getHemisphere() below) — hemisphere is now derived from
    // these coordinates, so refresh the reactive singleton immediately whenever they change, whether
    // from the user picking an address locally or a sync pulling down the owner's address for the
    // first time, so anything reading HemisphereState (dashboard/list/audit "due" status) doesn't
    // wait for an unrelated recomposition.
    HemisphereState.value = getHemisphere(context)
    GardenAddressState.latLng = lat to lng
}
private const val MAP_FALLBACK_LAT = 40.785091
private const val MAP_FALLBACK_LNG = -73.968285

/**
 * Where the user last left the real map's camera (pan/zoom) — read as the map's initial position so
 * it doesn't reset to the garden address every time the tab is reopened. Self-heals a specific past
 * bug (fixed, but already-affected devices still carry the bad saved value): the camera-persistence
 * effect used to fire on its very first "settled" callback — an artifact of initial composition, not
 * a real pan — which locked in whatever fallback position the map happened to start at (this exact
 * hardcoded coordinate) before the real garden address or plant-marker auto-fit ever got a chance to
 * run, permanently shadowing them from then on. A genuine user pan landing on this exact coordinate
 * is practically impossible, so treating it as "nothing saved yet" lets a real address/auto-fit apply.
 */
fun getMapCameraPosition(context: Context): Triple<Double, Double, Float>? {
    val lat = gardenScopedString(context, "map_camera_lat", "").toDoubleOrNull()
    val lng = gardenScopedString(context, "map_camera_lng", "").toDoubleOrNull()
    val zoom = gardenScopedFloat(context, "map_camera_zoom", -1f).takeIf { it > 0f }
    if (lat == null || lng == null || zoom == null) return null
    if (lat == MAP_FALLBACK_LAT && lng == MAP_FALLBACK_LNG) return null
    return Triple(lat, lng, zoom)
}
fun setMapCameraPosition(context: Context, lat: Double, lng: Double, zoom: Float) {
    setGardenScopedString(context, "map_camera_lat", lat.toString())
    setGardenScopedString(context, "map_camera_lng", lng.toString())
    setGardenScopedFloat(context, "map_camera_zoom", zoom)
}
fun getGardenAddress(context: Context): String = gardenScopedString(context, "garden_address", "")
fun setGardenAddress(context: Context, address: String) {
    setGardenScopedString(context, "garden_address", address)
    GardenAddressState.address = address
}
/** Null distinguishes "never set up" (seed from existing plants' locations) from "explicitly emptied". */
fun getGardenLocations(context: Context): List<String>? {
    val prefs = gardenPrefs(context)
    val scopedKey = gardenScopedKey(context, "garden_locations")
    // Unlike gardenScopedString, this used to fall back to the legacy unscoped key unconditionally
    // whenever the scoped key didn't exist yet — even for a garden that isn't this device's own
    // default one (see feedback_garden_scoped_setting_fallback). That meant a member viewing a
    // shared garden before its zones had ever synced down saw THEIR OWN pre-existing legacy zone
    // list instead of nothing, which getOrSeedGardenLocations then trusted as the real value and
    // never replaced with the owner's actual synced zones. Gated the same way every other scoped
    // getter already is.
    val raw = (if (prefs.contains(scopedKey)) prefs.getString(scopedKey, null) else if (canFallBackToLegacyKey(context)) prefs.getString("garden_locations", null) else null) ?: return null
    return raw.split("").filter { it.isNotBlank() }
}
fun setGardenLocations(context: Context, locations: List<String>) {
    GardenAddressState.locations = locations
    setGardenScopedString(context, "garden_locations", locations.joinToString(""))
}
/**
 * Reads the managed garden-locations list, seeding it from existing plants' distinct locations the
 * first time anything asks (so nothing is orphaned for a garden that predates this feature). Only
 * PERSISTS that seed for the garden's owner — a non-owner member's local seed must stay transient
 * (recomputed fresh each call, never written) until the real synced value arrives from the owner.
 * Persisting it immediately was a real bug: GardenSyncClient only ever sends this device's local
 * `gardenLocations` up during sync when it's non-null, specifically so an untouched device doesn't
 * overwrite the shared value before it's received the real one — but merely OPENING "Garden zones"
 * to look at it (before any sync had completed) counted as "touched", silently persisting a seed
 * built from whatever plants happened to be locally synced so far (often none yet), which then
 * became this device's own committed local value and could get pushed up on the very next sync,
 * clobbering the owner's real zone list. A write-permission member adding/renaming/removing a zone
 * still persists normally, since that goes through setGardenLocations directly — only the passive
 * read-triggered seed is affected.
 */
fun getOrSeedGardenLocations(context: Context, plants: List<PlantEntity>): List<String> {
    getGardenLocations(context)?.let { return it }
    val seeded = plants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted()
    if (isOwnerOfActiveGarden(context)) setGardenLocations(context, seeded)
    return seeded
}
fun getWeatherSkipEnabled(context: Context): Boolean = gardenScopedBoolean(context, "weather_skip_enabled", false)
fun setWeatherSkipEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "weather_skip_enabled", value)
fun getWeatherSkipEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "weather_skip_enabled", false, gardenIdOverride = gardenId)
fun getRainProbabilityThreshold(context: Context): Int = gardenScopedInt(context, "rain_probability_threshold", 60)
fun setRainProbabilityThreshold(context: Context, value: Int) = setGardenScopedInt(context, "rain_probability_threshold", value)
fun getRainProbabilityThresholdFor(context: Context, gardenId: String): Int =
    gardenScopedInt(context, "rain_probability_threshold", 60, gardenIdOverride = gardenId)
/** Minimum forecast rainfall (mm) required before a reminder is flagged — filters out high-probability drizzle. */
fun getRainAmountThreshold(context: Context): Float = gardenScopedFloat(context, "rain_amount_threshold_mm", 1.0f)
fun setRainAmountThreshold(context: Context, value: Float) = setGardenScopedFloat(context, "rain_amount_threshold_mm", value)
fun getRainAmountThresholdFor(context: Context, gardenId: String): Float =
    gardenScopedFloat(context, "rain_amount_threshold_mm", 1.0f, gardenIdOverride = gardenId)

// ============================================================================
// WATER USAGE & COST
// ============================================================================

/** Dollars per kiloliter (1000L) — 0.0 means the user hasn't set a rate yet. */
fun getWaterRatePerKiloliter(context: Context): Double {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getFloat("water_rate_per_kl", 0f).toDouble()
}
fun setWaterRatePerKiloliter(context: Context, value: Double) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putFloat("water_rate_per_kl", value.toFloat()).apply()
}

// ============================================================================
// FIRST-PLANT NOTIFICATION HINT
// ============================================================================

/** Whether the one-time "set up reminders" hint has already been shown, device-wide (never re-shown after that). */
fun hasShownNotificationHint(context: Context): Boolean {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("notification_hint_shown", false)
}
fun setNotificationHintShown(context: Context) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("notification_hint_shown", true).apply()
}

// ============================================================================
// DROPBOX CLOUD PHOTO STORAGE
// ============================================================================

const val DROPBOX_APP_KEY = BuildConfig.DROPBOX_APP_KEY

fun getDropboxAccessToken(context: Context): String? = migrateCredential(context, "dropbox_access_token")

fun getDropboxRefreshToken(context: Context): String? = migrateCredential(context, "dropbox_refresh_token")

fun saveDropboxTokens(context: Context, accessToken: String?, refreshToken: String?, savedAtMillis: Long) {
    credentialPrefs(context).edit()
        .putString("dropbox_access_token", accessToken)
        .putString("dropbox_refresh_token", refreshToken)
        .putLong("dropbox_token_saved_at", savedAtMillis)
        .apply()
}

fun clearDropboxTokens(context: Context) {
    saveDropboxTokens(context, null, null, 0L)
}

/** Dropbox short-lived tokens last ~4 hours; refresh proactively a bit before that. */
private const val DROPBOX_TOKEN_REFRESH_AFTER_MS = 3L * 60 * 60 * 1000

const val SUPPORT_LINK_URL = "https://www.buymeacoffee.com/sagegarden"

suspend fun ensureDropboxTokenFresh(context: Context) = withContext(Dispatchers.IO) {
    val refreshToken = getDropboxRefreshToken(context) ?: return@withContext
    val savedAt = credentialPrefs(context).getLong("dropbox_token_saved_at", 0L)
    if (System.currentTimeMillis() - savedAt < DROPBOX_TOKEN_REFRESH_AFTER_MS) return@withContext

    try {
        val client = OkHttpClient()
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", DROPBOX_APP_KEY)
            .build()
        val request = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(formBody).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val newAccessToken = json.optString("access_token").takeIf { it.isNotBlank() }
                    if (newAccessToken != null) {
                        saveDropboxTokens(context, newAccessToken, refreshToken, System.currentTimeMillis())
                    }
                }
            }
        }
    } catch (_: Exception) { /* keep the existing token; the next Dropbox call will surface any real problem */ }
}

/** Builds a Dropbox client, refreshing the access token first if it's due to expire soon. */
suspend fun getDropboxClient(context: Context): DbxClientV2? = withContext(Dispatchers.IO) {
    ensureDropboxTokenFresh(context)
    val token = getDropboxAccessToken(context) ?: return@withContext null
    val requestConfig = DbxRequestConfig.newBuilder("SageGarden/1.0").build()
    DbxClientV2(requestConfig, token)
}

fun startDropboxSignIn(context: Context) {
    val requestConfig = DbxRequestConfig.newBuilder("SageGarden/1.0").build()
    Auth.startOAuth2PKCE(
        context, DROPBOX_APP_KEY, requestConfig,
        listOf("files.content.write", "files.content.read", "sharing.write")
    )
}

object DropboxLinkState {
    var linking by mutableStateOf(false)
        private set
    var current by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var result by mutableStateOf<String?>(null)
        private set

    fun start() {
        linking = true
        current = 0
        total = 0
    }
    fun updateProgress(c: Int, t: Int) {
        current = c
        total = t
    }
    fun finish(message: String) {
        linking = false
        result = message
    }
}

object PendingNotificationState {
    var type by mutableStateOf<String?>(null)
}

object PendingPlantEditState {
    var plantId by mutableStateOf<String?>(null)
}

/** Set before navigating to Help from the Map tab's "no garden address set" guidance, so the Weather-aware reminders section (where the address field lives) opens already expanded. Reset once HelpScreen consumes it. */
object PendingHelpFocusState {
    var focusWeatherSection by mutableStateOf(false)
}

object SageFabResetState {
    var requested by mutableStateOf(false)
}

object DropboxAuthState {
    var token by mutableStateOf<String?>(null)
        private set

    fun refresh(context: Context) {
        token = getDropboxAccessToken(context)
    }

    fun checkAndRefresh(context: Context) {
        checkDropboxAuthResult(context)
        refresh(context)
    }

    fun clear(context: Context) {
        clearDropboxTokens(context)
        token = null
    }
}

/** Call this once, e.g. in a LaunchedEffect on HelpScreen, to pick up a completed sign-in. */
fun checkDropboxAuthResult(context: Context) {
    val credential = Auth.getDbxCredential() ?: return
    saveDropboxTokens(context, credential.accessToken, credential.refreshToken, System.currentTimeMillis())
}

/** Uploads a local photo to the app's Dropbox folder and returns a direct-viewable link, or null on failure. */
suspend fun uploadPhotoToDropbox(context: Context, localUri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val fileName = "/garden_${System.currentTimeMillis()}.jpg"
            client.files().uploadBuilder(fileName).uploadAndFinish(bytes.inputStream())
            val sharedLink = client.sharing().createSharedLinkWithSettings(fileName)
            toDirectDropboxLink(sharedLink.url)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Fetches (or creates) a direct shared link for [filePath], which must already exist in Dropbox.
 * Dropbox's sharing API can lag briefly right after a file is first uploaded — a fallback lookup
 * immediately after a failed [DbxClientV2.sharing]`.createSharedLinkWithSettings` can itself come
 * back empty even though the file is genuinely there, which used to be reported as a total upload
 * failure despite the file having actually been saved (leaving that filename permanently used up,
 * so the next attempt uploaded a duplicate under an incremented name instead of reusing it). Retries
 * the lookup a few times with a short delay before giving up.
 */
private suspend fun fetchOrCreateSharedLink(client: DbxClientV2, filePath: String): String? {
    // Retries the WHOLE create-then-lookup-fallback sequence, not just the fallback lookup — the
    // first version only retried the lookup after a single failed create attempt, on the theory
    // that the create call fails because a link already exists (just briefly not visible to the
    // lookup yet). Confirmed on-device 2026-08-29 that this isn't the whole story: an upload can
    // still be reported as failed despite genuinely succeeding, which means the CREATE call itself
    // can be the one failing transiently (network hiccup, transient server error) — in which case
    // no link was ever created, so retrying only the lookup finds nothing no matter how many times
    // it's tried. Retrying the create call too gives it a real second chance to succeed.
    val delaysMs = longArrayOf(500L, 1000L, 1500L, 2000L)
    repeat(5) { attempt ->
        val link = try {
            client.sharing().createSharedLinkWithSettings(filePath).url
        } catch (e: Exception) {
            val fallback = try {
                client.sharing().listSharedLinksBuilder().withPath(filePath).withDirectOnly(true).start()
                    .links.firstOrNull()?.url
            } catch (_: Exception) {
                null
            }
            if (fallback == null) {
                android.util.Log.w("DropboxLink", "fetchOrCreateSharedLink attempt ${attempt + 1}/5 failed for path='$filePath'", e)
            }
            fallback
        }
        if (link != null) return link
        if (attempt < delaysMs.size) kotlinx.coroutines.delay(delaysMs[attempt])
    }
    return null
}

/**
 * Figures out what filename [uploadPhotoToDropboxAsPlantId] will actually use for a given plant ID,
 * without uploading anything — the plant's *first* photo gets the bare ID ("P0056.jpg"); every
 * subsequent upload for the same ID (replacing a photo when editing) gets the next unused "_N"
 * suffix ("P0056_1.jpg", "P0056_2.jpg", ...). Shared by the upload function itself and by the
 * button text that previews the target name before the user commits to uploading. Returns null if
 * Dropbox isn't reachable (folder listing failed) — callers should fall back to showing the bare
 * plant ID in that case, same as before this preview existed.
 */
suspend fun previewDropboxUploadName(context: Context, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val listing = client.files().listFolder(folderPath.ifBlank { "" })
            val existingNames = listing.entries.mapNotNull { (it as? com.dropbox.core.v2.files.FileMetadata)?.name }.toMutableSet()
            // listFolder truncates at a page limit and sets hasMore=true if the folder has more
            // entries than that — without paging through listFolderContinue, a large photo folder
            // could silently miss files that DO exist, which previously showed as available to reuse.
            var cursor = listing.cursor
            var hasMore = listing.hasMore
            while (hasMore) {
                val more = client.files().listFolderContinue(cursor)
                existingNames += more.entries.mapNotNull { (it as? com.dropbox.core.v2.files.FileMetadata)?.name }
                cursor = more.cursor
                hasMore = more.hasMore
            }
            val bareName = "$plantId.jpg"
            android.util.Log.d("DropboxUploadName", "folder='$folderPath' entries=${existingNames.size} bareNameExists=${bareName in existingNames}")
            if (bareName !in existingNames) {
                bareName
            } else {
                var suffix = 1
                while ("${plantId}_$suffix.jpg" in existingNames) suffix++
                "${plantId}_$suffix.jpg"
            }
        } catch (e: Exception) {
            android.util.Log.w("DropboxUploadName", "preview failed for $plantId", e)
            null
        }
    }
}

/**
 * Uploads a local photo to the configured Dropbox photo folder under the name [previewDropboxUploadName]
 * computes for [plantId], so the file is recognisable in Dropbox itself.
 */
suspend fun uploadPhotoToDropboxAsPlantId(context: Context, localUri: Uri, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val targetName = previewDropboxUploadName(context, plantId) ?: return@withContext null

            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val filePath = "$folderPath/$targetName".replace("//", "/")
            client.files().uploadBuilder(filePath).uploadAndFinish(bytes.inputStream())
            // The upload itself (above) can succeed while getting a shareable link fails/lags —
            // see fetchOrCreateSharedLink for why this is retried rather than reported as a hard
            // failure immediately.
            fetchOrCreateSharedLink(client, filePath)?.let { toDirectDropboxLink(it) }
        } catch (_: Exception) {
            null
        }
    }
}

fun sanitizeForDropboxFilename(raw: String): String =
    raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "zone" }

/**
 * Progress (zone) photos have no plant ID to derive a Dropbox filename from, unlike
 * [previewDropboxUploadName] — so they use "progress_<zone>_<N>.jpg" instead, always suffixed
 * starting at _1 (unlike the plant-photo convention's bare name for the first upload, since
 * "progress_frontyard.jpg" alone wouldn't obviously read as belonging to this app/feature).
 */
suspend fun previewProgressPhotoDropboxUploadName(context: Context, location: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val baseName = "progress_${sanitizeForDropboxFilename(location)}"
            val existingNames = client.files().listFolder(folderPath.ifBlank { "" })
                .entries.mapNotNull { (it as? com.dropbox.core.v2.files.FileMetadata)?.name }
                .toSet()
            var suffix = 1
            while ("${baseName}_$suffix.jpg" in existingNames) suffix++
            "${baseName}_$suffix.jpg"
        } catch (_: Exception) {
            null
        }
    }
}

/** Uploads a local progress photo to the configured Dropbox photo folder under the name
 * [previewProgressPhotoDropboxUploadName] computes for [location]. Mirrors [uploadPhotoToDropboxAsPlantId]. */
suspend fun uploadPhotoToDropboxAsProgressPhoto(context: Context, localUri: Uri, location: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val targetName = previewProgressPhotoDropboxUploadName(context, location) ?: return@withContext null

            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val filePath = "$folderPath/$targetName".replace("//", "/")
            client.files().uploadBuilder(filePath).uploadAndFinish(bytes.inputStream())
            fetchOrCreateSharedLink(client, filePath)?.let { toDirectDropboxLink(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The classic `?raw=1` query-param swap on www.dropbox.com reliably serves inline image bytes for
 * Dropbox's older `/s/<hash>/...` share-link format, but is known to be unreliable for the newer
 * `/scl/fi/<id>/...?rlkey=...` format Dropbox's UI now generates — that format needs the request
 * served from the dl.dropboxusercontent.com CDN domain instead, not just a query tweak on
 * www.dropbox.com. Swapping the host (rather than stripping/rebuilding query params) preserves
 * rlkey and everything else a scl-format link needs to stay valid.
 */
fun toDirectDropboxLink(url: String): String {
    val onDirectHost = url
        .replace("://www.dropbox.com", "://dl.dropboxusercontent.com")
        .replace("://dropbox.com", "://dl.dropboxusercontent.com")
    return when {
        onDirectHost.contains("?dl=0") -> onDirectHost.replace("?dl=0", "?raw=1")
        onDirectHost.contains("&dl=0") -> onDirectHost.replace("&dl=0", "&raw=1")
        onDirectHost.contains("dl=0") -> onDirectHost.replace("dl=0", "raw=1")
        onDirectHost.contains("?") -> "$onDirectHost&raw=1"
        else -> "$onDirectHost?raw=1"
    }
}

fun getLocalPhotoFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("local_photo_folder_uri", null)?.let { Uri.parse(it) }
}
fun setLocalPhotoFolderUri(context: Context, uri: Uri?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("local_photo_folder_uri", uri?.toString()).apply()
}

/** Matches files whose name (minus extension) equals a Plant ID. Skips photos already linked. */
suspend fun autoLinkLocalPhotos(
    context: Context,
    folderUri: Uri,
    plants: List<PlantEntity>,
    onPlantUpdated: suspend (PlantEntity) -> Unit
): Int = withContext(Dispatchers.IO) {
    val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext 0
    val plantsById = plants.associateBy { it.id.lowercase() }
    var linkedCount = 0

    folder.listFiles().forEach { file ->
        val displayName = file.name ?: return@forEach
        if (!file.type.orEmpty().startsWith("image/")) return@forEach

        val baseName = displayName.substringBeforeLast(".")
        val plant = plantsById[baseName.lowercase()] ?: return@forEach

        val alreadyLinked = plant.photoUris.any { existing ->
            Uri.parse(existing).lastPathSegment?.substringAfterLast("/") == displayName
        }
        if (alreadyLinked) return@forEach

        try {
            context.contentResolver.takePersistableUriPermission(
                file.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* some providers don't support this — ignore */ }

        val updated = plant.copy(
            photoUris = plant.photoUris + file.uri.toString(),
            photoUri = plant.photoUri ?: file.uri.toString()
        )
        onPlantUpdated(updated)
        linkedCount++
    }
    linkedCount
}

/**
 * Where irrigation_log.csv is read/written — deliberately separate from the photo storage folder
 * above. Both used to silently default to the photo folder/path, which meant a user who'd never
 * explicitly thought about it would find their watering history mixed in with (or invisibly
 * shadowed by) wherever their photos happened to be configured to go, with no way to tell where it
 * actually ended up short of hunting through their photo folder. Null falls back to "(root)" for
 * Dropbox and blocks the local save with a clear "choose a folder first" message rather than a
 * silent no-op — see saveIrrigationCsvLocal/Dropbox.
 */
fun getIrrigationLogFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("irrigation_log_folder_uri", null)?.let { Uri.parse(it) }
}
fun setIrrigationLogFolderUri(context: Context, uri: Uri?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("irrigation_log_folder_uri", uri?.toString()).apply()
}
fun getIrrigationLogDropboxFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("irrigation_log_dropbox_folder_path", null)
}
fun setIrrigationLogDropboxFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("irrigation_log_dropbox_folder_path", path).apply()
}

/** Remembers the last Dropbox folder browsed to for a given zone's progress photos, so reopening
 * that zone's picker starts back where you left off — keyed per zone (not shared with any other
 * zone or with the main Photos & cloud storage Dropbox folder) since different zones' reference
 * photos often live in entirely different Dropbox folders. */
fun getProgressPhotoDropboxFolder(context: Context, location: String): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("progress_photo_dropbox_folder.$location", null)
}
fun setProgressPhotoDropboxFolder(context: Context, location: String, path: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("progress_photo_dropbox_folder.$location", path).apply()
}

fun getDropboxPhotoFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dropbox_photo_folder_path", null)
}
fun setDropboxPhotoFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dropbox_photo_folder_path", path).apply()
}
/** Null means "not set yet — falls back to the photo folder", so existing users keep their current behaviour. */
fun getDropboxBackupFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dropbox_backup_folder_path", null)
}
fun setDropboxBackupFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dropbox_backup_folder_path", path).apply()
}
/** Null means "not set yet — falls back to the backup folder, then the photo folder". */
fun getDropboxCsvFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dropbox_csv_folder_path", null)
}
fun setDropboxCsvFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dropbox_csv_folder_path", path).apply()
}
/** The device's own default garden keeps "sage_garden_plantdata.csv" ("plantdata" makes clear this
 * is only the plant CSV, not a full backup); any other garden gets a name-derived filename too,
 * same reasoning as BackupHelper.defaultBackupFileNameForGarden — otherwise two gardens exporting
 * CSV to the same Dropbox folder would silently overwrite each other's export. */
fun csvExportFileNameForGarden(context: Context, gardenId: String): String {
    if (gardenId.isBlank() || gardenId == getOrCreateInstallId(context)) return "sage_garden_plantdata.csv"
    val name = GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == gardenId }?.name ?: "garden"
    return "sage_garden_plantdata_${sanitizeForDropboxFilename(name)}.csv"
}
fun getLocalBackupFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("local_backup_folder_uri", null)?.let { Uri.parse(it) }
}
fun setLocalBackupFolderUri(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("local_backup_folder_uri", uri.toString()).apply()
}

data class DropboxLinkResult(val linkedCount: Int, val matchedCount: Int, val errorMessage: String? = null)

fun getLastDropboxLinkResult(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("last_dropbox_link_result", null)
}
fun setLastDropboxLinkResult(context: Context, result: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("last_dropbox_link_result", result).apply()
}

suspend fun autoLinkDropboxPhotos(
    context: Context,
    folderPath: String,
    plants: List<PlantEntity>,
    onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    onPlantUpdated: suspend (PlantEntity) -> Unit
): DropboxLinkResult = withContext(Dispatchers.IO) {
    val client = getDropboxClient(context) ?: return@withContext DropboxLinkResult(0, 0, "Dropbox isn't connected")
    val plantsById = plants.associateBy { it.id.trim().lowercase() }

    try {
        val allImageFiles = mutableListOf<com.dropbox.core.v2.files.FileMetadata>()
        var listResult = client.files().listFolder(folderPath)
        while (true) {
            allImageFiles.addAll(
                listResult.entries.filterIsInstance<com.dropbox.core.v2.files.FileMetadata>()
                    .filter { it.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") } }
            )
            if (!listResult.hasMore) break
            listResult = client.files().listFolderContinue(listResult.cursor)
        }

        val matched = allImageFiles.mapNotNull { file ->
            val baseName = file.name.substringBeforeLast(".").trim()
            plantsById[baseName.lowercase()]?.let { plant -> file to plant }
        }
        val totalMatched = matched.size
        var linkedCount = 0
        onProgress(0, totalMatched)

        matched.forEach { (file, plant) ->
            val name = file.name
            if (plant.photoUris.any { it.contains(name) }) {
                linkedCount++
                onProgress(linkedCount, totalMatched)
                return@forEach
            }
            val filePath = "$folderPath/$name".replace("//", "/")
            val link = try {
                toDirectDropboxLink(client.sharing().createSharedLinkWithSettings(filePath).url)
            } catch (_: Exception) {
                // withDirectOnly(true): see uploadPhotoToDropboxAsPlantId — without it this can pick
                // up an inherited link from an already-shared parent folder, which 404s as an image.
                client.sharing().listSharedLinksBuilder().withPath(filePath).withDirectOnly(true).start()
                    .links.firstOrNull()?.url?.let { toDirectDropboxLink(it) }
            }
            if (link != null) {
                onPlantUpdated(plant.copy(photoUris = plant.photoUris + link, photoUri = plant.photoUri ?: link))
                linkedCount++
            }
            onProgress(linkedCount, totalMatched)
        }
        DropboxLinkResult(linkedCount, totalMatched)
    } catch (e: Exception) {
        DropboxLinkResult(0, 0, e.message ?: "Unknown error")
    }
}

// ============================================================================
// IRRIGATION CSV PERSISTENCE (local folder or Dropbox — reuses photo storage settings)
// ============================================================================
// Tuya only retains ~7 days of device logs, so each sync's results are merged
// into a running irrigation_log.csv (deduped by zone+outlet+startTime) rather
// than overwritten, so history accumulates indefinitely.

val IRRIGATION_CSV_HEADERS = listOf("Zone", "Outlet", "StartTime", "DurationMinutes", "Source")

fun wateringEventsToCsv(events: List<WateringEvent>): String {
    val sb = StringBuilder()
    sb.append(IRRIGATION_CSV_HEADERS.joinToString(",")).append("\n")
    events.forEach { e ->
        val row = listOf(e.zone, e.outlet, e.startTime.toString(), e.durationMinutes.toString(), e.source)
            .joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        sb.append(row).append("\n")
    }
    return sb.toString()
}

sealed class CsvImportResult<out T> {
    data class Success<T>(val items: List<T>, val skippedRows: Int) : CsvImportResult<T>()
    data class MissingColumns(val missing: List<String>, val expected: List<String>) : CsvImportResult<Nothing>()
    data class EmptyFile(val expected: List<String>) : CsvImportResult<Nothing>()
}

data class CsvImportOutcome(val title: String, val message: String)

/** Case-insensitive, order-independent column lookup — a manually edited or re-saved
 *  CSV often reorders or re-cases headers, and this shouldn't break the import. */
private fun csvFindValue(headers: List<String>, cells: List<String>, key: String): String? {
    val idx = headers.indexOfFirst { it.equals(key, ignoreCase = true) }
    return if (idx >= 0 && idx < cells.size) cells[idx] else null
}

private val IRRIGATION_DATE_FORMATS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "dd/MM/yyyy HH:mm:ss",
    "dd/MM/yyyy HH:mm",
    "M/d/yyyy H:mm"
)

/** Accepts either epoch milliseconds or a recognised DateTime string; returns null if neither matches. */
fun parseFlexibleDateTime(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    trimmed.toLongOrNull()?.let { return it }
    for (pattern in IRRIGATION_DATE_FORMATS) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            sdf.isLenient = false
            return sdf.parse(trimmed)?.time
        } catch (_: Exception) { /* try next pattern */ }
    }
    return null
}
fun parseIrrigationCsv(text: String): CsvImportResult<WateringEvent> {
    val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
    val required = listOf("Zone", "StartTime", "DurationMinutes")
    if (lines.isEmpty()) return CsvImportResult.EmptyFile(IRRIGATION_CSV_HEADERS)

    val delimiter = detectCsvDelimiter(lines[0])
    val headers = parseCsvLine(lines[0], delimiter).map { it.trim().trim('"') }
    val missing = required.filter { req -> headers.none { it.equals(req, ignoreCase = true) } }
    if (missing.isNotEmpty()) return CsvImportResult.MissingColumns(missing, IRRIGATION_CSV_HEADERS)
    if (lines.size < 2) return CsvImportResult.Success(emptyList(), 0)

    val out = mutableListOf<WateringEvent>()
    var skipped = 0
    for (i in 1 until lines.size) {
        val cells = parseCsvLine(lines[i], delimiter)   // was: parseCsvLine(lines[i])
        val zone = csvFindValue(headers, cells, "Zone")
        val outlet = csvFindValue(headers, cells, "Outlet") ?: "1"
        val start = csvFindValue(headers, cells, "StartTime")?.let { parseFlexibleDateTime(it) }
        val duration = csvFindValue(headers, cells, "DurationMinutes")?.trim()?.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
        if (zone.isNullOrBlank() || start == null || duration == null) { skipped++; continue }
        out.add(
            WateringEvent(
                id = "$zone-$outlet-$start", zone = zone, outlet = outlet,
                startTime = start, durationMinutes = duration,
                source = csvFindValue(headers, cells, "Source") ?: "Tuya"
            )
        )
    }
    return CsvImportResult.Success(out, skipped)
}

fun csvImportResultToOutcome(result: CsvImportResult<WateringEvent>): CsvImportOutcome = when (result) {
    is CsvImportResult.Success -> CsvImportOutcome(
        "Import complete",
        if (result.skippedRows > 0)
            "Imported ${result.items.size} irrigation event(s). Skipped ${result.skippedRows} row(s) with missing or invalid data."
        else "Imported ${result.items.size} irrigation event(s)."
    )
    is CsvImportResult.MissingColumns -> CsvImportOutcome(
        "Missing column(s)",
        "This file is missing required column(s): ${result.missing.joinToString(", ")}.\n\nExpected columns: ${result.expected.joinToString(", ")}"
    )
    is CsvImportResult.EmptyFile -> CsvImportOutcome(
        "Nothing to import",
        "That file is empty.\n\nExpected columns: ${result.expected.joinToString(", ")}"
    )
}

/**
 * Shared by both the local-file "Choose CSV file" picker and "Choose CSV from Dropbox" — parses
 * [text] as a plant-import CSV and saves each row into the active garden, matching an existing
 * plant by its "Plant ID" column when present (carrying its photo fields forward) or generating a
 * fresh id otherwise.
 */
suspend fun importPlantsCsv(context: Context, text: String, plants: List<PlantEntity>, viewModel: PlantViewModel): CsvImportOutcome {
    val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        return CsvImportOutcome("Nothing to import", "That file is empty.\n\nExpected columns: ${CSV_HEADERS.joinToString(", ")}")
    }
    val headers = lines[0].split(",").map { it.trim().trim('"') }
    if (headers.none { it.equals("Plant", ignoreCase = true) }) {
        return CsvImportOutcome(
            "Missing column",
            "This file is missing a \"Plant\" column (plant name), which is required.\n\nExpected columns: ${CSV_HEADERS.joinToString(", ")}"
        )
    }

    val workingPlants = plants.toMutableList()
    // Every id on the device (every garden, not just the active one) — a fresh id generated here
    // only needs to avoid colliding with the active garden's OWN plants for update-matching
    // purposes (see `existing` below), but avoiding a cross-garden id collision entirely is what
    // actually matters: two gardens' plants share one local table keyed on plain `id`, so reusing
    // another garden's id would silently overwrite that garden's plant (see PlantDao.upsert).
    val allDeviceIds = viewModel.getAllPlantsOnDevice().map { it.id }.toMutableSet()
    val importPrefix = plantIdPrefixForGarden(context, effectiveGardenId(context))
    var imported = 0
    var skipped = 0
    for (i in 1 until lines.size) {
        val cells = parseCsvLine(lines[i])
        val plantName = csvFindValue(headers, cells, "Plant")
        if (plantName.isNullOrBlank()) { skipped++; continue }
        val csvId = csvFindValue(headers, cells, "Plant ID")?.trim()
        val existing = workingPlants.firstOrNull { it.id == csvId }
        val resolvedId = if (!csvId.isNullOrBlank()) csvId else nextPlantId(allDeviceIds, importPrefix)
        allDeviceIds.add(resolvedId)
        val plant = PlantEntity(
            id = resolvedId,
            name = plantName,
            sci = csvFindValue(headers, cells, "Scientific name") ?: "",
            location = csvFindValue(headers, cells, "Location") ?: "",
            sun = csvFindValue(headers, cells, "Sun") ?: "",
            water = csvFindValue(headers, cells, "Water") ?: "",
            soil = csvFindValue(headers, cells, "Soil") ?: "",
            soilPh = csvFindValue(headers, cells, "Soil pH") ?: "",
            category = csvFindValue(headers, cells, "Category") ?: "",
            frost = csvFindValue(headers, cells, "Frost") ?: "",
            native = csvFindValue(headers, cells, "Native/Exotic") ?: "Native (Aus)",
            pollinator = csvFindValue(headers, cells, "Pollinator-Friendly") ?: "",
            source = csvFindValue(headers, cells, "Source") ?: "",
            date = csvFindValue(headers, cells, "Date planted") ?: "",
            qty = csvFindValue(headers, cells, "Amount")?.toIntOrNull() ?: 1,
            notes = csvFindValue(headers, cells, "Notes") ?: "",
            wateringSystem = csvFindValue(headers, cells, "Watering System") ?: "",
            lat = csvFindValue(headers, cells, "Latitude")?.toDoubleOrNull(),
            lng = csvFindValue(headers, cells, "Longitude")?.toDoubleOrNull(),
            photoUri = existing?.photoUri,
            photoUris = existing?.photoUris ?: emptyList(),
            photoThumbnailBase64 = existing?.photoThumbnailBase64
        )
        viewModel.save(plant)
        workingPlants.removeAll { it.id == resolvedId }
        workingPlants.add(plant)
        imported++
    }
    return CsvImportOutcome(
        "Import complete",
        if (skipped > 0) "Imported $imported plant(s). Skipped $skipped row(s) missing a plant name."
        else "Imported $imported plant(s)."
    )
}

suspend fun saveIrrigationCsvLocal(context: Context, newEvents: List<WateringEvent>): Boolean = withContext(Dispatchers.IO) {
    try {
        val folder = getIrrigationLogFolderUri(context)?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext false
        val existingFile = folder.findFile("irrigation_log.csv")
        val existingText = existingFile?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
        }
        val existingEvents = existingText?.let { text ->
            (parseIrrigationCsv(text) as? CsvImportResult.Success)?.items ?: emptyList()
        } ?: emptyList()
        val merged = (existingEvents + newEvents)
            .associateBy { "${it.zone}|${it.outlet}|${it.startTime}" }
            .values.sortedByDescending { it.startTime }
        val target = existingFile ?: folder.createFile("text/csv", "irrigation_log.csv") ?: return@withContext false
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(wateringEventsToCsv(merged).toByteArray()) }
        true
    } catch (_: Exception) { false }
}

suspend fun saveIrrigationCsvDropbox(context: Context, newEvents: List<WateringEvent>): Boolean = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext false
        val filePath = "${getIrrigationLogDropboxFolderPath(context) ?: ""}/irrigation_log.csv".replace("//", "/")
        val existingText = try {
            val out = java.io.ByteArrayOutputStream()
            client.files().download(filePath).download(out)
            out.toString("UTF-8")
        } catch (_: Exception) { null }
        val existingEvents = existingText?.let { text ->
            (parseIrrigationCsv(text) as? CsvImportResult.Success)?.items ?: emptyList()
        } ?: emptyList()
        val merged = (existingEvents + newEvents)
            .associateBy { "${it.zone}|${it.outlet}|${it.startTime}" }
            .values.sortedByDescending { it.startTime }
        // Same rolling-snapshot intent as the main Dropbox backup — overwrite in place rather than
        // erroring on every sync after the first, since this always re-uploads to the same path.
        client.files().uploadBuilder(filePath).withMode(WriteMode.OVERWRITE).uploadAndFinish(wateringEventsToCsv(merged).toByteArray().inputStream())
        true
    } catch (_: Exception) { false }
}

suspend fun fetchIrrigationCsvFromDropbox(context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext null
        val filePath = "${getIrrigationLogDropboxFolderPath(context) ?: ""}/irrigation_log.csv".replace("//", "/")
        val out = java.io.ByteArrayOutputStream()
        client.files().download(filePath).download(out)
        out.toString("UTF-8")
    } catch (_: Exception) { null }
}

// ============================================================================
// MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        HemisphereState.value = getHemisphere(applicationContext)
        ActiveGardenState.activeGardenId = GardenMembershipStore.getActiveGardenId(applicationContext)
        GardenAddressState.address = getGardenAddress(applicationContext)
        GardenAddressState.latLng = getGardenLatLng(applicationContext)
        GardenAddressState.locations = getGardenLocations(applicationContext)
        EntitlementLiveState.value = EntitlementManager.getCached(applicationContext)
        NotificationHelper.createChannels(applicationContext)
        if (getNotificationsEnabled(applicationContext)) scheduleWateringReminders(applicationContext)
        PendingNotificationState.type = intent.getStringExtra("notification_type")
        PendingPlantEditState.plantId = intent.getStringExtra("widget_plant_id")
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
    }
}

// ============================================================================
// SPLASH SCREEN
// ============================================================================

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        val duration = 4000L
        val steps = 100

        repeat(steps) { step ->
            delay(duration / steps)
            progress = (step + 1) / steps.toFloat()
        }

        onFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.garden_splashscreenimage),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Center title on a "frosted" translucent white panel
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.80f))
                .padding(horizontal = 30.dp, vertical = 20.dp)
        ) {
            Text(
                "Sage Garden",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF233821)
            )
        }

        // Bottom-right credit, also on a frosted panel for legibility
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.80f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Created by Daniel Luton", fontSize = 11.sp, color = Color(0xFF233821))
        }

        // Fake loading bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 40.dp, end = 40.dp, bottom = 70.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.45f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
            )
        }
    }
}
// ============================================================================
// APP SHELL / NAVIGATION
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantTooltipCard(plant: PlantEntity, onEdit: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.widthIn(max = 260.dp), elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plant.photoUri != null) {
                    PlantPhoto(
                        photoUri = plant.photoUri, photoThumbnailBase64 = plant.photoThumbnailBase64,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(plant.id, fontSize = 11.sp, color = Color.Gray)
                }
                TextButton(onClick = onDismiss) { Text("✕") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit plant") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GardenMapperApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: PlantViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as Application
        )
    )
    val wateringViewModel: WateringZoneViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as Application
        )
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Dynamic (not a fixed guess) so the FAB can be dragged nearly the full height of whatever
    // device it's running on, rather than an arbitrary dp range that undershoots on taller screens.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val sageFabOffsetMinDp = -(screenHeightDp - 160f)
    val sageFabOffsetMaxDp = 60f
    var showSageSheet by remember { mutableStateOf(false) }
    var sageFabOffsetY by remember {
        mutableStateOf(FeatureVisibility.getSageFabOffsetDp(context).coerceIn(sageFabOffsetMinDp, sageFabOffsetMaxDp))
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val topLevelRoutes = listOf("dashboard", "map", "list", "irrigation", "audit", "help")

    LaunchedEffect(Unit) {
        // SageEnabledState/AdvancedModeState/HemisphereState are already synced synchronously in
        // MainActivity.onCreate(), before this composable's first composition — see the comment there.
        EntitlementManager.sync(context)
    }

    // Auto-syncs plant/care-log data so neither side of a shared garden needs to remember to tap
    // "Sync plants & care history" manually. Keyed on the active garden id: switching gardens (or
    // joining/creating one) cancels the previous loop and starts a fresh one that syncs immediately,
    // then keeps syncing periodically for as long as that garden stays active and the app is open.
    LaunchedEffect(ActiveGardenState.activeGardenId) {
        while (true) {
            GardenSyncClient.sync(context, getOrCreateInstallId(context), effectiveGardenId(context))
            // Keeps the local known-gardens cache (GardenMembershipStore) fresh continuously, not just
            // when the user happens to open Help's "Sync with other devices" section — allKnownGardenIds
            // (used by the watering-reminder worker and the home-screen widget's garden filter) reads
            // straight from that cache, so letting it go stale silently under-covers gardens whose
            // membership changed elsewhere (approved on another device, left, etc.) until someone
            // manually reopens the sharing UI.
            GardenMembershipClient.refreshKnownGardens(context)
            delay(60_000L)
        }
    }
    // Also syncs right away whenever the app returns to the foreground, so bringing the app back
    // after the other party has made changes doesn't mean waiting out the rest of the periodic delay.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { GardenSyncClient.sync(context, getOrCreateInstallId(context), effectiveGardenId(context)) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(SageFabResetState.requested) {
        if (SageFabResetState.requested) {
            sageFabOffsetY = 0f
            SageFabResetState.requested = false
        }
    }

    LaunchedEffect(PendingNotificationState.type) {
        val type = PendingNotificationState.type
        if (type != null) {
            navController.navigate("notification/$type")
            PendingNotificationState.type = null
        }
    }

    LaunchedEffect(PendingPlantEditState.plantId) {
        val id = PendingPlantEditState.plantId
        if (id != null) {
            navController.navigate("form_edit/$id")
            PendingPlantEditState.plantId = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("Sage Garden") })
        },
        floatingActionButton = {
            if (FeatureVisibility.shouldShow(context, Feature.SAGE_ASSISTANT)) {
                FloatingActionButton(
                    onClick = { showSageSheet = true },
                    modifier = Modifier
                        .offset(y = sageFabOffsetY.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val dragDp = with(density) { dragAmount.toDp().value }
                                sageFabOffsetY = (sageFabOffsetY + dragDp)
                                    .coerceIn(sageFabOffsetMinDp, sageFabOffsetMaxDp)
                                FeatureVisibility.setSageFabOffsetDp(context, sageFabOffsetY)
                            }
                        }
                ) { Text("🌿") }
            }
        },
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == "dashboard",
                        onClick = { navController.navigate("dashboard") { popUpTo("map") } },
                        icon = { Text("📊") }, label = { AutoSizeText("Report") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "map",
                        onClick = { navController.navigate("map") { popUpTo("map") { inclusive = true } } },
                        icon = { Text("🗺️") }, label = { AutoSizeText("Map") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "list",
                        onClick = { navController.navigate("list") { popUpTo("map") } },
                        icon = { Text("📋") }, label = { AutoSizeText("List") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "irrigation",
                        onClick = { navController.navigate("irrigation") { popUpTo("map") } },
                        icon = { Text("💧") }, label = { AutoSizeText("Water") }
                    )
                    if (FeatureVisibility.shouldShow(context, Feature.AUDIT_SCREEN)) {
                        NavigationBarItem(
                            selected = currentRoute == "audit",
                            onClick = { navController.navigate("audit") { popUpTo("map") } },
                            icon = { Text("🔍") }, label = { AutoSizeText("Audit") }
                        )
                    }
                    NavigationBarItem(
                        selected = currentRoute == "help",
                        onClick = { navController.navigate("help") { popUpTo("map") } },
                        icon = { Text("❓") }, label = { AutoSizeText("Help") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = remember { getDefaultLandingTab(context) },
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") { DashboardScreen(viewModel = viewModel) }
            composable("map") {
                MapTabScreen(
                    viewModel = viewModel,
                    onMarkerClick = { id -> navController.navigate("form_edit/$id") },
                    onAddPlantAtLatLng = { lat, lng -> navController.navigate("form_new?lat=$lat&lng=$lng") },
                    onAddPlantAtFraction = { x, y -> navController.navigate("form_new?mapX=$x&mapY=$y") },
                    startOnCustom = isUsingCustomMap(context),
                    onOpenSunMap = { navController.navigate("sunmap") },
                    onNavigateToHelp = { navController.navigate("help") }
                )
            }
            composable("list") {
                ListScreen(
                    viewModel = viewModel,
                    onPlantClick = { id -> navController.navigate("form_edit/$id") },
                    onAddPlant = { navController.navigate("form_new") },
                    onChangeLocation = { id, useCustom ->
                        navController.navigate(if (useCustom) "place_custom/$id" else "place_real/$id")
                    },
                    onOpenLocationPhotos = { location -> navController.navigate("location_photos/${Uri.encode(location)}") }
                )
            }
            composable("irrigation") {
                val events by wateringViewModel.events.collectAsState()
                val plants by viewModel.plants.collectAsState()
                IrrigationScreen(wateringEvents = events, plants = plants, onPlantClick = { id -> navController.navigate("form_edit/$id") })
            }
            composable("audit") { AuditScreen() }
            composable(
                "form_new?lat={lat}&lng={lng}&mapX={mapX}&mapY={mapY}",
                arguments = listOf(
                    navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("lng") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("mapX") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("mapY") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            )
            { backStackEntry ->
                val lat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull()
                val lng = backStackEntry.arguments?.getString("lng")?.toDoubleOrNull()
                val mapX = backStackEntry.arguments?.getString("mapX")?.toDoubleOrNull()
                val mapY = backStackEntry.arguments?.getString("mapY")?.toDoubleOrNull()
                FormScreen(
                    viewModel = viewModel, plantId = null,
                    initialLat = lat, initialLng = lng, initialMapX = mapX, initialMapY = mapY,
                    snackbarHostState = snackbarHostState, scope = scope,
                    onDone = { navController.navigate("list") { popUpTo("map") } },
                    onCancel = { navController.popBackStack() },
                    onNavigateToPlacement = { route -> navController.navigate(route) }
                )
            }
            composable(
                "form_edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")
                FormScreen(
                    viewModel = viewModel, plantId = id, initialLat = null, initialLng = null,
                    snackbarHostState = snackbarHostState, scope = scope,
                    onDone = { navController.navigate("list") { popUpTo("map") } },
                    onCancel = { navController.popBackStack() },
                    onNavigateToPlacement = { route -> navController.navigate(route) },
                    onOpenGrowthTimeline = { navController.navigate("growth/$it") },
                    onOpenCareHistory = { navController.navigate("care/$it") }
                )
            }
            composable("place_real/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                MapTabScreen(
                    viewModel = viewModel, onMarkerClick = { },
                    placementModeForPlantId = id, onPlacementSaved = { navController.navigate("list") { popUpTo("map") } },
                    startOnCustom = false
                )
            }
            composable("place_custom/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                MapTabScreen(
                    viewModel = viewModel, onMarkerClick = { },
                    placementModeForPlantId = id, onPlacementSaved = { navController.navigate("list") { popUpTo("map") } },
                    startOnCustom = true
                )
            }
            composable("help") {
                val pathViewModel: IrrigationPathViewModel = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
                        context.applicationContext as Application
                    )
                )
                HelpScreen(
                    viewModel = viewModel, wateringViewModel = wateringViewModel, pathViewModel = pathViewModel,
                    snackbarHostState = snackbarHostState, scope = scope,
                    onOpenFaq = { navController.navigate("faq") }
                )
            }
            composable("faq") {
                FaqScreen(onBack = { navController.popBackStack() })
            }
            composable("sunmap") {
                SunMapScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "notification/{type}",
                arguments = listOf(navArgument("type") { type = NavType.StringType })
            ) { backStackEntry ->
                val notifType = backStackEntry.arguments?.getString("type") ?: "watering"
                NotificationDetailsScreen(type = notifType, onBack = { navController.popBackStack() })
            }
            composable("growth/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                GrowthTimelineScreen(plantId = id, onBack = { navController.popBackStack() })
            }
            composable("care/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                CareHistoryScreen(plantId = id, onBack = { navController.popBackStack() })
            }
            composable("location_photos/{location}", arguments = listOf(navArgument("location") { type = NavType.StringType })) { backStackEntry ->
                val location = backStackEntry.arguments?.getString("location") ?: return@composable
                LocationTimelineScreen(location = Uri.decode(location), onBack = { navController.popBackStack() })
            }
        }
    }

    if (showSageSheet) {
        SageChatSheet(
            onDismiss = { showSageSheet = false },
            onOpenHelp = {
                showSageSheet = false
                navController.navigate("help")
            }
        )
    }

}

// ============================================================================
// DASHBOARD SCREEN (with filter panel + tappable plant detail)
// ============================================================================

data class DashboardFilters(
    val location: String = "All",
    val source: String = "All",
    val plant: String = "All",
    val sun: String = "All",
    val soil: String = "All",
    val soilPh: String = "All",
    val category: String = "All",
    val water: String = "All",
    val frost: String = "All"
)
data class DashboardStatOption(val key: String, val label: String)

val dashboardStatCatalog = listOf(
    DashboardStatOption("total", "Total Plants"),
    DashboardStatOption("native", "Native"),
    DashboardStatOption("exotic", "Exotic"),
    DashboardStatOption("pollinator", "Pollinator-friendly"),
    DashboardStatOption("locations", "Unique Locations"),
    DashboardStatOption("species", "Unique Species"),
    DashboardStatOption("no_photo", "Plants without Photos"),
    DashboardStatOption("needs_water", "Needs Watering Now"),
    DashboardStatOption("manual_water", "Manual Watering Only"),
    DashboardStatOption("frost_hardy", "Frost Hardy"),
    DashboardStatOption("indoor", "Indoor Plants")
)

val defaultDashboardStatKeys = listOf("total", "native", "exotic", "pollinator")

fun getDashboardStatKeys(context: Context): List<String> {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString("dashboard_stat_keys", null) ?: return defaultDashboardStatKeys
    val keys = raw.split(",").filter { it.isNotBlank() }
    return keys.ifEmpty { defaultDashboardStatKeys }
}

fun setDashboardStatKeys(context: Context, keys: List<String>) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dashboard_stat_keys", keys.joinToString(",")).apply()
}

fun computeDashboardStatValue(key: String, plants: List<PlantEntity>): String {
    val now = System.currentTimeMillis()
    return when (key) {
        "total" -> plants.sumOf { it.qty }.toString()
        "native" -> plants.count { it.native.startsWith("Native") }.toString()
        "exotic" -> plants.count { it.native.startsWith("Exotic") }.toString()
        "pollinator" -> plants.count { it.pollinator.startsWith("Yes") }.toString()
        "locations" -> plants.map { it.location }.filter { it.isNotBlank() }.distinct().size.toString()
        "species" -> plants.map { it.sci }.filter { it.isNotBlank() }.distinct().size.toString()
        "no_photo" -> plants.count { it.photoUri == null }.toString()
        "needs_water" -> plants.count { p ->
            computeWateringStatus(p, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
        }.toString()
        "manual_water" -> plants.count { it.manualWateringOnly }.toString()
        "frost_hardy" -> plants.count { it.frost == "Hardy" }.toString()
        "indoor" -> plants.count { it.isIndoor }.toString()
        else -> "0"
    }
}

fun plantMatchesStatKey(key: String, plant: PlantEntity, now: Long): Boolean = when (key) {
    "total" -> true
    "native" -> plant.native.startsWith("Native")
    "exotic" -> plant.native.startsWith("Exotic")
    "pollinator" -> plant.pollinator.startsWith("Yes")
    "locations" -> plant.location.isNotBlank()
    "species" -> plant.sci.isNotBlank()
    "no_photo" -> plant.photoUri == null
    "needs_water" -> computeWateringStatus(plant, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
    "manual_water" -> plant.manualWateringOnly
    "frost_hardy" -> plant.frost == "Hardy"
    "indoor" -> plant.isIndoor
    else -> true
}

@Composable
fun StatCard(
    label: String, value: String, modifier: Modifier = Modifier,
    selected: Boolean = false, onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF3A5A40) else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) BorderStroke(2.dp, Color(0xFF233821)) else null
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color(0xFF3A5A40))
            Text(label, fontSize = 12.sp, color = if (selected) Color.White.copy(alpha = 0.85f) else Color.Gray)
        }
    }
}

// ============================================================================
// DASHBOARD CHART SETTINGS
// ============================================================================

val dashboardChartGroupOptions = listOf(
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun Needs"),
    DashboardStatOption("water", "Water Needs"),
    DashboardStatOption("native", "Native/Exotic Status")
)

fun getDashboardChartEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("dashboard_chart_enabled", true)
}
fun setDashboardChartEnabled(context: Context, value: Boolean) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("dashboard_chart_enabled", value).apply()
}
fun getDashboardChartGroupBy(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dashboard_chart_group_by", "location") ?: "location"
}
fun setDashboardChartGroupBy(context: Context, value: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dashboard_chart_group_by", value).apply()
}

// ============================================================================
// LIST VIEW CUSTOMISATION
// ============================================================================

val listFieldCatalog = listOf(
    DashboardStatOption("sci", "Scientific name"),
    DashboardStatOption("native", "Native/Exotic"),
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun"),
    DashboardStatOption("water", "Water"),
    DashboardStatOption("soil", "Soil"),
    DashboardStatOption("soilPh", "Soil pH"),
    DashboardStatOption("frost", "Frost"),
    DashboardStatOption("pollinator", "Pollinator-friendly"),
    DashboardStatOption("source", "Source"),
    DashboardStatOption("wateringSystem", "Watering system"),
    DashboardStatOption("due", "Watering due")
)
val defaultListFieldKeys = listOf("sci", "native")

val listGroupOptions = listOf(
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun"),
    DashboardStatOption("water", "Water"),
    DashboardStatOption("none", "None")
)
val listSortOptions = listOf(
    DashboardStatOption("name", "Name"),
    DashboardStatOption("due", "Watering due")
)

fun getListFieldKeys(context: Context): List<String> {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString("list_field_keys", null) ?: return defaultListFieldKeys
    val keys = raw.split(",").filter { it.isNotBlank() }
    return keys.ifEmpty { defaultListFieldKeys }
}
fun setListFieldKeys(context: Context, keys: List<String>) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("list_field_keys", keys.joinToString(",")).apply()
}
fun getListGroupBy(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("list_group_by", "location") ?: "location"
}
fun setListGroupBy(context: Context, value: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("list_group_by", value).apply()
}
fun getListSortBy(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("list_sort_by", "name") ?: "name"
}
fun setListSortBy(context: Context, value: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("list_sort_by", value).apply()
}

fun listFieldValue(key: String, plant: PlantEntity): String? = when (key) {
    "sci" -> plant.sci.takeIf { it.isNotBlank() }
    "native" -> plant.native.takeIf { it.isNotBlank() }
    "location" -> plant.location.takeIf { it.isNotBlank() }
    "category" -> plant.category.takeIf { it.isNotBlank() }
    "sun" -> plant.sun.takeIf { it.isNotBlank() }?.let { "$it sun" }
    "water" -> plant.water.takeIf { it.isNotBlank() }?.let { "$it water" }
    "soil" -> plant.soil.takeIf { it.isNotBlank() }
    "soilPh" -> plant.soilPh.takeIf { it.isNotBlank() }
    "frost" -> plant.frost.takeIf { it.isNotBlank() }
    "pollinator" -> plant.pollinator.takeIf { it.isNotBlank() }
    "source" -> plant.source.takeIf { it.isNotBlank() }
    "wateringSystem" -> plant.wateringSystem.takeIf { it.isNotBlank() }
    "due" -> computeWateringStatus(plant)?.label
    else -> null
}

// ============================================================================
// APP SETTINGS (default landing tab)
// ============================================================================

val landingTabOptions = listOf(
    DashboardStatOption("dashboard", "Report"),
    DashboardStatOption("map", "Map"),
    DashboardStatOption("list", "List"),
    DashboardStatOption("irrigation", "Water"),
    DashboardStatOption("audit", "Audit")
)

fun getDefaultLandingTab(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("default_landing_tab", "map") ?: "map"
}
fun setDefaultLandingTab(context: Context, tab: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("default_landing_tab", tab).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: PlantViewModel) {
    val allPlants by viewModel.plants.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val context = LocalContext.current
    var showFilterDialog by remember { mutableStateOf(false) }
    var showCustomiseDialog by remember { mutableStateOf(false) }
    var selectedStatKey by remember { mutableStateOf<String?>("total") }
    var statKeys by remember { mutableStateOf(getDashboardStatKeys(context)) }
    var chartEnabled by remember { mutableStateOf(getDashboardChartEnabled(context)) }
    var chartGroupBy by remember { mutableStateOf(getDashboardChartGroupBy(context)) }
    var selectedPlant by remember { mutableStateOf<PlantEntity?>(null) }

    val locations = remember(allPlants) { listOf("All") + allPlants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted() }
    val sources = remember(allPlants) { listOf("All") + allPlants.map { it.source }.filter { it.isNotBlank() }.distinct().sorted() }
    val plantNames = remember(allPlants) { listOf("All") + allPlants.map { it.name }.filter { it.isNotBlank() }.distinct().sorted() }

    val filteredPlants by viewModel.filteredPlants.collectAsState()
    val percentage = if (allPlants.isNotEmpty()) {
        filteredPlants.size.toDouble() / allPlants.size * 100
    } else {
        0.0
    }
    val activeFilterCount = listOf(
        filters.location, filters.source, filters.plant, filters.sun, filters.soil, filters.soilPh, filters.category, filters.water, filters.frost
    ).count { it != "All" }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val df = DecimalFormat("0.##")
            Text(
                "${filteredPlants.size}/${allPlants.size} unique plants shown (${df.format(percentage)}%)",
                fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f)
            )
                    OutlinedButton(onClick = { showCustomiseDialog = true }, modifier = Modifier.padding(end = 8.dp)) {
            Text("Customise")
        }
            OutlinedButton(onClick = { showFilterDialog = true }) {
                Text(if (activeFilterCount > 0) "Filter ($activeFilterCount)" else "Filter")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (filteredPlants.isEmpty()) {
                Text("No plants match these filters.", color = Color.Gray)
                return@Column
            }

            statKeys.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { key ->
                        val option = dashboardStatCatalog.firstOrNull { it.key == key }
                        StatCard(
                            option?.label ?: key,
                            computeDashboardStatValue(key, filteredPlants),
                            Modifier.weight(1f),
                            selected = selectedStatKey == key,
                            onClick = { selectedStatKey = if (selectedStatKey == key) null else key }
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))

// Moved above the chart so both the chart and the list react to the selected stat card
            val now = remember { System.currentTimeMillis() }
            val statFilteredPlants = remember(filteredPlants, selectedStatKey, now) {
                val key = selectedStatKey
                if (key == null) filteredPlants else filteredPlants.filter { plantMatchesStatKey(key, it, now) }
            }

            if (chartEnabled) {
                val chartLabel = dashboardChartGroupOptions.firstOrNull { it.key == chartGroupBy }?.label ?: "Location"
                val statLabel = dashboardStatCatalog.firstOrNull { it.key == selectedStatKey }?.label
                Text(
                    if (statLabel != null) "Plants by $chartLabel — $statLabel" else "Plants by $chartLabel",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                DashboardBarChart(plants = statFilteredPlants, groupBy = chartGroupBy)   // was: filteredPlants
                Spacer(Modifier.height(24.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val statLabel = dashboardStatCatalog.firstOrNull { it.key == selectedStatKey }?.label
                Text(
                    if (statLabel != null) "Plants — $statLabel" else "Plants",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f)
                )
                if (selectedStatKey != null) TextButton(onClick = { selectedStatKey = null }) { Text("Clear", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))

            statFilteredPlants.forEach { plant ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedPlant = plant }
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (plant.photoUri != null) {
                            PlantPhoto(
                                photoUri = plant.photoUri, photoThumbnailBase64 = plant.photoThumbnailBase64,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE3DDCF)),
                                contentAlignment = Alignment.Center
                            ) { Text("🌿") }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                text = listOfNotNull(
                                    plant.location.takeIf { it.isNotBlank() } ?: "No location",
                                    plant.sun.takeIf { it.isNotBlank() }?.let { "$it sun" },
                                    plant.water.takeIf { it.isNotBlank() }?.let { "$it water" }
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = Color.Gray
                            )
                        }
                        Text("›", fontSize = 18.sp, color = Color.Gray)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showFilterDialog) {
        var draft by remember { mutableStateOf(filters) }
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Filter plants") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SimpleFilterDropdown("Location", locations, draft.location) { draft = draft.copy(location = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Source", sources, draft.source) { draft = draft.copy(source = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Plant", plantNames, draft.plant) { draft = draft.copy(plant = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Sun", listOf("All") + sunOptions, draft.sun) { draft = draft.copy(sun = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Soil", listOf("All") + soilOptions, draft.soil) { draft = draft.copy(soil = it) }
                    Spacer(Modifier.height(10.dp))
                    if (FeatureVisibility.shouldShow(context, Feature.SOIL_PH)) {
                        SimpleFilterDropdown("Soil pH", listOf("All") + soilPhOptions, draft.soilPh) { draft = draft.copy(soilPh = it) }
                        Spacer(Modifier.height(10.dp))
                    }
                    SimpleFilterDropdown("Category", listOf("All") + categoryOptions, draft.category) { draft = draft.copy(category = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Water", listOf("All") + waterOptions, draft.water) { draft = draft.copy(water = it) }
                    Spacer(Modifier.height(10.dp))
                    SimpleFilterDropdown("Frost", listOf("All") + frostOptions, draft.frost) { draft = draft.copy(frost = it) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setFilters(draft); showFilterDialog = false }) { Text("Apply") } },
            dismissButton = {
                TextButton(onClick = {
                    val cleared = DashboardFilters()
                    draft = cleared; viewModel.setFilters(cleared); showFilterDialog = false
                }) { Text("Clear filters") }
            }
        )
    }
    if (showCustomiseDialog) {
        val draftKeys = remember { mutableStateListOf(*statKeys.toTypedArray()) }
        var draftChartEnabled by remember { mutableStateOf(chartEnabled) }
        var draftChartGroupBy by remember { mutableStateOf(chartGroupBy) }
        AlertDialog(
            onDismissRequest = { showCustomiseDialog = false },
            title = { Text("Customise dashboard") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Figures shown (in order):", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    draftKeys.forEachIndexed { index, key ->
                        val option = dashboardStatCatalog.firstOrNull { it.key == key }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(option?.label ?: key, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { if (index > 0) { draftKeys.removeAt(index); draftKeys.add(index - 1, key) } },
                                enabled = index > 0
                            ) { Text("↑", fontSize = 14.sp) }
                            IconButton(
                                onClick = { if (index < draftKeys.size - 1) { draftKeys.removeAt(index); draftKeys.add(index + 1, key) } },
                                enabled = index < draftKeys.size - 1
                            ) { Text("↓", fontSize = 14.sp) }
                            IconButton(onClick = { draftKeys.remove(key) }) { Text("✕", fontSize = 14.sp) }
                        }
                    }

                    val addableOptions = dashboardStatCatalog.filter { !draftKeys.contains(it.key) }
                    if (addableOptions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Add more:", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        addableOptions.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { draftKeys.add(option.key) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text("+ ", fontWeight = FontWeight.Bold)
                                Text(option.label, fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Show chart", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = draftChartEnabled, onCheckedChange = { draftChartEnabled = it })
                    }
                    if (draftChartEnabled) {
                        Spacer(Modifier.height(8.dp))
                        DropdownField(
                            label = "Chart grouped by",
                            options = dashboardChartGroupOptions.map { it.label },
                            selected = dashboardChartGroupOptions.firstOrNull { it.key == draftChartGroupBy }?.label ?: "Location",
                            onSelect = { label ->
                                draftChartGroupBy = dashboardChartGroupOptions.firstOrNull { it.label == label }?.key ?: "location"
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalKeys = if (draftKeys.isEmpty()) defaultDashboardStatKeys else draftKeys.toList()
                    statKeys = finalKeys
                    setDashboardStatKeys(context, finalKeys)
                    chartEnabled = draftChartEnabled
                    setDashboardChartEnabled(context, draftChartEnabled)
                    chartGroupBy = draftChartGroupBy
                    setDashboardChartGroupBy(context, draftChartGroupBy)
                    showCustomiseDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCustomiseDialog = false }) { Text("Cancel") } }
        )
    }
    val plantToShow = selectedPlant
    if (plantToShow != null) {
        Dialog(onDismissRequest = { selectedPlant = null }) {
            Card {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (plantToShow.photoUri != null) {
                        PlantPhoto(
                            photoUri = plantToShow.photoUri, photoThumbnailBase64 = plantToShow.photoThumbnailBase64,
                            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(plantToShow.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (plantToShow.sci.isNotBlank()) {
                        Text(plantToShow.sci, fontSize = 13.sp, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    Spacer(Modifier.height(16.dp))
                    DetailRow("Sun", plantToShow.sun)
                    DetailRow("Water", plantToShow.water)
                    DetailRow("Soil", plantToShow.soil)
                    if (FeatureVisibility.shouldShow(context, Feature.SOIL_PH)) DetailRow("Soil pH", plantToShow.soilPh)
                    DetailRow("Category", plantToShow.category)
                    DetailRow("Frost", plantToShow.frost)
                    DetailRow("Native/Exotic", plantToShow.native)
                    DetailRow("Pollinator-friendly", plantToShow.pollinator)
                    DetailRow("Location", plantToShow.location)
                    DetailRow("Source", plantToShow.source)
                    DetailRow("Watering System", plantToShow.wateringSystem)
                    DetailRow("Notes", plantToShow.notes)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { selectedPlant = null }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleFilterDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3A5A40))
            Text(label, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
fun DashboardBarChart(plants: List<PlantEntity>, groupBy: String) {
    val keyFn: (PlantEntity) -> String = when (groupBy) {
        "sun" -> { p -> p.sun.ifBlank { "Unspecified" } }
        "water" -> { p -> p.water.ifBlank { "Unspecified" } }
        "native" -> { p -> p.native.ifBlank { "Unspecified" } }
        "category" -> { p -> p.category.ifBlank { "Unspecified" } }
        else -> { p -> p.location.ifBlank { "Unspecified" } }
    }
    val counts = remember(plants, groupBy) {
        plants.groupingBy(keyFn).eachCount().entries.sortedByDescending { it.value }.take(6)
    }
    if (counts.isEmpty()) {
        Text("No data to chart yet.", fontSize = 12.sp, color = Color.Gray)
        return
    }
    val maxCount = counts.maxOf { it.value }.coerceAtLeast(1)
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height },
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            counts.forEach { entry ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    // Reserve fixed px for the number+spacer above the bar, then size the bar
                    // from whatever's left — so the number never gets squeezed out.
                    val numberReservePx = with(density) { 18.dp.toPx() }
                    val availablePx = (trackHeightPx - numberReservePx).coerceAtLeast(0f)
                    val fraction = (entry.value.toFloat() / maxCount).coerceIn(0.03f, 1f)
                    val barHeightDp = with(density) { (availablePx * fraction).toDp() }.coerceAtLeast(3.dp)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.value.toString(), fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(barHeightDp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(Color(0xFF3A5A40))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { entry ->
                Text(
                    entry.key, fontSize = 10.sp, color = Color.Gray, lineHeight = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================================
// MAP SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: PlantViewModel,
    onMapTap: (Double, Double) -> Unit,
    onMarkerClick: (String) -> Unit,
    placementModeForPlantId: String? = null,
    onPlacementSaved: (() -> Unit)? = null,
    onNavigateToHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    val plants by viewModel.filteredPlants.collectAsState()
    // Categories are opt-in (most gardens never set one), so this quick filter — separate from the
    // Dashboard's full filter dialog, which isn't reachable from the map — only shows once at least
    // one plant actually has a category, and only offers the categories actually in use.
    val allPlantsUnfiltered by viewModel.plants.collectAsState()
    val usedCategories = remember(allPlantsUnfiltered) {
        categoryOptions.filter { cat -> allPlantsUnfiltered.any { it.category == cat } }
    }
    var mapCategoryFilter by remember { mutableStateOf("All") }
    val categoryFilteredPlants = remember(plants, mapCategoryFilter) {
        if (mapCategoryFilter == "All") plants else plants.filter { it.category == mapCategoryFilter }
    }
    val gardenLatLng = remember { getGardenLatLng(context) }
    val savedCamera = remember { getMapCameraPosition(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = when {
            savedCamera != null -> CameraPosition.fromLatLngZoom(LatLng(savedCamera.first, savedCamera.second), savedCamera.third)
            gardenLatLng != null -> CameraPosition.fromLatLngZoom(LatLng(gardenLatLng.first, gardenLatLng.second), 18f)
            else -> CameraPosition.fromLatLngZoom(LatLng(MAP_FALLBACK_LAT, MAP_FALLBACK_LNG), 18f)
        }
    }
    val scope = rememberCoroutineScope()
    // Persists the camera's resting position whenever the user finishes panning/zooming, so the
    // map opens back to where they left it instead of resetting to the garden address every time.
    // isMoving starts false at composition, so this LaunchedEffect's very first firing is an
    // artifact of initial setup, not a real user pan/zoom — persisting it would lock in whatever
    // fallback position (garden address, or the hardcoded default) the camera happened to start at,
    // permanently shadowing the real garden address once it becomes known and blocking the
    // auto-fit-to-markers effect below (which only runs when no camera has been saved yet) from ever
    // running again. Skipping exactly this first firing lets a real garden address or the auto-fit's
    // own camera move persist normally afterward, while still remembering genuine user pans.
    var hasSettledOnce by remember(ActiveGardenState.activeGardenId) { mutableStateOf(false) }
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            if (hasSettledOnce) {
                val pos = cameraPositionState.position
                setMapCameraPosition(context, pos.target.latitude, pos.target.longitude, pos.zoom)
            } else {
                hasSettledOnce = true
            }
        }
    }
    // A garden viewed for the first time on this device (freshly created, or just joined) has no
    // saved camera position or garden address of its own yet — without this, the map fell back to
    // a hardcoded NYC default, making a shared garden's plants look like they "didn't come across"
    // when they were actually just off-screen on the other side of the world. Once this garden's
    // actual plants load, fit the camera to them instead, but only once and only when there was
    // nothing more specific to go on.
    var hasAutoFitted by remember(ActiveGardenState.activeGardenId) { mutableStateOf(false) }
    LaunchedEffect(plants, hasAutoFitted) {
        if (!hasAutoFitted && savedCamera == null && gardenLatLng == null && plants.isNotEmpty()) {
            val coords = plants.mapNotNull { p -> if (p.lat != null && p.lng != null) LatLng(p.lat, p.lng) else null }
            if (coords.isNotEmpty()) {
                hasAutoFitted = true
                if (coords.size == 1) {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(coords.first(), 18f))
                } else {
                    val bounds = LatLngBounds.Builder().apply { coords.forEach { include(it) } }.build()
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                }
            }
        }
    }
    var searchQuery by remember { mutableStateOf("") }

    val placesClient = remember { Places.createClient(context) }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var geocoderPredictions by remember { mutableStateOf<List<android.location.Address>>(emptyList()) }
    var tooltipPlant by remember { mutableStateOf<PlantEntity?>(null) }
    // A session token bundles every autocomplete keystroke plus the final place-details fetch into
    // one billed "session" instead of separate per-request charges — a new token starts each time a
    // fresh search begins (see onPredictionClick, which rotates it once a place is picked).
    var sessionToken by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length > 2) {
            delay(300) // Debounce typing

            // 1. Try Places SDK (requires Places API enabled in Google Cloud Console)
            val request = FindAutocompletePredictionsRequest.builder()
                .setQuery(searchQuery)
                .setSessionToken(sessionToken)
                .build()
            placesClient.findAutocompletePredictions(request)
                .addOnSuccessListener { response: FindAutocompletePredictionsResponse ->
                    predictions = response.autocompletePredictions
                    geocoderPredictions = emptyList()
                }
                .addOnFailureListener {
                    predictions = emptyList()
                    // 2. Fallback to Geocoder (free, no API enablement required)
                    scope.launch {
                        val results = withContext(Dispatchers.IO) {
                            try {
                                @Suppress("DEPRECATION")
                                Geocoder(context, Locale.getDefault()).getFromLocationName(searchQuery, 5)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        geocoderPredictions = results ?: emptyList()
                    }
                }
        } else {
            predictions = emptyList()
            geocoderPredictions = emptyList()
        }
    }

    fun onAddressClick(address: android.location.Address) {
        searchQuery = address.getAddressLine(0) ?: ""
        geocoderPredictions = emptyList()
        cameraPositionState.position = CameraPosition.fromLatLngZoom(
            LatLng(address.latitude, address.longitude), 18f
        )
    }

    fun onPredictionClick(prediction: AutocompletePrediction) {
        val fullText = prediction.getFullText(null).toString()
        searchQuery = fullText
        predictions = emptyList()
        // LAT_LNG was replaced by LOCATION in Places SDK 5.0+
        val placeFields = listOf(Place.Field.LOCATION)
        val request = FetchPlaceRequest.builder(prediction.placeId, placeFields).setSessionToken(sessionToken).build()
        placesClient.fetchPlace(request)
            .addOnSuccessListener { response: FetchPlaceResponse ->
                val latLng = response.place.location
                if (latLng != null) {
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(latLng, 18f)
                }
            }
        sessionToken = AutocompleteSessionToken.newInstance() // this session is spent — start a fresh one for the next search
    }

    fun runAddressSearch() {
        if (searchQuery.isBlank()) return
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocationName(searchQuery, 1)
                } catch (_: Exception) {
                    null
                }
            }
            if (!results.isNullOrEmpty()) {
                val loc = results[0]
                cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(loc.latitude, loc.longitude), 18f)
            }
        }
    }

    // Only the true "nothing to show a map from" case should get the placeholder instead of the
    // real map — a garden with no address but at least one plant that already has coordinates
    // (e.g. a view-only member before the owner's address has synced down) still has something to
    // auto-fit the camera to, via the LaunchedEffect above. This used to just check gardenLatLng,
    // which crashed: that LaunchedEffect runs unconditionally (it's declared earlier in this
    // function, so a later `return` doesn't stop it from having already fired) and calls
    // CameraUpdateFactory, which is only initialized once a real GoogleMap has been created — but
    // the old condition skipped rendering GoogleMap entirely whenever gardenLatLng was null,
    // regardless of whether there were plant coordinates to fit to, so CameraUpdateFactory was
    // never initialized and the call threw "NullPointerException: CameraUpdateFactory is not
    // initialized". Broadening this condition to also render the map (letting the SDK initialize)
    // whenever plant coordinates exist fixes the crash and restores the auto-fit's actual purpose.
    val hasPlantCoordinates = plants.any { it.lat != null && it.lng != null }
    if (gardenLatLng == null && placementModeForPlantId == null && !hasPlantCoordinates) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺️", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Set your garden's address to see it here",
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Once set, the map centres on your garden instead of a generic location.",
                    fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { PendingHelpFocusState.focusWeatherSection = true; onNavigateToHelp() }) {
                    Text("Set garden address in Help")
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.HYBRID),
            onMapClick = { latLng ->
                // A view-only member can still pan/zoom freely (that's just camera state, not a
                // GoogleMap click callback) — only tapping to add/move a plant is blocked here.
                // Placement mode itself is already unreachable without edit access (its only entry
                // point, FormScreen's "Place on real-world map" button, is hidden for read-only), so
                // this check mainly guards the plain "tap empty space to add a new plant" path.
                if (hasWriteAccessToActiveGarden(context)) {
                    if (placementModeForPlantId != null) {
                        scope.launch {
                            val plant = viewModel.getById(placementModeForPlantId)
                            if (plant != null) {
                                viewModel.save(plant.copy(lat = latLng.latitude, lng = latLng.longitude))
                            }
                            onPlacementSaved?.invoke()
                        }
                    } else {
                        onMapTap(latLng.latitude, latLng.longitude)
                    }
                }
            }
        ) {
            categoryFilteredPlants.forEach { plant ->
                // Keyed by plant id so each marker's remembered state (MarkerComposable especially
                // caches its rendered content as a bitmap internally) stays tied to that specific
                // plant across recompositions — without this, Compose's default positional slot
                // reuse in a plain forEach could let a marker at a given list position keep an old
                // plant's cached icon bitmap for a moment after filtering/reordering changed which
                // plant now occupies that position, showing the wrong category icon.
                key(plant.id) {
                if (plant.lat != null && plant.lng != null) {
                    if (plant.category.isNotBlank() && plant.category != "Other") {
                        MarkerComposable(
                            state = MarkerState(position = LatLng(plant.lat, plant.lng)),
                            title = plant.name,
                            snippet = plant.sci,
                            onClick = { tooltipPlant = plant; true }
                        ) {
                            Box(
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50))
                                    .background(Color.White).border(1.5.dp, Color(0xFF3A5A40), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(categoryMarkerEmoji(plant.category), fontSize = 15.sp)
                            }
                        }
                    } else {
                        Marker(
                            state = MarkerState(position = LatLng(plant.lat, plant.lng)),
                            title = plant.name,
                            snippet = plant.sci,
                            icon = BitmapDescriptorFactory.defaultMarker(
                                if (plant.native.startsWith("Native")) BitmapDescriptorFactory.HUE_GREEN
                                else BitmapDescriptorFactory.HUE_ORANGE
                            ),
                            onClick = {
                                tooltipPlant = plant
                                true
                            }
                        )
                    }
                }
                }
            }
        }

        if (placementModeForPlantId != null) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Color(0xFF3A5A40), RoundedCornerShape(8.dp)).padding(12.dp)
            ) {
                Text("Tap anywhere to place this plant", color = Color.White, fontSize = 13.sp)
            }
        }

        // Address search bar and predictions list
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search for an address…") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { runAddressSearch() }) { Text("🔍") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(10.dp))
            )

            if (usedCategories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val categoryLabels = remember(usedCategories) {
                    listOf("All" to "All") + usedCategories.map { cat -> cat to "${categoryMarkerEmoji(cat)} $cat" }
                }
                val selectedCategoryLabel = categoryLabels.firstOrNull { it.first == mapCategoryFilter }?.second ?: "All"
                var categoryMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { categoryMenuExpanded = true },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.92f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(selectedCategoryLabel, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (categoryMenuExpanded) "▾" else "▸", fontSize = 12.sp, color = Color.Gray)
                    }
                    DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                        categoryLabels.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = { mapCategoryFilter = key; categoryMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            if (predictions.isNotEmpty() || geocoderPredictions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                    ) {
                        items(predictions) { prediction ->
                            Text(
                                text = prediction.getFullText(null).toString(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPredictionClick(prediction) }
                                    .padding(12.dp),
                                fontSize = 14.sp
                            )
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                        }
                        items(geocoderPredictions) { address ->
                            Text(
                                text = address.getAddressLine(0) ?: "Unknown address",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddressClick(address) }
                                    .padding(12.dp),
                                fontSize = 14.sp
                            )
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // "+" add-plant button, bottom-center (was bottom-end, overlapped zoom controls)
        FloatingActionButton(
            onClick = {
                val c = cameraPositionState.position.target
                onMapTap(c.latitude, c.longitude)
            },
            containerColor = Color(0xFFFF7A45),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
        ) {
            Text("+", fontSize = 28.sp)
        }

        // Tooltip sits above the map layer and consumes taps so they don't fall
        // through to the GoogleMap AndroidView underneath (which would otherwise
        // register them as "add a plant here").
        tooltipPlant?.let { plant ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { /* consume — don't let it reach the map */ } }
            ) {
                PlantTooltipCard(plant = plant, onEdit = { onMarkerClick(plant.id) }, onDismiss = { tooltipPlant = null })
            }
        }
    }
}

@Composable
fun MapTabScreen(
    viewModel: PlantViewModel,
    onMarkerClick: (String) -> Unit,
    onAddPlantAtLatLng: (Double, Double) -> Unit = { _, _ -> },
    onAddPlantAtFraction: (Double, Double) -> Unit = { _, _ -> },
    placementModeForPlantId: String? = null,
    onPlacementSaved: (() -> Unit)? = null,
    startOnCustom: Boolean = false,
    onOpenSunMap: () -> Unit = {},
    onNavigateToHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    val pathViewModel: IrrigationPathViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as Application
        )
    )
    // The custom map drawing and sun map are per-device local data that never syncs to a shared
    // garden — showing them while viewing a garden you don't own would just surface YOUR OWN
    // unrelated drawing/zones, not the owner's, so they're hidden entirely for a non-owner.
    val canManageMap = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) } && FeatureVisibility.shouldShow(context, Feature.CUSTOM_MAP)
    val hasCustomMap = canManageMap && remember(ActiveGardenState.activeGardenId) { getCustomMapUri(context) != null }
    var showingCustom by remember { mutableStateOf(startOnCustom && hasCustomMap) }

    Column(Modifier.fillMaxSize()) {
        // Placement mode has no other way out — tapping the map either places the plant (which
        // itself navigates away via onPlacementSaved) or, for a view-only viewer, does nothing at
        // all (see MapScreen's onMapClick gate), leaving them stuck on this screen with no escape.
        if (placementModeForPlantId != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).zIndex(2f).padding(10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { onPlacementSaved?.invoke() }) { Text("‹ Cancel") }
            }
        }
        if (hasCustomMap || (canManageMap && FeatureVisibility.shouldShow(context, Feature.SUN_MAP))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(2f)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (hasCustomMap) {
                    Button(
                        onClick = { showingCustom = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!showingCustom) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                            contentColor = if (!showingCustom) Color.White else Color.Black
                        )
                    ) { Text("Real Map", fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { showingCustom = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showingCustom) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                            contentColor = if (showingCustom) Color.White else Color.Black
                        )
                    ) { Text("My Drawing", fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                }
                if (canManageMap && FeatureVisibility.shouldShow(context, Feature.SUN_MAP)) {
                    OutlinedButton(onClick = onOpenSunMap) { Text("☀️ Sun map", fontSize = 12.sp) }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (showingCustom && hasCustomMap) {
                CustomMapScreen(
                    viewModel = viewModel, pathViewModel = pathViewModel, onMarkerClick = onMarkerClick,
                    placementModeForPlantId = placementModeForPlantId, onPlacementSaved = onPlacementSaved,
                    onAddPlantAt = onAddPlantAtFraction
                )
            } else {
                MapScreen(
                    viewModel = viewModel, onMapTap = onAddPlantAtLatLng, onMarkerClick = onMarkerClick,
                    placementModeForPlantId = placementModeForPlantId, onPlacementSaved = onPlacementSaved,
                    onNavigateToHelp = onNavigateToHelp
                )
            }
        }
    }
}
// ============================================================================
// IRRIGATION PATH HELPERS
// ============================================================================

val zoneColorPalette = listOf(
    Color(0xFF3D8FB0), Color(0xFFB0793D), Color(0xFF6B3DB0), Color(0xFF3DB073),
    Color(0xFFB03D6B), Color(0xFFB0AA3D), Color(0xFF3D62B0), Color(0xFF8FB03D)
)

fun colorForZone(zone: String): Color {
    val idx = kotlin.math.abs(zone.hashCode()) % zoneColorPalette.size
    return zoneColorPalette[idx]
}

data class PathSegment(
    val type: String, // "main" | "drip" | "sprinkler" | "impact_sprinkler"
    val points: List<Offset>,
    val targetPlantIds: List<String> = emptyList(),
    val radius: Float? = null // fraction of map width — used by "sprinkler" only
)

fun segmentsToJson(segments: List<PathSegment>): String {
    val arr = org.json.JSONArray()
    segments.forEach { seg ->
        val obj = org.json.JSONObject()
        obj.put("type", seg.type)
        val pts = org.json.JSONArray()
        seg.points.forEach { p ->
            val pair = org.json.JSONArray()
            pair.put(p.x.toDouble()); pair.put(p.y.toDouble())
            pts.put(pair)
        }
        obj.put("points", pts)
        val targets = org.json.JSONArray()
        seg.targetPlantIds.forEach { targets.put(it) }
        obj.put("targets", targets)
        seg.radius?.let { obj.put("radius", it.toDouble()) }
        arr.put(obj)
    }
    return arr.toString()
}

fun jsonToSegments(json: String): List<PathSegment> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val type = obj.optString("type", "main")
            val ptsArr = obj.getJSONArray("points")
            val points = (0 until ptsArr.length()).map { j ->
                val pair = ptsArr.getJSONArray(j)
                Offset(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
            }
            val targetsArr = obj.optJSONArray("targets") ?: org.json.JSONArray()
            val targets = (0 until targetsArr.length()).map { targetsArr.getString(it) }
            val radius = if (obj.has("radius")) obj.optDouble("radius").toFloat() else null
            PathSegment(type, points, targets, radius)
        }
    } catch (_: Exception) { emptyList() }
}

fun distancePointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSq = abx * abx + aby * aby
    if (lengthSq == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSq).coerceIn(0f, 1f)
    val proj = Offset(a.x + t * abx, a.y + t * aby)
    return (p - proj).getDistance()
}

fun distancePointToPolyline(p: Offset, points: List<Offset>): Float {
    if (points.size < 2) return points.firstOrNull()?.let { (p - it).getDistance() } ?: Float.MAX_VALUE
    var minDist = Float.MAX_VALUE
    for (i in 0 until points.size - 1) {
        val d = distancePointToSegment(p, points[i], points[i + 1])
        if (d < minDist) minDist = d
    }
    return minDist
}

@Composable
fun CustomMapScreen(
    viewModel: PlantViewModel,
    pathViewModel: IrrigationPathViewModel,
    onMarkerClick: (String) -> Unit,
    placementModeForPlantId: String? = null,
    onPlacementSaved: (() -> Unit)? = null,
    onAddPlantAt: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plants by viewModel.filteredPlants.collectAsState()
    val paths by pathViewModel.paths.collectAsState()
    val mapUri = remember { getCustomMapUri(context) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current

    var scale by remember { mutableStateOf(1f) }
    var rotation by remember { mutableStateOf(0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var pendingFraction by remember { mutableStateOf<Offset?>(null) }
    var tooltipPlant by remember { mutableStateOf<PlantEntity?>(null) }

    // Irrigation path editing state
    var editingPaths by remember { mutableStateOf(false) }
    var editingPathId by remember { mutableStateOf<String?>(null) }
    var draftZone by remember { mutableStateOf("") }
    var draftOutlet by remember { mutableStateOf<Offset?>(null) }
    var placingOutlet by remember { mutableStateOf(false) }
    var isDrafting by remember { mutableStateOf(false) }
    var drawMode by remember { mutableStateOf<String?>(null) } // null | "main" | "drip" | "impact_sprinkler"
    var placingSprinklerCenter by remember { mutableStateOf(false) }
    var draftSprinklerCenter by remember { mutableStateOf<Offset?>(null) }
    var draftSprinklerRadius by remember { mutableStateOf(0.08f) }
    val draftSegments = remember { mutableStateListOf<PathSegment>() }
    val currentStroke = remember { mutableStateListOf<Offset>() }
    var attachingDripSegment by remember { mutableStateOf<List<Offset>?>(null) }
    val pendingDripTargets = remember { mutableStateListOf<String>() }
    var pathPendingDeletion by remember { mutableStateOf<IrrigationPathEntity?>(null) }
    var segmentPendingRemovalIndex by remember { mutableStateOf<Int?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "waterFlow")
    val dashPhase by infiniteTransition.animateFloat(
        initialValue = 40f, targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "dashPhase"
    )

    fun resetDraft() {
        draftZone = ""
        draftOutlet = null
        placingOutlet = false
        isDrafting = false
        drawMode = null
        draftSegments.clear()
        currentStroke.clear()
        attachingDripSegment = null
        pendingDripTargets.clear()
        editingPathId = null
        segmentPendingRemovalIndex = null
        placingSprinklerCenter = false
        draftSprinklerCenter = null
        draftSprinklerRadius = 0.08f
    }

    if (mapUri == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No custom map uploaded yet — add one in Help.", color = Color.Gray)
        }
        return
    }

    fun screenPointToFraction(tap: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val theta = Math.toRadians(-rotation.toDouble())
        val cosT = kotlin.math.cos(theta).toFloat()
        val sinT = kotlin.math.sin(theta).toFloat()
        val translated = tap - panOffset - center
        val rotated = Offset(
            translated.x * cosT - translated.y * sinT,
            translated.x * sinT + translated.y * cosT
        )
        val unscaled = rotated / scale
        val orig = unscaled + center
        return Offset(
            (orig.x / containerSize.width).coerceIn(0f, 1f),
            (orig.y / containerSize.height).coerceIn(0f, 1f)
        )
    }

    // Inverse of screenPointToFraction — where a map-fraction point actually renders on screen
    // right now, given the live pan/zoom/rotation. Used to hit-test existing plant markers against
    // a fixed on-screen radius (see below) rather than a fraction-space one, so the tap target
    // doesn't balloon in real screen size as the user zooms in.
    fun fractionToScreenPoint(frac: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val orig = Offset(frac.x * containerSize.width, frac.y * containerSize.height)
        val unscaled = orig - center
        val theta = Math.toRadians(rotation.toDouble())
        val cosT = kotlin.math.cos(theta).toFloat()
        val sinT = kotlin.math.sin(theta).toFloat()
        val rotated = Offset(
            unscaled.x * cosT - unscaled.y * sinT,
            unscaled.x * sinT + unscaled.y * cosT
        )
        return rotated * scale + center + panOffset
    }

    // Screen-space (not fraction-space) hit radius for "did the user tap an existing plant
    // marker" — matches each marker's own rendered radius exactly rather than padding out to a
    // comfortable touch target, and is zoom-independent (in dp, not fraction-space) so that
    // zooming in lets a plant be placed right next to an existing one instead of the marker's hit
    // target growing along with it. Deliberately precise rather than forgiving: a near-miss tap is
    // treated as "place a new plant here", which is what makes dense plantings placeable at all.
    fun plantMarkerRadiusPx(plant: PlantEntity): Float {
        val hasCategoryIcon = plant.category.isNotBlank() && plant.category != "Other"
        val diameterDp = if (hasCategoryIcon) 14.dp else 5.dp
        return with(density) { (diameterDp / 2).toPx() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { containerSize = it.size }
            .then(
                if (drawMode == null && attachingDripSegment == null && !editingPaths) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            scale = (scale * zoom).coerceIn(0.5f, 14f)
                            rotation += rot
                            panOffset += pan
                        }
                    }
                } else Modifier
            )
            .then(
                if (draftSprinklerCenter != null) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            draftSprinklerRadius = (draftSprinklerRadius * zoom).coerceIn(0.02f, 0.4f)
                        }
                    }
                } else Modifier
            )
            .pointerInput(editingPaths, placingOutlet, isDrafting, drawMode, attachingDripSegment, draftSegments.size, placingSprinklerCenter, draftSprinklerCenter) {
                detectTapGestures { tap ->
                    if (containerSize.width == 0) return@detectTapGestures
                    val frac = screenPointToFraction(tap)
                    if (editingPaths) {
                        when {
                            placingSprinklerCenter -> {
                                draftSprinklerCenter = frac
                                placingSprinklerCenter = false
                            }
                            placingOutlet -> {
                                draftOutlet = frac
                                placingOutlet = false
                                isDrafting = true
                            }
                            isDrafting && drawMode == null && attachingDripSegment == null && draftSprinklerCenter == null -> {
                                // Tap-to-remove — only active while editing a specific path's draft
                                val localPoint = Offset(frac.x * containerSize.width, frac.y * containerSize.height)
                                var closestIndex = -1
                                var closestDist = Float.MAX_VALUE
                                draftSegments.forEachIndexed { idx, seg ->
                                    val pxPoints = seg.points.map { Offset(it.x * containerSize.width, it.y * containerSize.height) }
                                    val dist = distancePointToPolyline(localPoint, pxPoints)
                                    if (dist < closestDist) { closestDist = dist; closestIndex = idx }
                                }
                                if (closestIndex >= 0 && closestDist <= 24f) {
                                    segmentPendingRemovalIndex = closestIndex
                                }
                            }
                            // else: browsing the main paths list — taps do nothing here
                        }
                    } else {
                        val nearestPlant = plants
                            .filter { it.mapX != null && it.mapY != null }
                            .minByOrNull { (fractionToScreenPoint(Offset(it.mapX!!.toFloat(), it.mapY!!.toFloat())) - tap).getDistance() }
                        val nearestDist = nearestPlant?.let { (fractionToScreenPoint(Offset(it.mapX!!.toFloat(), it.mapY!!.toFloat())) - tap).getDistance() }
                        if (nearestPlant != null && nearestDist != null && nearestDist <= plantMarkerRadiusPx(nearestPlant)) {
                            tooltipPlant = nearestPlant
                        } else {
                            pendingFraction = frac
                        }
                    }
                }
            }
            .then(
                if (drawMode != null) {
                    Modifier.pointerInput(drawMode) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentStroke.clear()
                                currentStroke.add(screenPointToFraction(offset))
                            },
                            onDrag = { change, _ ->
                                val frac = screenPointToFraction(change.position)
                                val last = currentStroke.lastOrNull()
                                if (last == null || (frac - last).getDistance() > 0.004f) {
                                    currentStroke.add(frac)
                                }
                            },
                            onDragEnd = {
                                if (currentStroke.size >= 2) {
                                    when (drawMode) {
                                        "main" -> {
                                            draftSegments.add(PathSegment("main", currentStroke.toList()))
                                            drawMode = null
                                        }
                                        "drip" -> {
                                            attachingDripSegment = currentStroke.toList()
                                            pendingDripTargets.clear()
                                            drawMode = null
                                        }
                                        "impact_sprinkler" -> {
                                            draftSegments.add(PathSegment("impact_sprinkler", currentStroke.toList()))
                                            drawMode = null
                                        }
                                    }
                                } else {
                                    drawMode = null
                                }
                                currentStroke.clear()
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    rotationZ = rotation,
                    translationX = panOffset.x, translationY = panOffset.y
                )
        ) {
            val mapRotation = remember { getCustomMapRotation(context) }
            AsyncImage(
                model = ImageRequest.Builder(context).data(mapUri).transformations(RotateTransformation(mapRotation.toFloat())).build(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                fun toPx(frac: Offset) = Offset(frac.x * w, frac.y * h)
                fun buildPath(points: List<Offset>): androidx.compose.ui.graphics.Path {
                    val path = androidx.compose.ui.graphics.Path()
                    if (points.isNotEmpty()) {
                        path.moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    }
                    return path
                }

                // Saved paths — dashed, animated to look like flowing water
                paths.forEach { pathEntity ->
                    val color = colorForZone(pathEntity.zone)
                    jsonToSegments(pathEntity.segmentsJson).forEach { seg ->
                        if (seg.type == "sprinkler" && seg.points.isNotEmpty()) {
                            val center = toPx(seg.points[0])
                            val radiusPx = (seg.radius ?: 0.08f) * w
                            drawCircle(color = color.copy(alpha = 0.22f), radius = radiusPx, center = center)
                            drawCircle(color = color, radius = radiusPx, center = center, style = Stroke(width = 3f))
                            drawCircle(color = color, radius = 6f, center = center)
                        } else {
                            val pxPoints = seg.points.map { toPx(it) }
                            if (pxPoints.size >= 2) {
                                val isMain = seg.type == "main"
                                val isImpact = seg.type == "impact_sprinkler"
                                drawPath(
                                    path = buildPath(pxPoints),
                                    color = if (isImpact) color.copy(alpha = 0.35f) else color,
                                    style = Stroke(
                                        width = if (isMain) 12f else if (isImpact) 26f else 5f,
                                        cap = StrokeCap.Round,
                                        pathEffect = when {
                                            isImpact -> null
                                            isMain -> PathEffect.dashPathEffect(floatArrayOf(26f, 14f), phase = 0f)
                                            else -> PathEffect.dashPathEffect(floatArrayOf(9f, 11f), phase = dashPhase)
                                        }
                                    )
                                )
                            }
                        }
                    }
                    val outletPx = toPx(Offset(pathEntity.outletX.toFloat(), pathEntity.outletY.toFloat()))
                    drawCircle(color = color, radius = 10f, center = outletPx)
                    drawCircle(color = Color.White, radius = 10f, center = outletPx, style = Stroke(width = 3f))
                }

                // In-progress draft
                draftSegments.forEachIndexed { idx, seg ->
                    val isSelected = segmentPendingRemovalIndex == idx
                    if (seg.type == "sprinkler" && seg.points.isNotEmpty()) {
                        val center = toPx(seg.points[0])
                        val radiusPx = (seg.radius ?: 0.08f) * w
                        val col = if (isSelected) Color(0xFFE53935) else Color(0xFF888888)
                        drawCircle(color = col.copy(alpha = 0.22f), radius = radiusPx, center = center)
                        drawCircle(color = col, radius = radiusPx, center = center, style = Stroke(width = 3f))
                    } else {
                        val pxPoints = seg.points.map { toPx(it) }
                        if (pxPoints.size >= 2) {
                            drawPath(
                                path = buildPath(pxPoints),
                                color = if (isSelected) Color(0xFFE53935) else Color(0xFF888888),
                                style = Stroke(
                                    width = (if (seg.type == "main") 12f else if (seg.type == "impact_sprinkler") 26f else 5f) + if (isSelected) 4f else 0f,
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                    }
                }
                draftOutlet?.let { o -> drawCircle(color = Color(0xFF3D8FB0), radius = 10f, center = toPx(o)) }
                draftSprinklerCenter?.let { c ->
                    val center = toPx(c)
                    val radiusPx = draftSprinklerRadius * w
                    drawCircle(color = Color(0xFFFF7A45).copy(alpha = 0.25f), radius = radiusPx, center = center)
                    drawCircle(color = Color(0xFFFF7A45), radius = radiusPx, center = center, style = Stroke(width = 3f))
                }

                if (currentStroke.size >= 2) {
                    drawPath(
                        path = buildPath(currentStroke.map { toPx(it) }),
                        color = Color(0xFFFF7A45),
                        style = Stroke(
                            width = when (drawMode) { "main" -> 12f; "impact_sprinkler" -> 26f; else -> 5f },
                            cap = StrokeCap.Round
                        )
                    )
                }
                attachingDripSegment?.let { pts ->
                    if (pts.size >= 2) {
                        drawPath(
                            path = buildPath(pts.map { toPx(it) }),
                            color = Color(0xFFFF7A45),
                            style = Stroke(width = 5f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            plants.forEach { plant ->
                if (plant.mapX != null && plant.mapY != null && containerSize.width > 0) {
                    val xDp = with(density) { (plant.mapX * containerSize.width).toFloat().toDp() }
                    val yDp = with(density) { (plant.mapY * containerSize.height).toFloat().toDp() }
                    val isPendingTarget = attachingDripSegment != null && pendingDripTargets.contains(plant.id)
                    val hasCategoryIcon = plant.category.isNotBlank() && plant.category != "Other"
                    val markerSize = if (hasCategoryIcon) 14.dp else 5.dp
                    val markerColor = Color(0xFFFF7A45)
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - markerSize / 2, y = yDp - markerSize / 2)
                            .size(markerSize)
                            .clip(RoundedCornerShape(50))
                            .background(if (hasCategoryIcon) Color.White else markerColor)
                            .then(if (isPendingTarget) Modifier.border(1.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                            .then(
                                // Only intercepts taps while picking drip-segment targets. Plain
                                // plant lookup/placement taps are handled by the parent's fixed-radius
                                // hit test instead, so this tiny marker's touch target doesn't grow
                                // to match it when zoomed in (see fractionToScreenPoint above).
                                if (attachingDripSegment != null) {
                                    Modifier.clickable {
                                        if (pendingDripTargets.contains(plant.id)) pendingDripTargets.remove(plant.id)
                                        else pendingDripTargets.add(plant.id)
                                    }
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasCategoryIcon) Text(categoryMarkerEmoji(plant.category), fontSize = 8.sp)
                    }
                }
            }

            pendingFraction?.let { frac ->
                val xDp = with(density) { (frac.x * containerSize.width).toDp() }
                val yDp = with(density) { (frac.y * containerSize.height).toDp() }
                Box(
                    modifier = Modifier.offset(x = xDp - 4.dp, y = yDp - 4.dp).size(8.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xFFFF7A45).copy(alpha = 0.85f))
                        .border(1.dp, Color.White, RoundedCornerShape(50))
                )
            }
        }

        pendingFraction?.let { frac ->
            AlertDialog(
                onDismissRequest = { pendingFraction = null },
                title = { Text("Add plant here?") },
                text = { Text("Place a plant marker at this spot on your drawing.") },
                confirmButton = {
                    TextButton(onClick = {
                        val f = frac
                        pendingFraction = null
                        if (placementModeForPlantId != null) {
                            scope.launch {
                                val plant = viewModel.getById(placementModeForPlantId)
                                if (plant != null) viewModel.save(plant.copy(mapX = f.x.toDouble(), mapY = f.y.toDouble()))
                                onPlacementSaved?.invoke()
                            }
                        } else {
                            onAddPlantAt(f.x.toDouble(), f.y.toDouble())
                        }
                    }) { Text("Add plant here") }
                },
                dismissButton = { TextButton(onClick = { pendingFraction = null }) { Text("Cancel") } }
            )
        }

        tooltipPlant?.let { plant ->
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { /* consume */ } }
            ) {
                PlantTooltipCard(plant = plant, onEdit = { onMarkerClick(plant.id) }, onDismiss = { tooltipPlant = null })
            }
        }

        if (pathPendingDeletion != null) {
            val p = pathPendingDeletion!!
            AlertDialog(
                onDismissRequest = { pathPendingDeletion = null },
                title = { Text("Delete this path?") },
                text = { Text("This removes the \"${p.zone}\" irrigation path. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = { pathViewModel.delete(p.id); pathPendingDeletion = null }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { pathPendingDeletion = null }) { Text("Cancel") } }
            )
        }
        segmentPendingRemovalIndex?.let { idx ->
            if (idx !in draftSegments.indices) {
                segmentPendingRemovalIndex = null
            } else {
                AlertDialog(
                    onDismissRequest = { segmentPendingRemovalIndex = null },
                    title = { Text("Remove this segment?") },
                    text = { Text("Removes the highlighted pipe/drip segment from this path's draft. Use \"Cancel path\" instead if you want to discard all your changes.") },
                    confirmButton = {
                        TextButton(onClick = {
                            draftSegments.removeAt(idx)
                            segmentPendingRemovalIndex = null
                        }) { Text("Remove") }
                    },
                    dismissButton = { TextButton(onClick = { segmentPendingRemovalIndex = null }) { Text("Cancel") } }
                )
            }
        }
        if (placementModeForPlantId == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (!editingPaths) {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { editingPaths = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8FB0))
                        ) { Text("💧 Edit irrigation paths", fontSize = 12.sp) }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            when {
                                placingOutlet -> {
                                    Text("Tap the drawing to mark where the outlet/tap starts 🚰", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { placingOutlet = false; if (draftOutlet == null) resetDraft() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel", fontSize = 12.sp)
                                    }
                                }
                                placingSprinklerCenter -> {
                                    Text("Tap the drawing to place the sprinkler 💧", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { placingSprinklerCenter = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel", fontSize = 12.sp)
                                    }
                                }
                                draftSprinklerCenter != null -> {
                                    Text("Pinch to adjust the spread, then confirm", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                draftSegments.add(PathSegment("sprinkler", listOf(draftSprinklerCenter!!), radius = draftSprinklerRadius))
                                                draftSprinklerCenter = null
                                                draftSprinklerRadius = 0.08f
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Confirm", fontSize = 12.sp) }
                                        OutlinedButton(
                                            onClick = { draftSprinklerCenter = null; draftSprinklerRadius = 0.08f },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Cancel", fontSize = 12.sp) }
                                    }
                                }
                                drawMode != null -> {
                                    Text(
                                        when (drawMode) {
                                            "main" -> "Drawing main pipe — drag along the pipe, lift when done"
                                            "impact_sprinkler" -> "Drawing impact sprinkler sweep — drag along its arc, lift when done"
                                            else -> "Drawing drip line — drag from the pipe toward the plant(s), lift when done"
                                        },
                                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { drawMode = null; currentStroke.clear() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel segment", fontSize = 12.sp)
                                    }
                                }
                                attachingDripSegment != null -> {
                                    Text("Tap the plant(s) this drip line waters", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${pendingDripTargets.size} plant(s) selected", fontSize = 11.sp, color = Color.Gray)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                draftSegments.add(PathSegment("drip", attachingDripSegment!!, pendingDripTargets.toList()))
                                                attachingDripSegment = null
                                                pendingDripTargets.clear()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Confirm", fontSize = 12.sp) }
                                        OutlinedButton(
                                            onClick = { attachingDripSegment = null; pendingDripTargets.clear() },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Discard", fontSize = 12.sp) }
                                    }
                                }
                                isDrafting -> {
                                    Text("Editing path for \"$draftZone\"", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        "${draftSegments.count { it.type == "main" }} main segment(s), ${draftSegments.count { it.type == "drip" }} drip line(s), " +
                                                "${draftSegments.count { it.type == "sprinkler" || it.type == "impact_sprinkler" }} sprinkler(s)",
                                        fontSize = 11.sp, color = Color.Gray
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { drawMode = "main" }, modifier = Modifier.weight(1f)) { Text("Draw main pipe", fontSize = 11.sp) }
                                        Button(onClick = { drawMode = "drip" }, modifier = Modifier.weight(1f)) { Text("Draw drip line", fontSize = 11.sp) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { placingSprinklerCenter = true }, modifier = Modifier.weight(1f)) { Text("Add sprinkler", fontSize = 11.sp) }
                                        Button(onClick = { drawMode = "impact_sprinkler" }, modifier = Modifier.weight(1f)) { Text("Draw impact sprinkler", fontSize = 11.sp) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { if (draftSegments.isNotEmpty()) draftSegments.removeAt(draftSegments.size - 1) },
                                            enabled = draftSegments.isNotEmpty(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Undo last segment", fontSize = 11.sp) }
                                        OutlinedButton(onClick = { placingOutlet = true }, modifier = Modifier.weight(1f)) {
                                            Text("Move outlet", fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                val outlet = draftOutlet
                                                if (outlet != null && draftSegments.isNotEmpty()) {
                                                    pathViewModel.save(
                                                        IrrigationPathEntity(
                                                            id = editingPathId ?: "path-${System.currentTimeMillis()}",   // was: always new id
                                                            zone = draftZone,
                                                            outletX = outlet.x.toDouble(),
                                                            outletY = outlet.y.toDouble(),
                                                            segmentsJson = segmentsToJson(draftSegments)
                                                        )
                                                    )
                                                    resetDraft()
                                                }
                                            },
                                            enabled = draftOutlet != null && draftSegments.isNotEmpty(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Finish path", fontSize = 12.sp) }
                                        OutlinedButton(onClick = { resetDraft() }, modifier = Modifier.weight(1f)) {
                                            Text("Cancel path", fontSize = 12.sp)
                                        }
                                    }
                                    if (draftOutlet == null || draftSegments.isEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Draw at least one segment and set the outlet before finishing. Cancel discards this draft without saving.",
                                            fontSize = 10.sp, color = Color.Gray
                                        )
                                    }
                                }
                                else -> {
                                    Text("Irrigation paths", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = draftZone,
                                        onValueChange = { draftZone = it },
                                        label = { Text("Zone name") },
                                        supportingText = { Text("Match a Tuya zone name for consistent colouring", fontSize = 10.sp) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { if (draftZone.isNotBlank()) placingOutlet = true },
                                            enabled = draftZone.isNotBlank(),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Start new path", fontSize = 12.sp) }
                                        OutlinedButton(onClick = { editingPaths = false; segmentPendingRemovalIndex = null }, modifier = Modifier.weight(1f)) {
                                            Text("Done", fontSize = 12.sp)
                                        }
                                    }
                                    if (paths.isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        HorizontalDivider()
                                        Spacer(Modifier.height(6.dp))
                                        paths.forEach { p ->
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colorForZone(p.zone)))
                                                Spacer(Modifier.width(8.dp))
                                                Text(p.zone, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    draftZone = p.zone
                                                    draftOutlet = Offset(p.outletX.toFloat(), p.outletY.toFloat())
                                                    draftSegments.clear()
                                                    draftSegments.addAll(jsonToSegments(p.segmentsJson))
                                                    editingPathId = p.id
                                                    segmentPendingRemovalIndex = null
                                                    isDrafting = true
                                                }) { Text("Edit", fontSize = 11.sp) }
                                                TextButton(onClick = { pathPendingDeletion = p }) { Text("Delete", fontSize = 11.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// LIST SCREEN
// ============================================================================
object ListScreenState {
    val searchState = mutableStateOf("")
    val collapsedGroups = mutableStateListOf<String>()
    val scrollIndex = mutableStateOf(0)
    val scrollOffset = mutableStateOf(0)
}

@Composable
fun ListScreen(
    viewModel: PlantViewModel, onPlantClick: (String) -> Unit, onAddPlant: () -> Unit,
    onChangeLocation: (String, Boolean) -> Unit, onOpenLocationPhotos: (String) -> Unit
) {
    val context = LocalContext.current
    val canEdit = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }
    val growthViewModel: GrowthPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val plantIdsWithPhotos by growthViewModel.plantIdsWithPhotos.collectAsState()
    val plants by viewModel.filteredPlants.collectAsState()
    var search by ListScreenState.searchState
    var locationChangePlantId by remember { mutableStateOf<String?>(null) }
    var showListFieldsDialog by remember { mutableStateOf(false) }
    var showProgressPhotosPicker by remember { mutableStateOf(false) }
    var groupBy by remember { mutableStateOf(getListGroupBy(context)) }
    var sortBy by remember { mutableStateOf(getListSortBy(context)) }
    var fieldKeys by remember { mutableStateOf(getListFieldKeys(context)) }
    val hasCustomMap = remember { getCustomMapUri(context) != null }
    val now = remember { System.currentTimeMillis() }
    val collapsedGroups = ListScreenState.collapsedGroups

    // Every text field except notes (free-form, easy to search separately if ever needed), numeric
    // fields, date fields, and latitude/longitude — those aren't meaningful to match against typed
    // search text.
    val filtered = plants.filter {
        search.isBlank() ||
                it.name.contains(search, ignoreCase = true) ||
                it.sci.contains(search, ignoreCase = true) ||
                it.location.contains(search, ignoreCase = true) ||
                it.id.contains(search, ignoreCase = true) ||
                it.sun.contains(search, ignoreCase = true) ||
                it.water.contains(search, ignoreCase = true) ||
                it.soil.contains(search, ignoreCase = true) ||
                it.soilPh.contains(search, ignoreCase = true) ||
                it.category.contains(search, ignoreCase = true) ||
                it.frost.contains(search, ignoreCase = true) ||
                it.native.contains(search, ignoreCase = true) ||
                it.pollinator.contains(search, ignoreCase = true) ||
                it.source.contains(search, ignoreCase = true) ||
                it.wateringSystem.contains(search, ignoreCase = true)
    }

    fun groupKey(p: PlantEntity): String = when (groupBy) {
        "sun" -> p.sun.ifBlank { "Unspecified sun" }
        "water" -> p.water.ifBlank { "Unspecified water" }
        "category" -> p.category.ifBlank { "Unspecified category" }
        "none" -> ""
        else -> p.location.ifBlank { "Unspecified location" }
    }

    fun sortedWithin(list: List<PlantEntity>): List<PlantEntity> = when (sortBy) {
        "due" -> list.sortedWith(
            compareBy(
                { p -> computeWateringStatus(p, now)?.nextDueMillis ?: Long.MAX_VALUE },
                { p -> p.name.lowercase() }
            )
        )
        else -> list.sortedBy { it.name.lowercase() }
    }

    val grouped: Map<String, List<PlantEntity>> = if (groupBy == "none") {
        mapOf("" to filtered)
    } else {
        filtered.groupBy { groupKey(it) }
            .toSortedMap(compareBy { if (it.startsWith("Unspecified")) "\uFFFF" else it.lowercase() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                label = { Text("Search plants, locations, or by Plant ID") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    DropdownField(
                        label = "Group by", options = listGroupOptions.map { it.label },
                        selected = listGroupOptions.firstOrNull { it.key == groupBy }?.label ?: "Location",
                        onSelect = { label ->
                            val key = listGroupOptions.firstOrNull { it.label == label }?.key ?: "location"
                            groupBy = key; setListGroupBy(context, key)
                        }
                    )
                }
                Box(Modifier.weight(1f)) {
                    DropdownField(
                        label = "Sort by", options = listSortOptions.map { it.label },
                        selected = listSortOptions.firstOrNull { it.key == sortBy }?.label ?: "Name",
                        onSelect = { label ->
                            val key = listSortOptions.firstOrNull { it.label == label }?.key ?: "name"
                            sortBy = key; setListSortBy(context, key)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow {
                TextButton(onClick = { showListFieldsDialog = true }) { Text("Customise fields shown", fontSize = 12.sp) }
                if (FeatureVisibility.shouldShow(context, Feature.PROGRESS_PHOTOS)) {
                    TextButton(onClick = { showProgressPhotosPicker = true }) { Text("📷 Progress photos", fontSize = 12.sp) }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No plants match — tap + to add one.", color = Color.Gray)
                }
            } else {
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = ListScreenState.scrollIndex.value,
                    initialFirstVisibleItemScrollOffset = ListScreenState.scrollOffset.value
                )
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            ListScreenState.scrollIndex.value = index
                            ListScreenState.scrollOffset.value = offset
                        }
                }
                LazyColumn(state = listState) {
                    grouped.forEach { (label, plantsInGroup) ->
                        if (label.isNotBlank()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            if (collapsedGroups.contains(label)) collapsedGroups.remove(label)
                                            else collapsedGroups.add(label)
                                        }
                                        .padding(top = 12.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF3A5A40),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        if (collapsedGroups.contains(label)) "▸ ${plantsInGroup.size}" else "▾",
                                        color = Color(0xFF3A5A40), fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        if (!collapsedGroups.contains(label)) {
                            items(sortedWithin(plantsInGroup)) { plant ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPlantClick(plant.id) }
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (plant.photoUri != null) {
                                        PlantPhoto(
                                            photoUri = plant.photoUri, photoThumbnailBase64 = plant.photoThumbnailBase64,
                                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE3DDCF)),
                                            contentAlignment = Alignment.Center
                                        ) { Text("🌿") }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(plant.name, fontWeight = FontWeight.SemiBold)
                                            if (plantIdsWithPhotos.contains(plant.id)) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("📸", fontSize = 12.sp)
                                            }
                                        }
                                        val subtitle = fieldKeys.mapNotNull { listFieldValue(it, plant) }.joinToString(" · ")
                                        if (subtitle.isNotBlank()) {
                                            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
                                        }
                                    }
                                    if (canEdit) {
                                        IconButton(onClick = { locationChangePlantId = plant.id }) { Text("📍") }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddPlant,
            containerColor = Color(0xFFFF7A45),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
        ) {
            Text("+", fontSize = 28.sp)
        }

        if (locationChangePlantId != null) {
            val id = locationChangePlantId!!
            AlertDialog(
                onDismissRequest = { locationChangePlantId = null },
                title = { Text("Change plant location") },
                text = {
                    Column {
                        Text(
                            "Pick where to place this plant. You'll be taken to the map — tap the new spot.",
                            fontSize = 13.sp, color = Color.Gray
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { locationChangePlantId = null; onChangeLocation(id, false) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Real map")
                        }
                        if (hasCustomMap) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { locationChangePlantId = null; onChangeLocation(id, true) }, modifier = Modifier.fillMaxWidth()) {
                                Text("My drawing")
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { locationChangePlantId = null }) { Text("Cancel") } }
            )
        }

        if (showListFieldsDialog) {
            val draftFields = remember { mutableStateListOf(*fieldKeys.toTypedArray()) }
            AlertDialog(
                onDismissRequest = { showListFieldsDialog = false },
                title = { Text("Customise fields shown") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Choose which details appear under each plant's name:", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        listFieldCatalog.forEach { option ->
                            val checked = draftFields.contains(option.key)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { if (checked) draftFields.remove(option.key) else draftFields.add(option.key) }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (checked) draftFields.remove(option.key) else draftFields.add(option.key)
                                })
                                Text(option.label, fontSize = 13.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val finalFields = if (draftFields.isEmpty()) defaultListFieldKeys else draftFields.toList()
                        fieldKeys = finalFields
                        setListFieldKeys(context, finalFields)
                        showListFieldsDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showListFieldsDialog = false }) { Text("Cancel") } }
            )
        }

        if (showProgressPhotosPicker) {
            val locationPhotoViewModel: LocationPhotoViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            )
            val photoCounts by locationPhotoViewModel.locationsWithPhotoCounts.collectAsState()
            val allLocations = remember(plants) { plants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted() }
            AlertDialog(
                onDismissRequest = { showProgressPhotosPicker = false },
                title = { Text("Progress photos") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (allLocations.isEmpty()) {
                            Text("Add a location to a plant first to track progress photos for it.", fontSize = 12.sp, color = Color.Gray)
                        } else {
                            Text("Tap a zone below to view or add photos of that area.", fontSize = 12.sp, color = Color.Gray)
                            Spacer(Modifier.height(10.dp))
                            allLocations.forEach { location ->
                                val count = photoCounts[location] ?: 0
                                OutlinedButton(
                                    onClick = { showProgressPhotosPicker = false; onOpenLocationPhotos(location) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    Text("📷", fontSize = 14.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(location, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                                    Text(
                                        if (count == 1) "1 photo" else "$count photos",
                                        fontSize = 11.sp, color = Color.Gray
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("›", fontSize = 16.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showProgressPhotosPicker = false }) { Text("Close") } }
            )
        }
    }
}

// ============================================================================
// IRRIGATION TAB
// ============================================================================
// Shows every watering event (location, start, end, duration), with filters
// by zone and by date. Data comes from Room (populated by WateringZoneViewModel
// syncs against the Tuya Cloud API), and is separately backed up to an
// irrigation_log.csv in the user's chosen photo storage location since Tuya
// only retains ~7 days of logs at any given moment.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrrigationScreen(wateringEvents: List<WateringEvent>, plants: List<PlantEntity>, onPlantClick: (String) -> Unit = {}) {
    val context = LocalContext.current
    var zoneFilter by remember { mutableStateOf("All") }
    var dateFilter by remember { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    val zones = remember(wateringEvents) { listOf("All") + wateringEvents.map { it.zone }.distinct().sorted() }
    val sdfDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    val now = remember { System.currentTimeMillis() }
    val statused = remember(plants, now) {
        plants.mapNotNull { p -> computeWateringStatus(p, now)?.let { p to it } }
    }
    val dueOrOverdue = remember(statused, now) {
        statused.filter { (_, status) -> status.nextDueMillis != null && status.nextDueMillis <= now + 3 * 86_400_000L }
            .sortedBy { (_, status) -> status.sortKey() }
    }
    val unscheduled = remember(statused) {
        statused.filter { (_, status) -> status.nextDueMillis == null }
    }

    val filtered = wateringEvents.filter { e ->
        (zoneFilter == "All" || e.zone == zoneFilter) &&
                (dateFilter.isBlank() || sdfDate.format(Date(e.startTime)) == dateFilter)
    }.sortedByDescending { it.startTime }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).imePadding()) {
        Text("Irrigation", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(12.dp))

        ExpandableSection(title = "Water now/soon (${dueOrOverdue.size})", initiallyExpanded = true) {
            if (dueOrOverdue.isEmpty()) {
                Text("Nothing due within the next 3 days.", fontSize = 12.sp, color = Color.Gray)
            } else {
                dueOrOverdue.forEach { (plant, status) ->
                    val overdue = status.nextDueMillis!! <= now
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onPlantClick(plant.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (overdue) Color(0xFFFBE9E7) else Color(0xFFF5F5F0)
                        )
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    plant.location.ifBlank { "No location" },
                                    fontSize = 11.sp, color = Color.Gray
                                )
                            }
                            Text(
                                status.label, fontSize = 12.sp,
                                color = if (overdue) Color(0xFFB23B3B) else Color(0xFF3A5A40),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (unscheduled.isNotEmpty()) {
            Text("Unscheduled (never watered)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                unscheduled.forEach { (plant, _) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onPlantClick(plant.id) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F0))
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plant.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    plant.location.ifBlank { "No location" },
                                    fontSize = 11.sp, color = Color.Gray
                                )
                            }
                            Text("Never watered", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (FeatureVisibility.shouldShow(context, Feature.COST_WATER_TRACKING)) {
        ExpandableSection(title = "Water usage & cost (estimated)") {
            val flowRateViewModel: WaterFlowRateViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            )
            val flowRates by flowRateViewModel.flowRates.collectAsState()
            val flowRateByKey = remember(flowRates) { flowRates.associateBy { it.zone to it.outlet } }

            var waterRate by remember { mutableStateOf(getWaterRatePerKiloliter(context)) }
            var waterRateText by remember { mutableStateOf(if (waterRate > 0) waterRate.toString() else "") }

            Text(
                "Estimated from your logged watering durations and a flow rate you calibrate per zone/outlet — not a metered reading.",
                fontSize = 11.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = waterRateText,
                onValueChange = { new ->
                    waterRateText = new.filter { it.isDigit() || it == '.' }
                    waterRateText.toDoubleOrNull()?.let { waterRate = it; setWaterRatePerKiloliter(context, it) }
                },
                label = { Text("Water rate (\$ per kL)") },
                supportingText = {
                    Text(
                        "What your water utility charges per 1,000 litres (1 kilolitre) — check a recent water bill, usually shown as \"\$/kL\" or \"\$/1000L\".",
                        fontSize = 11.sp
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))

            val zoneOutlets = remember(wateringEvents) {
                wateringEvents.map { it.zone to it.outlet }.distinct().sortedBy { it.first + it.second }
            }
            Text("Flow rate calibration", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(
                "For each zone/outlet below: put a 1-litre container under it, run the water, time how many seconds it takes to fill, then enter that number and tap Save. This converts to a flow rate (litres/minute) used to turn logged watering durations into litres used.",
                fontSize = 11.sp, color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))

            if (zoneOutlets.isEmpty()) {
                Text("No watering events logged yet.", fontSize = 12.sp, color = Color.Gray)
            } else {
                zoneOutlets.forEach { (zone, outlet) ->
                    val existing = flowRateByKey[zone to outlet]
                    var secondsText by remember(zone, outlet) {
                        mutableStateOf(existing?.let { "%.1f".format(60.0 / it.litersPerMinute) } ?: "")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("$zone — outlet $outlet", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (existing != null) {
                                Text("${"%.2f".format(existing.litersPerMinute)} L/min", fontSize = 11.sp, color = Color(0xFF3A5A40))
                            } else {
                                Text("Not calibrated", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                        OutlinedTextField(
                            value = secondsText,
                            onValueChange = { new -> secondsText = new.filter { it.isDigit() || it == '.' } },
                            label = { Text("Secs/1L") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(100.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            secondsText.toDoubleOrNull()?.takeIf { it > 0 }?.let { seconds ->
                                flowRateViewModel.save(zone, outlet, 60.0 / seconds)
                            }
                        }) { Text("Save") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            val monthStart = remember {
                java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            val monthEvents = remember(wateringEvents, monthStart) { wateringEvents.filter { it.startTime >= monthStart } }
            val calibratedEvents = remember(monthEvents, flowRateByKey) { monthEvents.filter { flowRateByKey.containsKey(it.zone to it.outlet) } }
            val uncalibratedCount = monthEvents.size - calibratedEvents.size
            val totalLiters = calibratedEvents.sumOf { e -> e.durationMinutes * (flowRateByKey[e.zone to e.outlet]?.litersPerMinute ?: 0.0) }
            val totalCost = totalLiters / 1000.0 * waterRate

            val allTimeCalibrated = remember(wateringEvents, flowRateByKey) { wateringEvents.filter { flowRateByKey.containsKey(it.zone to it.outlet) } }
            val allTimeLiters = allTimeCalibrated.sumOf { e -> e.durationMinutes * (flowRateByKey[e.zone to e.outlet]?.litersPerMinute ?: 0.0) }
            val allTimeCost = allTimeLiters / 1000.0 * waterRate

            Text("This month so far", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text("${"%.0f".format(totalLiters)} L", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
            if (waterRate > 0) {
                Text("≈ \$${"%.2f".format(totalCost)}", fontSize = 14.sp, color = Color(0xFF3A5A40))
            } else {
                Text("Enter a water rate above to see an estimated cost.", fontSize = 11.sp, color = Color.Gray)
            }
            when {
                monthEvents.isEmpty() && flowRates.isEmpty() -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No zones calibrated yet — enter a flow rate above for at least one zone/outlet.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
                monthEvents.isEmpty() -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No watering events logged since the start of this month — that's why this reads 0, not a calibration problem. See \"All time\" below.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
                uncalibratedCount > 0 -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$uncalibratedCount event(s) this month excluded — calibrate the zone/outlet above to include them.",
                        fontSize = 11.sp, color = Color(0xFFB23B3B)
                    )
                }
            }

            val byZone = calibratedEvents.groupBy { it.zone }
            if (byZone.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                byZone.forEach { (zone, zoneEvents) ->
                    val liters = zoneEvents.sumOf { e -> e.durationMinutes * (flowRateByKey[e.zone to e.outlet]?.litersPerMinute ?: 0.0) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(zone, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${"%.0f".format(liters)} L", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("All time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text("${"%.0f".format(allTimeLiters)} L", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
            if (waterRate > 0) {
                Text("≈ \$${"%.2f".format(allTimeCost)}", fontSize = 13.sp, color = Color(0xFF3A5A40))
            }
        }
        }
        Spacer(Modifier.height(16.dp))

        if (FeatureVisibility.shouldShow(context, Feature.WATERING_HISTORY)) {
        ExpandableSection(title = "Watering history (${filtered.size})") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    DropdownField(label = "Zone", options = zones, selected = zoneFilter, onSelect = { zoneFilter = it })
                }
                Box(Modifier.weight(1f)) { DatePickerField("Date", dateFilter, { dateFilter = it }) }
            }
            if (dateFilter.isNotBlank()) {
                TextButton(onClick = { dateFilter = "" }) { Text("Clear date filter") }
            }
            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                val irrigationSystemName = when (getIrrigationSystem(context)) {
                    IrrigationSystem.RACHIO -> "Rachio"
                    else -> "Tuya"
                }
                Text("No irrigation data yet — connect $irrigationSystemName zones and sync in Help.", color = Color.Gray)
            } else {
                filtered.forEach { e ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(e.zone, fontWeight = FontWeight.SemiBold)
                            val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", locale)
                            val end = e.startTime + e.durationMinutes * 60_000L
                            Text("Start: ${sdf.format(Date(e.startTime))}", fontSize = 12.sp, color = Color.Gray)
                            Text("End: ${sdf.format(Date(end))}", fontSize = 12.sp, color = Color.Gray)
                            Text("Duration: ${e.durationMinutes} min", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ============================================================================
// REUSABLE DROPDOWN FIELD
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String, options: List<String>, selected: String,
    onSelect: (String) -> Unit, helperText: String? = null, enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) },
            placeholder = { Text("Pick an option") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            supportingText = helperText?.let { { Text(it, fontSize = 11.sp) } },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

// ============================================================================
// DATE PICKER FIELD
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String, dateString: String, onDateChange: (String) -> Unit,
    restrictToPastOrToday: Boolean = false,
    allowNotApplicable: Boolean = false,
    allowClear: Boolean = true,
    enabled: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = dateString, onValueChange = {}, readOnly = true, enabled = enabled,
        label = { Text(label) }, placeholder = { Text("YYYY-MM-DD") },
        trailingIcon = { IconButton(onClick = { if (enabled) showDialog = true }, enabled = enabled) { Text("📅") } },
        modifier = Modifier.fillMaxWidth()
    )
    if (showDialog) {
        val datePickerState = rememberDatePickerState(
            selectableDates = if (restrictToPastOrToday) {
                // The picker reports each candidate day as UTC midnight of that calendar date, so
                // comparing it against a raw System.currentTimeMillis() instant breaks in any
                // timezone ahead of UTC: today's UTC-midnight representation is later than the
                // actual current UTC instant until local time catches up to the UTC offset (e.g.
                // until 10am in AEST/UTC+10), making "today" look like a future date and get
                // excluded — this is exactly why only yesterday and earlier were selectable.
                // Fix: compare against UTC midnight of *today's local date* instead, using the
                // same yyyy-MM-dd/UTC convention dateStringToMillis uses everywhere else.
                val todayUtcMidnight = run {
                    val localSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val utcSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    utcSdf.parse(localSdf.format(Date()))!!.time
                }
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis <= todayUtcMidnight
                }
            } else DatePickerDefaults.AllDates
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        onDateChange(sdf.format(Date(millis)))
                    }
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowNotApplicable && dateString != "N/A") {
                        TextButton(onClick = { onDateChange("N/A"); showDialog = false }) { Text("N/A") }
                    }
                    if (allowClear && dateString.isNotBlank()) {
                        TextButton(onClick = { onDateChange(""); showDialog = false }) { Text("Clear") }
                    }
                    TextButton(onClick = { showDialog = false }) { Text("Cancel") }
                }
            }
        ) { DatePicker(state = datePickerState) }
    }
}

// ============================================================================
// WATERING SCHEDULE HELPERS
// ============================================================================

fun dateStringToMillis(s: String): Long? {
    if (s.isBlank()) return null
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s)?.time
    } catch (_: Exception) { null }
}

fun millisToDateString(millis: Long?): String {
    if (millis == null) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(millis))
}

data class WateringStatus(val nextDueMillis: Long?, val label: String) {
    /** Sort key for priority ordering — never-watered plants sort first (most urgent). */
    fun sortKey(): Long = nextDueMillis ?: Long.MIN_VALUE
}

/**
 * Dec/Jan/Feb = summer + Jun/Jul/Aug = winter for a Southern-hemisphere garden, flipped for a
 * Northern-hemisphere one (Help → Weather-aware reminders); else base frequency. [hemisphere]
 * defaults to the live [HemisphereState] singleton, which is correct for any Compose call site —
 * a background caller with no composition (the reminder worker) should pass [getHemisphere]'s
 * result explicitly instead, since the singleton may not be synced yet in a cold-started process.
 */
fun effectiveWateringFrequencyDays(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis(), hemisphere: Hemisphere = HemisphereState.value): Int? {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    val isDecJanFeb = cal.get(java.util.Calendar.MONTH) in listOf(java.util.Calendar.DECEMBER, java.util.Calendar.JANUARY, java.util.Calendar.FEBRUARY)
    val isJunJulAug = cal.get(java.util.Calendar.MONTH) in listOf(java.util.Calendar.JUNE, java.util.Calendar.JULY, java.util.Calendar.AUGUST)
    val isSummer = if (hemisphere == Hemisphere.SOUTHERN) isDecJanFeb else isJunJulAug
    val isWinter = if (hemisphere == Hemisphere.SOUTHERN) isJunJulAug else isDecJanFeb
    return when {
        isSummer -> plant.summerWateringFrequencyDays ?: plant.wateringFrequencyDays
        isWinter -> plant.winterWateringFrequencyDays ?: plant.wateringFrequencyDays
        else -> plant.wateringFrequencyDays
    }
}

/** Generic due-date calculator, reused by watering, fertilising, and pruning. */
fun computeCareStatus(lastDate: Long?, frequencyDays: Int?, nowMillis: Long = System.currentTimeMillis()): WateringStatus? {
    val freq = frequencyDays ?: return null
    val last = lastDate ?: return WateringStatus(nextDueMillis = null, label = "Never — do now")
    val nextDue = last + freq * 86_400_000L
    val diffDays = ((nextDue - nowMillis) / 86_400_000L).toInt()
    val label = when {
        diffDays < 0 -> "Overdue by ${-diffDays} day(s)"
        diffDays == 0 -> "Due today"
        else -> "Due in $diffDays day(s)"
    }
    return WateringStatus(nextDueMillis = nextDue, label = label)
}

fun computeFertiliseStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastFertilisedDate, plant.fertiliseFrequencyDays, nowMillis)

fun computePruneStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastPrunedDate, plant.pruneFrequencyDays, nowMillis)

fun computeFeedStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastFedDate, plant.feedFrequencyDays, nowMillis)

/** Returns null if no watering frequency is configured (nothing to schedule). See [effectiveWateringFrequencyDays] for the [hemisphere] default's caveat for background callers. */
fun computeWateringStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis(), hemisphere: Hemisphere = HemisphereState.value): WateringStatus? {
    val freq = effectiveWateringFrequencyDays(plant, nowMillis, hemisphere) ?: return null
    val last = plant.lastWateredDate
        ?: return WateringStatus(nextDueMillis = null, label = "Never watered — water now")

    val nextDue = last + freq * 86_400_000L
    val diffDays = ((nextDue - nowMillis) / 86_400_000L).toInt()
    val label = when {
        diffDays < 0 -> "Overdue by ${-diffDays} day(s)"
        diffDays == 0 -> "Due today"
        else -> "Due in $diffDays day(s)"
    }
    return WateringStatus(nextDueMillis = nextDue, label = label)
}

fun frostTenderOutdoorPlants(plants: List<PlantEntity>): List<PlantEntity> =
    plants.filter { (it.frost == "Tender" || it.frost == "Half-hardy") && !it.isIndoor }

fun getFrostWarningsEnabled(context: Context): Boolean = gardenScopedBoolean(context, "frost_warnings_enabled", true)
fun setFrostWarningsEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "frost_warnings_enabled", value)
fun getFrostWarningsEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "frost_warnings_enabled", true, gardenIdOverride = gardenId)
fun getFrostTempThreshold(context: Context): Double = gardenScopedFloat(context, "frost_temp_threshold", 2.0f).toDouble()
fun setFrostTempThreshold(context: Context, value: Double) = setGardenScopedFloat(context, "frost_temp_threshold", value.toFloat())
fun getFrostTempThresholdFor(context: Context, gardenId: String): Double =
    gardenScopedFloat(context, "frost_temp_threshold", 2.0f, gardenIdOverride = gardenId).toDouble()

// ============================================================================
// FORM SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    viewModel: PlantViewModel, plantId: String?,
    initialLat: Double?, initialLng: Double?,
    initialMapX: Double? = null, initialMapY: Double? = null,
    snackbarHostState: SnackbarHostState, scope: CoroutineScope,
    onDone: () -> Unit, onCancel: () -> Unit,
    onNavigateToPlacement: (String) -> Unit = {},
    onOpenGrowthTimeline: (String) -> Unit = {},
    onOpenCareHistory: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val photoMode = remember { getPhotoStorageMode(context) }
    val careLogViewModel: CareLogViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )

    var name by remember { mutableStateOf("") }
    var sci by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var sun by remember { mutableStateOf("") }
    var water by remember { mutableStateOf("") }
    var soil by remember { mutableStateOf("") }
    var soilPh by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var gardenId by remember { mutableStateOf("") }
    var frost by remember { mutableStateOf("") }
    var native by remember { mutableStateOf("") }
    var pollinatorChoice by remember { mutableStateOf("") }
    var pollinatorOther by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var lat by remember { mutableStateOf(initialLat?.toString() ?: "") }
    var lng by remember { mutableStateOf(initialLng?.toString() ?: "") }
    var notes by remember { mutableStateOf("") }
    var wateringSystem by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoThumbnailBase64 by remember { mutableStateOf<String?>(null) }
    // Tracks which photoUri the cached thumbnail above was generated from, so loading an existing
    // plant reuses its already-stored thumbnail instead of re-decoding the image every time the
    // form opens — only a genuine photoUri change (new capture/pick) triggers regeneration below.
    var photoThumbnailForUri by remember { mutableStateOf<String?>(null) }
    var uploadingPhotoToDropbox by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(plantId == null) }
    var aiLoading by remember { mutableStateOf(false) }
    var autoFillLoading by remember { mutableStateOf(false) }
    var showAutoFillConfirm by remember { mutableStateOf<FrequencySuggestion?>(null) }
    var conditionsAutoFillLoading by remember { mutableStateOf(false) }
    var showConditionsAutoFillConfirm by remember { mutableStateOf<ConditionsSuggestion?>(null) }
    val allPlants by viewModel.plants.collectAsState()
    var generatedId by remember { mutableStateOf("") }
    var showPhotoViewer by remember { mutableStateOf(false) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    var mapX by remember { mutableStateOf(initialMapX) }
    var mapY by remember { mutableStateOf(initialMapY) }
    var showPlacementPrompt by remember { mutableStateOf(false) }
    var placementPromptRoute by remember { mutableStateOf("") }
    var placementPromptText by remember { mutableStateOf("") }
    var showNotificationHint by remember { mutableStateOf(false) }
    var pendingHintPlant by remember { mutableStateOf<PlantEntity?>(null) }
    var lastWateredDate by remember { mutableStateOf("") }
    var originalLastWateredDate by remember { mutableStateOf("") }
    var wateringFrequency by remember { mutableStateOf("") }
    var summerWateringFrequency by remember { mutableStateOf("") }
    var winterWateringFrequency by remember { mutableStateOf("") }
    var lastFertilisedDate by remember { mutableStateOf("") }
    var originalLastFertilisedDate by remember { mutableStateOf("") }
    var fertiliseFrequency by remember { mutableStateOf("") }
    var lastPrunedDate by remember { mutableStateOf("") }
    var originalLastPrunedDate by remember { mutableStateOf("") }
    var pruneFrequency by remember { mutableStateOf("") }
    var lastFedDate by remember { mutableStateOf("") }
    var originalLastFedDate by remember { mutableStateOf("") }
    var feedFrequency by remember { mutableStateOf("") }
    var manualWateringOnly by remember { mutableStateOf(false) }
    var isIndoor by remember { mutableStateOf(false) }
    var showBulkWaterPrompt by remember { mutableStateOf(false) }
    var pendingSavedPlant by remember { mutableStateOf<PlantEntity?>(null) }

    fun buildPlant(): PlantEntity {
        val finalPollinator = if (pollinatorChoice == "Other") pollinatorOther else pollinatorChoice
        return PlantEntity(
            id = plantId ?: generatedId.ifBlank { generateNextPlantId(allPlants) },
            name = name, sci = sci, location = location,
            sun = sun, water = water, soil = soil, soilPh = soilPh, category = category, frost = frost,
            gardenId = gardenId,
            native = native, pollinator = finalPollinator, source = source, date = date,
            qty = qty.toIntOrNull() ?: 1,
            notes = notes,
            wateringSystem = wateringSystem,
            lat = lat.toDoubleOrNull(), lng = lng.toDoubleOrNull(),
            photoUri = photoUri?.toString(),
            photoThumbnailBase64 = photoThumbnailBase64,
            mapX = mapX, mapY = mapY,
            lastWateredDate = dateStringToMillis(lastWateredDate),
            wateringFrequencyDays = wateringFrequency.toIntOrNull(),
            summerWateringFrequencyDays = summerWateringFrequency.toIntOrNull(),
            winterWateringFrequencyDays = winterWateringFrequency.toIntOrNull(),
            manualWateringOnly = manualWateringOnly,
            isIndoor = isIndoor,
            lastFertilisedDate = dateStringToMillis(lastFertilisedDate),
            fertiliseFrequencyDays = fertiliseFrequency.toIntOrNull(),
            lastPrunedDate = dateStringToMillis(lastPrunedDate),
            pruneFrequencyDays = pruneFrequency.toIntOrNull(),
            lastFedDate = dateStringToMillis(lastFedDate),
            feedFrequencyDays = feedFrequency.toIntOrNull()
        )
    }

    /** Saves any pending edits (including care-log sync) before navigating away to place the plant on a map, so nothing is lost. */
    suspend fun saveThenNavigateToPlacement(route: String) {
        val plant = buildPlant()
        viewModel.saveSync(plant)
        if (lastWateredDate != originalLastWateredDate) {
            plant.lastWateredDate?.let { careLogViewModel.logCareSync(plant.id, "watering", it) }
        }
        if (lastFertilisedDate != originalLastFertilisedDate) {
            plant.lastFertilisedDate?.let { careLogViewModel.logCareSync(plant.id, "fertilise", it) }
        }
        if (lastPrunedDate != originalLastPrunedDate) {
            plant.lastPrunedDate?.let { careLogViewModel.logCareSync(plant.id, "prune", it) }
        }
        if (lastFedDate != originalLastFedDate) {
            plant.lastFedDate?.let { careLogViewModel.logCareSync(plant.id, "feed", it) }
        }
        onNavigateToPlacement(route)
    }

    fun checkPlacementPrompts(plant: PlantEntity) {
        if (plantId == null && allPlants.isEmpty() && !hasShownNotificationHint(context)) {
            setNotificationHintShown(context)
            pendingHintPlant = plant
            showNotificationHint = true
            return
        }
        val customMapExists = getCustomMapUri(context) != null
        val hasReal = plant.lat != null && plant.lng != null
        val hasCustom = plant.mapX != null && plant.mapY != null

        when {
            // Only offered when creating a brand new plant — otherwise this would re-prompt on
            // every edit of an existing plant that simply hasn't been placed on both maps yet.
            plantId != null -> {
                scope.launch { snackbarHostState.showSnackbar("Plant saved!") }
                onDone()
            }
            customMapExists && hasReal && !hasCustom -> {
                placementPromptRoute = "place_custom/${plant.id}"
                placementPromptText = "Would you like to also place this plant on your custom map?"
                showPlacementPrompt = true
            }
            hasCustom && !hasReal -> {
                placementPromptRoute = "place_real/${plant.id}"
                placementPromptText = "Would you like to also place this plant on the real-world map?"
                showPlacementPrompt = true
            }
            else -> {
                scope.launch { snackbarHostState.showSnackbar("Plant saved!") }
                onDone()
            }
        }
    }

    LaunchedEffect(plantId) {
        if (plantId != null) {
            val existing = viewModel.getById(plantId)
            if (existing != null) {
                name = existing.name
                sci = existing.sci
                location = existing.location
                sun = existing.sun
                water = existing.water
                soil = existing.soil
                soilPh = existing.soilPh
                category = existing.category
                gardenId = existing.gardenId
                frost = existing.frost
                native = existing.native
                if (pollinatorOptions.contains(existing.pollinator)) {
                    pollinatorChoice = existing.pollinator
                } else if (existing.pollinator.isNotBlank()) {
                    pollinatorChoice = "Other"
                    pollinatorOther = existing.pollinator
                }
                source = existing.source
                date = existing.date
                qty = existing.qty.toString()
                lat = existing.lat?.toString() ?: ""
                lng = existing.lng?.toString() ?: ""
                notes = existing.notes
                wateringSystem = existing.wateringSystem
                photoUri = existing.photoUri?.let { Uri.parse(it) }
                photoThumbnailBase64 = existing.photoThumbnailBase64
                photoThumbnailForUri = existing.photoUri
                mapX = existing.mapX
                mapY = existing.mapY
                lastWateredDate = millisToDateString(existing.lastWateredDate)
                originalLastWateredDate = lastWateredDate
                wateringFrequency = existing.wateringFrequencyDays?.toString() ?: ""
                summerWateringFrequency = existing.summerWateringFrequencyDays?.toString() ?: ""
                winterWateringFrequency = existing.winterWateringFrequencyDays?.toString() ?: ""
                lastFertilisedDate = millisToDateString(existing.lastFertilisedDate)
                originalLastFertilisedDate = lastFertilisedDate
                fertiliseFrequency = existing.fertiliseFrequencyDays?.toString() ?: ""
                lastPrunedDate = millisToDateString(existing.lastPrunedDate)
                originalLastPrunedDate = lastPrunedDate
                pruneFrequency = existing.pruneFrequencyDays?.toString() ?: ""
                lastFedDate = millisToDateString(existing.lastFedDate)
                originalLastFedDate = lastFedDate
                feedFrequency = existing.feedFrequencyDays?.toString() ?: ""
                manualWateringOnly = existing.manualWateringOnly
                isIndoor = existing.isIndoor
            }
            loaded = true
        }
    }

    // Regenerates the cached thumbnail only when photoUri actually changes to something other than
    // what it was last generated from (a fresh capture/pick), not on every recomposition or on the
    // initial load of an existing plant (which already restored the cached value above).
    LaunchedEffect(photoUri) {
        val uri = photoUri
        if (uri == null) {
            photoThumbnailBase64 = null
            photoThumbnailForUri = null
        } else if (uri.toString() != photoThumbnailForUri) {
            photoThumbnailBase64 = if (uri.scheme != "http" && uri.scheme != "https") {
                withContext(Dispatchers.IO) { generatePhotoThumbnailBase64(context, uri) }
            } else null
            photoThumbnailForUri = uri.toString()
        }
    }

    LaunchedEffect(Unit) {
        if (plantId == null && generatedId.isBlank()) {
            // This garden's own letter prefix (see plantIdPrefixForGarden) means a different
            // garden's plants structurally can't collide with this one's, on top of also checking
            // against every plant on the device (every garden) as a backstop — see
            // getAllPlantsOnDevice for why a garden-scoped-only check previously let two different
            // gardens' plants collide on the same auto-generated id.
            val prefix = plantIdPrefixForGarden(context, effectiveGardenId(context))
            generatedId = generateNextPlantId(viewModel.getAllPlantsOnDevice(), prefix)
        }
    }

    val displayId = plantId ?: generatedId

    var pendingCameraUri by rememberSaveable(stateSaver = UriSaver) { mutableStateOf<Uri?>(null) }
    val dropboxConnected = DropboxAuthState.token != null

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        // Camera captures are used as-is — no auto-upload. Use "Choose photo from Dropbox"
        // afterwards for a cloud-linked copy, same as device-gallery photos.
        if (success && pendingCameraUri != null) {
            photoUri = pendingCameraUri
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createImageUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            // Photos picked from the device are used as-is — no auto-upload.
            // Auto-upload is reserved for camera captures; for cloud-stored
            // photos, users have the explicit "Choose from Dropbox" button.
            photoUri = uri
        }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // A view-only member of a shared garden can still open this form to look at a plant, but
    // shouldn't be able to change anything — the server already discards their writes (see
    // hasWriteAccessToActiveGarden), but leaving the fields editable and a Save button visible
    // would make it look like their edits took effect when they're silently dropped on next sync.
    val canEdit = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp)
    ) {
        if (!canEdit) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "View-only — you don't have edit access to this garden.",
                    modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = Color(0xFF6B5300)
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        if (photoUri == null && photoMode == "cloud" && !dropboxConnected) {
            // An already-set photo (photoUri != null) is shown regardless of this device's own
            // Dropbox connection — it's a plain HTTPS shared link that renders fine without this
            // device being linked. Only picking/uploading a NEW cloud photo actually needs it,
            // and those specific buttons below already gate on dropboxConnected individually.
            Text("Connect your cloud storage in the Help tab first.", color = Color.Gray, fontSize = 13.sp)
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE3DDCF))
                    .clickable(enabled = canEdit || photoUri != null) {
                        if (photoUri != null) {
                            showPhotoViewer = true
                        } else {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri)
                            } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Gated on photoMode == "cloud" so this can never show while in local mode. Viewing
                // the existing photo full-screen is harmless read-only behaviour, so that tap stays
                // enabled above even when canEdit is false — only capturing a NEW photo is blocked.
                if (photoUri != null) PlantPhoto(photoUri = photoUri.toString(), photoThumbnailBase64 = photoThumbnailBase64, modifier = Modifier.fillMaxSize())
                else Text("📷 Tap to take a photo", color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))

            if (canEdit) {
            if (photoUri != null) {
                OutlinedButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri)
                        } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📷 Replace with a new photo") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (photoUri != null) "🖼️ Replace with a photo from your device" else "🖼️ Choose a photo from your device")
            }
            if (dropboxConnected) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDropboxPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (photoUri != null) "☁️ Replace with a photo from Dropbox" else "☁️ Choose a photo from Dropbox")
                }
            }
            val currentPhotoUri = photoUri
            if (currentPhotoUri != null && dropboxConnected && currentPhotoUri.scheme != "http" && currentPhotoUri.scheme != "https") {
                Spacer(Modifier.height(8.dp))
                // Previews the actual target filename (accounting for the "_1"/"_2" suffix a repeat
                // upload for this plant ID would get) before the user commits, rather than always
                // showing the bare ID as if every upload were the first for this plant.
                var previewName by remember(displayId) { mutableStateOf(displayId) }
                LaunchedEffect(displayId) {
                    previewDropboxUploadName(context, displayId)?.let { previewName = it.removeSuffix(".jpg") }
                }
                OutlinedButton(
                    onClick = {
                        uploadingPhotoToDropbox = true
                        scope.launch {
                            val link = uploadPhotoToDropboxAsPlantId(context, currentPhotoUri, displayId)
                            uploadingPhotoToDropbox = false
                            if (link != null) {
                                photoUri = Uri.parse(link)
                                snackbarHostState.showSnackbar("Uploaded to Dropbox")
                            } else {
                                snackbarHostState.showSnackbar("Upload to Dropbox failed — try again shortly.")
                            }
                        }
                    },
                    enabled = !uploadingPhotoToDropbox && displayId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (uploadingPhotoToDropbox) "Uploading…" else "☁️ Upload this photo to Dropbox as $previewName") }
            }
            if (photoUri != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { photoUri = null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
                ) { Text("🗑️ Remove photo from plant") }
            }
            }
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = displayId,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Plant ID") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = Color.Black,
                disabledBorderColor = Color.Gray,
                disabledLabelColor = Color.Gray
            )
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Plant name") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit)
        if (canEdit) {
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                if (photoUri == null) {
                    scope.launch { snackbarHostState.showSnackbar("Take or choose a photo first.") }
                    return@Button
                }
                aiLoading = true
                scope.launch {
                    when (val result = identifyPlantFromUri(context, photoUri!!)) {
                        is PlantIdResult.Success -> {
                            name = result.commonName
                            sci = result.scientificName
                            snackbarHostState.showSnackbar("AI suggestion applied - please double-check it!")
                        }
                        is PlantIdResult.Failed -> {
                            snackbarHostState.showSnackbar("Couldn't identify this plant. Try a clearer photo.")
                        }
                        is PlantIdResult.DailyLimitReached -> {
                            snackbarHostState.showSnackbar(
                                if (result.isProLimit) "You've reached today's AI photo ID limit (${result.limit}/day) — try again tomorrow."
                                else "You've reached today's AI photo ID limit (${result.limit}/day). Pro raises this to $PLANTNET_PRO_DAILY_LIMIT/day."
                            )
                        }
                    }
                    aiLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8FB0)),
            enabled = !aiLoading
        ) { Text(if (aiLoading) "Identifying…" else "✨ Suggest name from photo (AI)") }
        Text(
            "AI suggestions are a starting point - always double-check the result.",
            fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
        )
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = sci, onValueChange = { sci = it }, label = { Text("Scientific name") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit)
        Spacer(Modifier.height(14.dp))

        val locationOptions = remember(allPlants) {
            (getOrSeedGardenLocations(context, allPlants) + allPlants.map { it.location }.filter { it.isNotBlank() })
                .distinct().sorted()
        }
        DropdownField(
            "Garden location", locationOptions, location, { location = it },
            "Manage these from Help → Garden locations", enabled = canEdit
        )
        Spacer(Modifier.height(14.dp))

        DropdownField("Category", categoryOptions, category, { category = it }, "What kind of plant this is — also picks its icon on the map", enabled = canEdit)
        Spacer(Modifier.height(14.dp))
        DropdownField("Sun", sunOptions, sun, { sun = it }, "Optimal sunlight conditions for your plant", enabled = canEdit)
        Spacer(Modifier.height(14.dp))
        DropdownField("Water", waterOptions, water, { water = it }, "Optimal watering conditions for your plant", enabled = canEdit)
        Spacer(Modifier.height(14.dp))
        DropdownField("Soil", soilOptions, soil, { soil = it }, "Optimal soil conditions for your plant", enabled = canEdit)
        Spacer(Modifier.height(14.dp))
        if (FeatureVisibility.shouldShow(context, Feature.SOIL_PH)) {
            DropdownField("Soil pH", soilPhOptions, soilPh, { soilPh = it }, "How acidic or alkaline this plant's soil should be", enabled = canEdit)
            Spacer(Modifier.height(14.dp))
        }
        DropdownField("Frost", frostOptions, frost, { frost = it }, "Optimal frost conditions for your plant", enabled = canEdit)
        Spacer(Modifier.height(14.dp))
        DropdownField("Native / Exotic", nativeOptions, native, { native = it }, enabled = canEdit)
        Spacer(Modifier.height(14.dp))

        DropdownField("Pollinator-friendly?", pollinatorOptions, pollinatorChoice, { pollinatorChoice = it }, enabled = canEdit)
        if (pollinatorChoice == "Other") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pollinatorOther, onValueChange = { pollinatorOther = it },
                label = { Text("Describe pollinator-friendliness") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
            )
        }
        Spacer(Modifier.height(10.dp))

        if (canEdit && FeatureVisibility.shouldShow(context, Feature.SAGE_ASSISTANT)) {
            Button(
                onClick = {
                    conditionsAutoFillLoading = true
                    scope.launch {
                        when (val result = SageClient.autoFillConditions(context, sci)) {
                            is SageAutoFillConditionsResult.Success -> {
                                EntitlementManager.updateSagePromptsRemaining(context, result.promptsRemaining)
                                val hasExisting = listOf(sun, water, soil, soilPh, frost, native, pollinatorChoice).any { it.isNotBlank() }
                                if (hasExisting) {
                                    showConditionsAutoFillConfirm = result.suggestion
                                } else {
                                    result.suggestion.sun?.let { sun = it }
                                    result.suggestion.water?.let { water = it }
                                    result.suggestion.soil?.let { soil = it }
                                    result.suggestion.soilPh?.let { soilPh = it }
                                    result.suggestion.frost?.let { frost = it }
                                    result.suggestion.native?.let { native = it }
                                    result.suggestion.pollinator?.let { pollinatorChoice = it }
                                }
                            }
                            is SageAutoFillConditionsResult.FreeLimitReached -> {
                                EntitlementManager.updateSagePromptsRemaining(context, 0)
                                snackbarHostState.showSnackbar("You've used all your free Sage questions — enter a promo code under Help → Basic/Advanced mode for unlimited access.")
                            }
                            is SageAutoFillConditionsResult.DailyLimitReached ->
                                snackbarHostState.showSnackbar("Sage is busy right now — try again later.")
                            else ->
                                snackbarHostState.showSnackbar("Couldn't get suggestions right now.")
                        }
                        conditionsAutoFillLoading = false
                    }
                },
                enabled = sci.isNotBlank() && !conditionsAutoFillLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8FB0))
            ) { Text(if (conditionsAutoFillLoading) "Asking Sage…" else "🌿 Suggest optimal conditions with Sage") }
            if (sci.isBlank()) {
                Text("Enter a scientific name above to use this.", fontSize = 11.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Source (e.g. nursery)") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit)
        Spacer(Modifier.height(14.dp))

        DatePickerField(label = "Date planted", dateString = date, onDateChange = { date = it }, allowNotApplicable = true, enabled = canEdit)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = qty, onValueChange = { qty = it }, label = { Text("Quantity") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = wateringSystem, onValueChange = { wateringSystem = it }, label = { Text("Watering System") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit)
        Spacer(Modifier.height(14.dp))

        DatePickerField("Last watered", lastWateredDate, { lastWateredDate = it }, restrictToPastOrToday = true, allowClear = false, enabled = canEdit)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = wateringFrequency, onValueChange = { new ->
                wateringFrequency = new.filter { it.isDigit() }
                if (wateringFrequency.isNotBlank() && lastWateredDate.isBlank()) {
                    lastWateredDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                }
            },
            label = { Text("Watering frequency (days)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    "This is a guide only — your plant's needs may vary with conditions. Before watering, check the soil by inserting your finger about 2–3 cm deep. If the soil feels dry, it's time to water; if it's still moist, wait and check again later.",
                    fontSize = 11.sp
                )
            },
            modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
        )
        Spacer(Modifier.height(10.dp))

        if (FeatureVisibility.shouldShow(context, Feature.SEASONAL_WATERING)) {
        ExpandableSection(title = "Seasonal watering (optional)") {
            Text("Overrides the frequency above during summer/winter. Leave a season blank to use the default year-round frequency instead.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(
                "Entering 0 does NOT mean \"skip watering this season\" — it means water every 0 days, so the plant will constantly show as overdue. To skip a season entirely, leave its field blank.",
                fontSize = 12.sp, color = Color(0xFFB23B3B)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = summerWateringFrequency,
                onValueChange = { summerWateringFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Summer frequency (days) — blank = no summer override") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = winterWateringFrequency,
                onValueChange = { winterWateringFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Winter frequency (days) — blank = no winter override") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
            )
        }
        Spacer(Modifier.height(4.dp))
        }

        if (FeatureVisibility.shouldShow(context, Feature.FERTILISE_PRUNE)) {
        ExpandableSection(title = "Fertilising & pruning (optional)") {
            DatePickerField("Last fertilised", lastFertilisedDate, { lastFertilisedDate = it }, restrictToPastOrToday = true, enabled = canEdit)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = fertiliseFrequency, onValueChange = { fertiliseFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Fertilise frequency (days)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
            )
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            DatePickerField("Last pruned", lastPrunedDate, { lastPrunedDate = it }, restrictToPastOrToday = true, enabled = canEdit)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pruneFrequency, onValueChange = { pruneFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Prune frequency (days)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
            )
        }
        Spacer(Modifier.height(4.dp))
        }

        if (FeatureVisibility.shouldShow(context, Feature.FEEDING)) {
        ExpandableSection(title = "Feeding (optional)") {
            DatePickerField("Last fed", lastFedDate, { lastFedDate = it }, restrictToPastOrToday = true, enabled = canEdit)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = feedFrequency, onValueChange = { feedFrequency = it.filter { c -> c.isDigit() } },
                label = { Text("Feeding frequency (days)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
            )
        }
        Spacer(Modifier.height(10.dp))
        }

        if (canEdit && FeatureVisibility.shouldShow(context, Feature.SAGE_CARE_FREQUENCIES) && FeatureVisibility.shouldShow(context, Feature.SAGE_ASSISTANT)) {
            Button(
                onClick = {
                    autoFillLoading = true
                    scope.launch {
                        when (val result = SageClient.autoFillFrequencies(context, sci)) {
                            is SageAutoFillResult.Success -> {
                                EntitlementManager.updateSagePromptsRemaining(context, result.promptsRemaining)
                                val hasExisting = listOf(wateringFrequency, fertiliseFrequency, pruneFrequency, feedFrequency).any { it.isNotBlank() }
                                if (hasExisting) {
                                    showAutoFillConfirm = result.suggestion
                                } else {
                                    result.suggestion.wateringFrequencyDays?.let {
                                        wateringFrequency = it.toString()
                                        if (lastWateredDate.isBlank()) lastWateredDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    }
                                    result.suggestion.fertiliseFrequencyDays?.let { fertiliseFrequency = it.toString() }
                                    result.suggestion.pruneFrequencyDays?.let { pruneFrequency = it.toString() }
                                    result.suggestion.feedFrequencyDays?.let { feedFrequency = it.toString() }
                                }
                            }
                            is SageAutoFillResult.FreeLimitReached -> {
                                EntitlementManager.updateSagePromptsRemaining(context, 0)
                                snackbarHostState.showSnackbar("You've used all your free Sage questions — enter a promo code under Help → Basic/Advanced mode for unlimited access.")
                            }
                            is SageAutoFillResult.DailyLimitReached ->
                                snackbarHostState.showSnackbar("Sage is busy right now — try again later.")
                            else ->
                                snackbarHostState.showSnackbar("Couldn't get suggestions right now.")
                        }
                        autoFillLoading = false
                    }
                },
                enabled = sci.isNotBlank() && !autoFillLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8FB0))
            ) { Text(if (autoFillLoading) "Asking Sage…" else "🌿 Suggest care frequencies with Sage") }
            if (sci.isBlank()) {
                Text("Enter a scientific name above to use this.", fontSize = 11.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(14.dp))
        }

        if (plantId != null && FeatureVisibility.shouldShow(context, Feature.PLANT_HISTORY)) {
            OutlinedButton(onClick = { onOpenCareHistory(plantId) }, modifier = Modifier.fillMaxWidth()) {
                Text("📋 View watering, fertilising, feeding & pruning history")
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Requires manual watering (not part of a watering system)",
                fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            Switch(checked = manualWateringOnly, onCheckedChange = { manualWateringOnly = it }, enabled = canEdit)
        }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Indoor plant (exempt from rain-based reminder skipping)",
                fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            Switch(checked = isIndoor, onCheckedChange = { isIndoor = it }, enabled = canEdit)
        }
        Spacer(Modifier.height(14.dp))

        if (FeatureVisibility.shouldShow(context, Feature.COORDINATES)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), readOnly = !canEdit)
            OutlinedTextField(value = lng, onValueChange = { lng = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), readOnly = !canEdit)
        }
        Text(
            "Coordinates based on map location - update the location using the red pin in list view, or by manually updating the coordinates below",
            fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
        )
        }
        if (plantId != null && canEdit && FeatureVisibility.shouldShow(context, Feature.PLACE_ON_MAP)) {
            val hasReal = lat.toDoubleOrNull() != null && lng.toDoubleOrNull() != null
            val hasCustom = mapX != null && mapY != null
            val customMapExists = remember { getCustomMapUri(context) != null }
            if (customMapExists) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scope.launch { saveThenNavigateToPlacement("place_custom/$plantId") } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (hasCustom) "📍 Change location on custom map" else "📍 Place on custom map") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { scope.launch { saveThenNavigateToPlacement("place_real/$plantId") } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (hasReal) "📍 Change location on real-world map" else "📍 Place on real-world map") }
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit)
        Spacer(Modifier.height(20.dp))

        if (canEdit) {
        Button(
            onClick = {
                if (name.isBlank()) { scope.launch { snackbarHostState.showSnackbar("Please give the plant a name.") }; return@Button }

                val plant = buildPlant()
                pendingSavedPlant = plant
                scope.launch {
                    // Sequenced (not fired in parallel): each log call re-reads the plant to apply its one field,
                    // so overlapping writes here would race and could silently drop an earlier change.
                    viewModel.saveSync(plant)
                    if (lastWateredDate != originalLastWateredDate) {
                        plant.lastWateredDate?.let { careLogViewModel.logCareSync(plant.id, "watering", it) }
                    }
                    if (lastFertilisedDate != originalLastFertilisedDate) {
                        plant.lastFertilisedDate?.let { careLogViewModel.logCareSync(plant.id, "fertilise", it) }
                    }
                    if (lastPrunedDate != originalLastPrunedDate) {
                        plant.lastPrunedDate?.let { careLogViewModel.logCareSync(plant.id, "prune", it) }
                    }
                    if (lastFedDate != originalLastFedDate) {
                        plant.lastFedDate?.let { careLogViewModel.logCareSync(plant.id, "feed", it) }
                    }

                    if (plant.lastWateredDate != null && location.isNotBlank() && lastWateredDate != originalLastWateredDate) {
                        showBulkWaterPrompt = true
                    } else {
                        checkPlacementPrompts(plant)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A40))
        ) { Text("Save plant") }
        }

        if (plantId != null && FeatureVisibility.shouldShow(context, Feature.GROWTH_TIMELINES)) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onOpenGrowthTimeline(plantId) }, modifier = Modifier.fillMaxWidth()) {
                Text("🌱 View growth timeline")
            }
        }
        if (displayId.isNotBlank() && FeatureVisibility.shouldShow(context, Feature.EXTRA_PHOTOS)) {
            Spacer(Modifier.height(8.dp))
            ExtraPhotosSection(plantId = displayId, canEdit = canEdit)
        }
        if (plantId != null && canEdit) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDeleteDialog = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
            ) { Text("Delete this plant") }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                // A brand-new (never-saved) plant may have picked up extra photos already —
                // they were staged against a display ID that's about to become meaningless.
                if (plantId == null && displayId.isNotBlank()) {
                    val extraPhotoDao = AppDatabase.getInstance(context).extraPhotoDao()
                    scope.launch { extraPhotoDao.deleteForPlant(displayId) }
                }
                onCancel()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cancel") }
        Spacer(Modifier.height(30.dp))
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this plant?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    plantId?.let { viewModel.delete(it) }
                    scope.launch { snackbarHostState.showSnackbar("Plant deleted") }
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    showAutoFillConfirm?.let { suggestion ->
        val overwritten = buildList {
            if (wateringFrequency.isNotBlank() && suggestion.wateringFrequencyDays != null) add("Watering frequency")
            if (fertiliseFrequency.isNotBlank() && suggestion.fertiliseFrequencyDays != null) add("Fertilise frequency")
            if (pruneFrequency.isNotBlank() && suggestion.pruneFrequencyDays != null) add("Prune frequency")
            if (feedFrequency.isNotBlank() && suggestion.feedFrequencyDays != null) add("Feeding frequency")
        }
        AlertDialog(
            onDismissRequest = { showAutoFillConfirm = null },
            title = { Text("Overwrite existing values?") },
            text = {
                Column {
                    Text("Sage's suggestions will replace the values you've already entered for:")
                    Spacer(Modifier.height(6.dp))
                    overwritten.forEach { Text("• $it", fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    suggestion.wateringFrequencyDays?.let {
                        wateringFrequency = it.toString()
                        if (lastWateredDate.isBlank()) lastWateredDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    }
                    suggestion.fertiliseFrequencyDays?.let { fertiliseFrequency = it.toString() }
                    suggestion.pruneFrequencyDays?.let { pruneFrequency = it.toString() }
                    suggestion.feedFrequencyDays?.let { feedFrequency = it.toString() }
                    showAutoFillConfirm = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showAutoFillConfirm = null }) { Text("Cancel") } }
        )
    }

    showConditionsAutoFillConfirm?.let { suggestion ->
        val overwritten = buildList {
            if (sun.isNotBlank() && suggestion.sun != null) add("Sun")
            if (water.isNotBlank() && suggestion.water != null) add("Water")
            if (soil.isNotBlank() && suggestion.soil != null) add("Soil")
            if (soilPh.isNotBlank() && suggestion.soilPh != null) add("Soil pH")
            if (frost.isNotBlank() && suggestion.frost != null) add("Frost")
            if (native.isNotBlank() && suggestion.native != null) add("Native/Exotic")
            if (pollinatorChoice.isNotBlank() && suggestion.pollinator != null) add("Pollinator-friendly")
        }
        AlertDialog(
            onDismissRequest = { showConditionsAutoFillConfirm = null },
            title = { Text("Overwrite existing values?") },
            text = {
                Column {
                    Text("Sage's suggestions will replace the values you've already entered for:")
                    Spacer(Modifier.height(6.dp))
                    overwritten.forEach { Text("• $it", fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    suggestion.sun?.let { sun = it }
                    suggestion.water?.let { water = it }
                    suggestion.soil?.let { soil = it }
                    suggestion.soilPh?.let { soilPh = it }
                    suggestion.frost?.let { frost = it }
                    suggestion.native?.let { native = it }
                    suggestion.pollinator?.let { pollinatorChoice = it }
                    showConditionsAutoFillConfirm = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showConditionsAutoFillConfirm = null }) { Text("Cancel") } }
        )
    }

    if (showNotificationHint) {
        AlertDialog(
            onDismissRequest = {
                showNotificationHint = false
                pendingHintPlant?.let { checkPlacementPrompts(it) }
                pendingHintPlant = null
            },
            title = { Text("Set up care reminders?") },
            text = { Text("Sage Garden can remind you when your plants need watering, fertilising, feeding, or pruning. You can turn reminders on any time from Help → Plant notifications.") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationHint = false
                    pendingHintPlant?.let { checkPlacementPrompts(it) }
                    pendingHintPlant = null
                }) { Text("Got it") }
            }
        )
    }
    if (showPlacementPrompt) {
        AlertDialog(
            onDismissRequest = { showPlacementPrompt = false; onDone() },
            title = { Text("Place on other map too?") },
            text = { Text(placementPromptText) },
            confirmButton = {
                TextButton(onClick = {
                    showPlacementPrompt = false
                    onNavigateToPlacement(placementPromptRoute)
                }) { Text("Yes, place it") }
            },
            dismissButton = {
                TextButton(onClick = { showPlacementPrompt = false; onDone() }) { Text("Not now") }
            }
        )
    }
    if (showBulkWaterPrompt) {
        val plant = pendingSavedPlant
        AlertDialog(
            onDismissRequest = {
                showBulkWaterPrompt = false
                plant?.let { checkPlacementPrompts(it) }
            },
            title = { Text("Apply to whole location?") },
            text = { Text("Set \"last watered\" to $lastWateredDate for every plant in \"$location\"?") },
            confirmButton = {
                TextButton(onClick = {
                    showBulkWaterPrompt = false
                    val dateMillis = plant?.lastWateredDate
                    if (dateMillis != null) {
                        scope.launch {
                            allPlants.filter { it.location == location }.forEach { p ->
                                if (p.id != plant.id) {
                                    viewModel.saveSync(p.copy(lastWateredDate = dateMillis))
                                    careLogViewModel.logCareSync(p.id, "watering", dateMillis)
                                }
                            }
                            checkPlacementPrompts(plant)
                        }
                    } else {
                        plant?.let { checkPlacementPrompts(it) }
                    }
                }) { Text("Yes, apply to all") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBulkWaterPrompt = false
                    plant?.let { checkPlacementPrompts(it) }
                }) { Text("Just this plant") }
            }
        )
    }
    if (showPhotoViewer && photoUri != null) {
        var photoLoadFailed by remember(photoUri) { mutableStateOf(false) }
        Dialog(onDismissRequest = { showPhotoViewer = false }) {
            Box(modifier = Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (photoLoadFailed) {
                    val fallbackBitmap = remember(photoThumbnailBase64) { photoThumbnailBase64?.let(::decodePhotoThumbnail)?.asImageBitmap() }
                    if (fallbackBitmap != null) {
                        Image(bitmap = fallbackBitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        Text(
                            "Couldn't load this photo — the link may no longer be valid (e.g. its Dropbox sharing permissions changed).",
                            color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = photoUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        onError = { photoLoadFailed = true }
                    )
                }
            }
        }
    }
    if (showDropboxPicker) {
        DropboxImagePickerDialog(context, onDismiss = { showDropboxPicker = false }, onImageSelected = { link, _ -> photoUri = Uri.parse(link) })
    }
}

// ============================================================================
// EXTRA PHOTOS (per plant — watering system, care leaflets, etc.)
// ============================================================================

@Composable
fun ExtraPhotosSection(plantId: String, canEdit: Boolean = true) {
    val context = LocalContext.current
    val extraPhotoViewModel: ExtraPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val gardenId = remember { effectiveGardenId(context) }
    val photos by remember(plantId) { extraPhotoViewModel.getForPlant(plantId, gardenId) }.collectAsState()
    var pendingCameraUri by rememberSaveable(stateSaver = UriSaver) { mutableStateOf<Uri?>(null) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var uploadingPhotoId by remember { mutableStateOf<String?>(null) }
    var uploadFailedId by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) extraPhotoViewModel.addPhoto(plantId, pendingCameraUri.toString())
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            extraPhotoViewModel.addPhoto(plantId, uri.toString())
        }
    }

    ExpandableSection(title = "Extra photos (${photos.size})") {
        Text(
            "Anything else worth keeping a photo of for this plant — its watering system, a care-instruction leaflet, etc.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(10.dp))
        if (canEdit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.weight(1f)
            ) { Text("📷 Camera", fontSize = 12.sp) }
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("🖼️ Gallery", fontSize = 12.sp) }
            if (DropboxAuthState.token != null) {
                OutlinedButton(onClick = { showDropboxPicker = true }, modifier = Modifier.weight(1f)) { Text("☁️ Dropbox", fontSize = 12.sp) }
            }
        }
        }

        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            photos.forEach { photo ->
                var label by remember(photo.id) { mutableStateOf(photo.label) }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = Uri.parse(photo.uri), contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                value = label,
                                onValueChange = { label = it; extraPhotoViewModel.updateLabel(photo, it) },
                                label = { Text("Label", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                readOnly = !canEdit
                            )
                            if (canEdit) {
                                TextButton(onClick = { extraPhotoViewModel.delete(photo.id) }) { Text("Delete", fontSize = 11.sp) }
                            }
                        }
                        val localUriScheme = Uri.parse(photo.uri).scheme
                        if (canEdit && DropboxAuthState.token != null && localUriScheme != "http" && localUriScheme != "https") {
                            TextButton(
                                onClick = {
                                    uploadingPhotoId = photo.id; uploadFailedId = null
                                    scope.launch {
                                        val link = uploadPhotoToDropboxAsPlantId(context, Uri.parse(photo.uri), plantId)
                                        uploadingPhotoId = null
                                        if (link != null) extraPhotoViewModel.updateUri(photo, link) else uploadFailedId = photo.id
                                    }
                                },
                                enabled = uploadingPhotoId != photo.id
                            ) { Text(if (uploadingPhotoId == photo.id) "Uploading…" else "☁️ Upload to Dropbox", fontSize = 11.sp) }
                            if (uploadFailedId == photo.id) {
                                Text("Upload failed — try again", fontSize = 11.sp, color = Color(0xFFB23B3B))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDropboxPicker) {
        DropboxImagePickerDialog(context, onDismiss = { showDropboxPicker = false },
            onImageSelected = { link, _ -> extraPhotoViewModel.addPhoto(plantId, link) })
    }
}

fun createImageUri(context: Context): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "garden_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
}

/**
 * A camera-capture "pending URI" held in plain `remember` is lost if the OS kills the app's process
 * while the system Camera app is in the foreground (common under memory pressure, especially on
 * lower-RAM devices) — the camera result still comes back fine (ActivityResultRegistry is designed
 * to survive process death), but with pendingCameraUri reset to null the success callback silently
 * no-ops instead of adding the photo, and the visible Activity recreation looks like the app
 * "restarted". Use this Saver with rememberSaveable everywhere a pending camera URI is held so it
 * survives process death and the photo actually gets added once the callback fires.
 */
val UriSaver = androidx.compose.runtime.saveable.Saver<Uri?, String>(
    save = { it?.toString() ?: "" },
    restore = { if (it.isBlank()) null else Uri.parse(it) }
)

// ============================================================================
// FAQ SCREEN (accordion - separate page, linked from Help)
// ============================================================================

data class FaqItem(val question: String, val answer: String)

val faqItems = listOf(
    FaqItem(
        "What does this app do?",
        "Sage Garden helps you track every plant in your garden — photos, care history, and location on a real-world or hand-drawn map — with smart reminders for watering, feeding, fertilising and pruning, weather-aware skipping, a sun exposure map, companion planting/spacing checks, watering cost & usage tracking, and Sage, a built-in AI assistant for gardening questions. It's entirely free — see \"Are there any limits?\" below for the two small exceptions."
    ),
    FaqItem(
        "Where is my plant data stored?",
        "All plant details (name, care info, location, etc.) are stored locally in a database on this device only. Nothing is sent to a server unless you choose cloud photo storage below."
    ),
    FaqItem(
        "Where are my photos stored, and what's recommended?",
        "By default, photos you take or upload are stored locally on this device and only referenced from within the app. This means they won't automatically back up or " +
                "sync to another device, and could be lost if this device is lost, reset, or the app is uninstalled. For safer, more portable storage, it's recommended to " +
                "connect Dropbox in the Photo storage section below - photos you take will then be saved there automatically. " +
                "I recommend compressing your photos to <1MB, so you can save more photos on the cloud."
    ),
    FaqItem(
        "How do I find a plant I've already added?",
        "Use the List tab — plants are grouped alphabetically by garden location, and the search bar filters by plant name, scientific name, or location."
    ),
    FaqItem(
        "How do I back up or move my data to another device?",
        "There are two options in Help → Data, and they cover different things. \"Export CSV\" downloads your plant and irrigation data (not photos) as a spreadsheet — good for a quick data-only copy, or bulk-editing in a spreadsheet app; bring it back in with \"Import CSV\" on another install. \"Backup & restore all data\" is the fuller option — it backs up everything (plants, irrigation, sun zones, Tuya mappings, your custom map, growth/care history, and all app settings) to a Dropbox folder you choose, and restores it on another device in one go. Locally-stored photos aren't included in either — connect Dropbox photo storage first if you want photos to carry across too."
    ),
    FaqItem(
        "What format should my CSV files be in?",
        "For plant imports, the file needs a header row with at least a \"Plant\" column (name); optional columns are Plant ID, Scientific name, Location, Date planted, Source, Sun, Soil, Water, Frost, Native/Exotic, Pollinator-Friendly, Notes, Latitude, Longitude, and Watering System — export a CSV first to see the exact layout. \" For irrigation log imports, the header row needs Zone, StartTime, and DurationMinutes; Outlet and Source are optional. StartTime accepts either epoch milliseconds or a DateTime like \\\"2024-01-31 06:30:00\\\".\" Column order and capitalisation don't matter, but names need to match — if something's missing or the file is empty, you'll get a pop-up explaining exactly what's wrong."
    ),
    FaqItem(
        "Are there any limits?",
        "Sage Garden is free — every feature is unlocked for everyone: unlimited plants and log history, watering reminders, the photo log, plant care widget, Dropbox backup, weather-aware reminders, Tuya/Rachio smart-irrigation integration, the sun map, companion planting/spacing audit, cost & water usage tracking, and growth photo timelines. The only limits are on the Sage AI assistant (${EntitlementManager.FREE_SAGE_PROMPT_LIMIT} free questions total) and AI plant-photo identification ($PLANTNET_TRIAL_DAILY_LIMIT identifications a day) — both of which call paid AI services behind the scenes."
    )
)

@Composable
fun FaqScreen(onBack: () -> Unit) {
    var expandedIndex by remember { mutableStateOf(-1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text("Frequently Asked Questions", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(12.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            faqItems.forEachIndexed { index, faq ->
                val expanded = expandedIndex == index
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { expandedIndex = if (expanded) -1 else index }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(faq.question, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(if (expanded) "▾" else "▸", color = Color.Gray)
                        }
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            Text(faq.answer, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ============================================================================
// AUTO-SIZING TEXT (shrinks to fit one line — for tight spots like nav bar labels
// that would otherwise wrap/clip on narrow screens or when the user increases their
// system font size)
// ============================================================================

@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    initialFontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    minFontSize: androidx.compose.ui.unit.TextUnit = 7.sp
) {
    var fontSize by remember(text) { mutableStateOf(initialFontSize) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > minFontSize) {
                fontSize = (fontSize.value * 0.9f).sp
            }
        }
    )
}

// ============================================================================
// EXPANDABLE SECTION REUSABLE COMPONENT
// ============================================================================

@Composable
fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = Color.Gray, fontSize = 16.sp)
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

// ============================================================================
// HELP SCREEN (photo storage setting + export/import/reset + FAQ link)
// ============================================================================

@Composable
fun GardenAddressSection(context: Context, scope: CoroutineScope, snackbarHostState: SnackbarHostState, initiallyExpanded: Boolean = false) {
    // Owner-only, not merely write-access — the server only ever accepts an address/coordinate
    // update from the garden's owner (see syncGarden.ts), since this describes the physical garden
    // itself, unlike plants/care-log which any write-permission member may edit. A non-owner editor
    // seeing this as editable would have their change silently dropped on the next sync.
    val canEdit = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) }
    ExpandableSection(title = "Garden address", initiallyExpanded = initiallyExpanded) {
        Text(
            "Centres the Map tab on your garden and enables weather-aware watering reminders below.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(10.dp))
        if (!canEdit) {
            Text("View-only — synced from the garden owner.", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
        }

        var gardenAddressQuery by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenAddressState.address ?: "") }
        var gardenAddressEditedByUser by remember { mutableStateOf(false) }
        var gardenCoords by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenAddressState.latLng) }
        var gardenPredictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
        var gardenGeocoderPredictions by remember { mutableStateOf<List<android.location.Address>>(emptyList()) }
        val gardenPlacesClient = remember { Places.createClient(context) }
        var gardenSessionToken by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }

        // Picks up a sync landing AFTER this section already composed with a stale/blank snapshot —
        // the plain `remember(ActiveGardenState.activeGardenId)` above only re-reads on a garden
        // switch, not when GardenAddressState is updated asynchronously by a sync response arriving
        // later (see GardenAddressState's doc comment). Skipped while the user is actively typing so
        // a background sync can't clobber their in-progress edit.
        LaunchedEffect(GardenAddressState.address, GardenAddressState.latLng) {
            if (!gardenAddressEditedByUser) {
                gardenAddressQuery = GardenAddressState.address ?: ""
                gardenCoords = GardenAddressState.latLng
            }
        }

        if (gardenCoords != null) {
            Text("Garden location set ✅", fontSize = 12.sp, color = Color(0xFF3A5A40))
            Spacer(Modifier.height(6.dp))
        }

        LaunchedEffect(gardenAddressQuery) {
            if (gardenAddressEditedByUser && gardenAddressQuery.length > 2) {
                delay(300)
                // The Places Task callbacks below aren't tied to this coroutine's cancellation, so if the
                // user selects a suggestion (or types something else) before this in-flight request
                // resolves, a late callback must not resurrect the dropdown — guard on both flags below.
                val queryAtRequestTime = gardenAddressQuery
                fun stillRelevant() = gardenAddressEditedByUser && gardenAddressQuery == queryAtRequestTime
                val request = FindAutocompletePredictionsRequest.builder().setQuery(gardenAddressQuery).setSessionToken(gardenSessionToken).build()
                gardenPlacesClient.findAutocompletePredictions(request)
                    .addOnSuccessListener { response: FindAutocompletePredictionsResponse ->
                        if (stillRelevant()) {
                            gardenPredictions = response.autocompletePredictions
                            gardenGeocoderPredictions = emptyList()
                        }
                    }
                    .addOnFailureListener {
                        if (stillRelevant()) {
                            gardenPredictions = emptyList()
                            scope.launch {
                                val results = withContext(Dispatchers.IO) {
                                    try {
                                        @Suppress("DEPRECATION")
                                        Geocoder(context, Locale.getDefault()).getFromLocationName(gardenAddressQuery, 5)
                                    } catch (_: Exception) { null }
                                }
                                if (stillRelevant()) {
                                    gardenGeocoderPredictions = results ?: emptyList()
                                }
                            }
                        }
                    }
            } else {
                gardenPredictions = emptyList()
                gardenGeocoderPredictions = emptyList()
            }
        }

        OutlinedTextField(
            value = gardenAddressQuery,
            onValueChange = { gardenAddressQuery = it; gardenAddressEditedByUser = true },
            label = { Text("Garden address") }, placeholder = { Text("Start typing to search…") },
            modifier = Modifier.fillMaxWidth(), readOnly = !canEdit
        )

        if (gardenPredictions.isNotEmpty() || gardenGeocoderPredictions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(10.dp), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    gardenPredictions.forEach { prediction ->
                        Text(
                            text = prediction.getFullText(null).toString(),
                            modifier = Modifier.fillMaxWidth().clickable {
                                val request = FetchPlaceRequest.builder(prediction.placeId, listOf(Place.Field.LOCATION)).setSessionToken(gardenSessionToken).build()
                                gardenPlacesClient.fetchPlace(request).addOnSuccessListener { response: FetchPlaceResponse ->
                                    val latLng = response.place.location
                                    if (latLng != null) {
                                        gardenCoords = latLng.latitude to latLng.longitude
                                        setGardenLatLng(context, latLng.latitude, latLng.longitude)
                                        scope.launch { snackbarHostState.showSnackbar("Garden location saved") }
                                    }
                                }
                                gardenSessionToken = AutocompleteSessionToken.newInstance() // this session is spent — start a fresh one for the next search
                                gardenAddressQuery = prediction.getFullText(null).toString()
                                setGardenAddress(context, gardenAddressQuery)
                                gardenAddressEditedByUser = false
                                gardenPredictions = emptyList()
                            }.padding(12.dp),
                            fontSize = 13.sp
                        )
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                    }
                    gardenGeocoderPredictions.forEach { address ->
                        Text(
                            text = address.getAddressLine(0) ?: "Unknown address",
                            modifier = Modifier.fillMaxWidth().clickable {
                                gardenCoords = address.latitude to address.longitude
                                setGardenLatLng(context, address.latitude, address.longitude)
                                gardenAddressQuery = address.getAddressLine(0) ?: ""
                                setGardenAddress(context, gardenAddressQuery)
                                gardenAddressEditedByUser = false
                                gardenGeocoderPredictions = emptyList()
                                scope.launch { snackbarHostState.showSnackbar("Garden location saved") }
                            }.padding(12.dp),
                            fontSize = 13.sp
                        )
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
fun GardenZonesSection(context: Context, plants: List<PlantEntity>) {
    // Owner-only — see GardenAddressSection's comment; the server only accepts a zones update from
    // the garden's owner (syncGarden.ts), so a non-owner editor must see this read-only too.
    val canEdit = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) }
    ExpandableSection(title = "Garden zones") {
        Text(
            "Manage the named areas of your garden (e.g. \"Front garden\", \"Back garden\") — these appear as a dropdown when adding or editing a plant, instead of typing the same names over and over.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(10.dp))
        if (!canEdit) {
            Text("View-only — synced from the garden owner.", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
        }
        var gardenLocations by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getOrSeedGardenLocations(context, plants)) }
        var newLocationText by remember { mutableStateOf("") }
        var renamingIndex by remember { mutableStateOf(-1) }
        var renameText by remember { mutableStateOf("") }

        // Same reactive-staleness fix as GardenAddressSection above — picks up a sync landing after
        // this section already composed with a stale/seeded snapshot. Harmless for the owner too:
        // their own add/rename/remove already calls setGardenLocations synchronously, so this just
        // echoes back the value they set.
        LaunchedEffect(GardenAddressState.locations) {
            GardenAddressState.locations?.let { gardenLocations = it }
        }

        gardenLocations.forEachIndexed { index, loc ->
            if (renamingIndex == index) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, modifier = Modifier.weight(1f), singleLine = true)
                    TextButton(onClick = {
                        if (renameText.isNotBlank()) {
                            gardenLocations = gardenLocations.toMutableList().also { it[index] = renameText.trim() }
                            setGardenLocations(context, gardenLocations)
                        }
                        renamingIndex = -1
                    }) { Text("Save") }
                    TextButton(onClick = { renamingIndex = -1 }) { Text("Cancel") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(loc, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (canEdit) {
                        TextButton(onClick = { renamingIndex = index; renameText = loc }) { Text("Rename", fontSize = 11.sp) }
                        TextButton(onClick = {
                            gardenLocations = gardenLocations.filterIndexed { i, _ -> i != index }
                            setGardenLocations(context, gardenLocations)
                        }) { Text("Remove", fontSize = 11.sp, color = Color(0xFFB23B3B)) }
                    }
                }
            }
        }
        if (gardenLocations.isEmpty()) {
            Text("No zones yet — add one below.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
        }
        if (canEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newLocationText, onValueChange = { newLocationText = it },
                label = { Text("Add a zone") }, modifier = Modifier.weight(1f).imePadding(), singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val trimmed = newLocationText.trim()
                if (trimmed.isNotBlank() && trimmed !in gardenLocations) {
                    gardenLocations = gardenLocations + trimmed
                    setGardenLocations(context, gardenLocations)
                }
                newLocationText = ""
            }) { Text("Add") }
        }
        }
    }
}

/**
 * Garden picker + create/share/join/approve controls, embedded in Help's "Sync with other
 * devices" section (see GardenMembershipClient.kt for the backend calls this drives). Switching
 * the picker updates ActiveGardenState immediately, which every screen's ViewModel already reacts
 * to (see PlantViewModel.plants) — no navigation or restart needed.
 */
/** Small circular initial-letter avatar, used wherever the sharing UI names a person/device — gives requests and member rows a face to anchor on instead of a wall of plain text. */
@Composable
fun AvatarBubble(name: String) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF3A5A40)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().take(1).uppercase().ifBlank { "?" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small rounded label used throughout the sharing UI for a role/permission at a glance (e.g. "Owner", "View-only") instead of parenthetical text. */
@Composable
fun PermissionChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GardenSharingControls(context: Context, scope: CoroutineScope, snackbarHostState: SnackbarHostState) {
    val installId = remember { getOrCreateInstallId(context) }
    var knownGardens by remember { mutableStateOf(GardenMembershipStore.getKnownGardens(context)) }
    var pendingMyRequests by remember { mutableStateOf(GardenMembershipStore.getPendingRequests(context)) }
    var refreshing by remember { mutableStateOf(false) }

    // "My Garden" (this device's own default, gardenId == installId) always shows as an option,
    // even before the first sync/refresh has ever populated the known-gardens cache.
    val gardens = remember(knownGardens) {
        if (knownGardens.any { it.gardenId == installId }) knownGardens
        else listOf(KnownGarden(installId, "My Garden", "owner", "write", "")) + knownGardens
    }
    var activeGardenId by remember { mutableStateOf(ActiveGardenState.activeGardenId ?: installId) }
    val activeGarden = gardens.firstOrNull { it.gardenId == activeGardenId }
    val isOwner = activeGarden?.role == "owner"

    var pendingForActiveGarden by remember { mutableStateOf<List<PendingGardenRequest>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var shareCode by remember { mutableStateOf<String?>(null) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showManageAccessDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteGardenConfirm by remember { mutableStateOf(false) }
    var deletingGarden by remember { mutableStateOf(false) }

    fun refresh() {
        refreshing = true
        scope.launch {
            GardenMembershipClient.refreshKnownGardens(context)
            knownGardens = GardenMembershipStore.getKnownGardens(context)
            pendingMyRequests = GardenMembershipStore.getPendingRequests(context)
            refreshing = false
        }
    }
    // Polls every 15s for as long as this section stays expanded/visible (ExpandableSection only
    // composes its content while open), rather than only refreshing once on open — so a new join
    // request, or your own request finally getting approved, shows up without needing to collapse
    // and re-expand this section (or switch tabs and back) to force a re-fetch.
    LaunchedEffect(Unit) {
        while (true) {
            GardenMembershipClient.refreshKnownGardens(context)
            knownGardens = GardenMembershipStore.getKnownGardens(context)
            pendingMyRequests = GardenMembershipStore.getPendingRequests(context)
            refreshing = false
            delay(15_000L)
        }
    }

    LaunchedEffect(activeGardenId) {
        var previousCount = -1
        while (true) {
            val currentIsOwner = activeGardenId == installId ||
                GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == activeGardenId }?.role == "owner"
            if (currentIsOwner) {
                when (val result = GardenMembershipClient.listPendingRequestsForGarden(context, activeGardenId)) {
                    is GardenMembershipResult.Success -> {
                        pendingForActiveGarden = result.value
                        // Only announce a genuine increase, not the first load (previousCount == -1) or a
                        // decrease (someone got approved/rejected) — otherwise every poll after the very
                        // first would either spam a snackbar for nothing new or announce on every load.
                        if (previousCount in 0 until result.value.size) {
                            snackbarHostState.showSnackbar("New request to join ${activeGarden?.name ?: "this garden"}")
                        }
                        previousCount = result.value.size
                    }
                    else -> {}
                }
            } else {
                pendingForActiveGarden = emptyList()
                previousCount = -1
            }
            delay(15_000L)
        }
    }

    Text("Choose which garden's plants and care history you're viewing and editing.", fontSize = 12.sp, color = Color.Gray)
    Spacer(Modifier.height(10.dp))

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = activeGarden?.name ?: "My Garden", onValueChange = {}, readOnly = true,
            label = { Text("Active garden") },
            leadingIcon = { Text("🌿", fontSize = 16.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            gardens.forEach { garden ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(garden.name, modifier = Modifier.weight(1f))
                            if (garden.role == "owner") {
                                PermissionChip("Owner", Color(0xFF3A5A40))
                            } else {
                                PermissionChip(if (garden.permission == "read") "View-only" else "Editor", Color(0xFF6E6E6E))
                            }
                        }
                    },
                    onClick = {
                        activeGardenId = garden.gardenId
                        GardenMembershipStore.setActiveGardenId(context, if (garden.gardenId == installId) null else garden.gardenId)
                        expanded = false
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (pendingForActiveGarden.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "🔔 ${pendingForActiveGarden.size} request${if (pendingForActiveGarden.size == 1) "" else "s"} to join ${activeGarden?.name ?: "this garden"}",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6B5300)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showCreateDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text("🌱 New garden", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (isOwner) {
            OutlinedButton(onClick = { showShareDialog = true; shareCode = null }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text("🔗 Share", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
        OutlinedButton(onClick = { showJoinDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text("🔑 Have code?", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
    if (isOwner) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showRenameDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text("✏️ Rename", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            OutlinedButton(onClick = { showManageAccessDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) {
                Text(
                    if (pendingForActiveGarden.isNotEmpty()) "👥 Manage access (${pendingForActiveGarden.size})" else "👥 Manage access",
                    fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // The device's own original default garden can't be deleted this way — it's always this
        // device's fallback identity, not something created/joined that could be given up.
        if (activeGardenId != installId) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDeleteGardenConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
            ) { Text("🗑️ Delete ${activeGarden?.name ?: "this garden"}", fontSize = 12.sp) }
        }
    } else if (activeGardenId != installId) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showLeaveConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
        ) { Text("🚪 Leave ${activeGarden?.name ?: "this garden"}", fontSize = 12.sp) }
    }

    if (pendingForActiveGarden.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Requests to join ${activeGarden?.name ?: "this garden"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        pendingForActiveGarden.forEach { req ->
            val requesterLabel = req.displayName?.takeIf { it.isNotBlank() } ?: "Device ${req.requestingDeviceId.take(8)}"
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBubble(requesterLabel)
                        Spacer(Modifier.width(10.dp))
                        Text(requesterLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        PermissionChip(if (req.requestedPermission == "read") "View-only" else "Edit", Color(0xFF6E6E6E))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    GardenMembershipClient.respondToJoinRequest(context, activeGardenId, req.requestingDeviceId, approve = true, permission = req.requestedPermission)
                                    pendingForActiveGarden = pendingForActiveGarden.filterNot { it.requestingDeviceId == req.requestingDeviceId }
                                    snackbarHostState.showSnackbar("Approved")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("✓ Approve", fontSize = 12.sp) }
                        OutlinedButton(
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B)),
                            onClick = {
                                scope.launch {
                                    GardenMembershipClient.respondToJoinRequest(context, activeGardenId, req.requestingDeviceId, approve = false)
                                    pendingForActiveGarden = pendingForActiveGarden.filterNot { it.requestingDeviceId == req.requestingDeviceId }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("✕ Reject", fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    if (pendingMyRequests.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Your pending requests", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        pendingMyRequests.forEach { req ->
            Text("• ${req.name} — waiting for approval", fontSize = 12.sp, color = Color.Gray)
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create a new garden") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Garden name") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim().ifBlank { "New Garden" }
                        scope.launch {
                            when (val result = GardenMembershipClient.createGarden(context, trimmed)) {
                                is GardenMembershipResult.Success -> {
                                    knownGardens = GardenMembershipStore.getKnownGardens(context)
                                    activeGardenId = result.value.gardenId
                                    GardenMembershipStore.setActiveGardenId(context, result.value.gardenId)
                                    snackbarHostState.showSnackbar("Created \"$trimmed\"")
                                }
                                else -> snackbarHostState.showSnackbar("Couldn't create garden — try again.")
                            }
                        }
                        showCreateDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    if (showShareDialog) {
        var loadingShareCode by remember { mutableStateOf(true) }
        LaunchedEffect(showShareDialog) {
            loadingShareCode = true
            when (val result = GardenMembershipClient.getInviteCode(context, activeGardenId)) {
                is GardenMembershipResult.Success -> shareCode = result.value
                else -> {}
            }
            loadingShareCode = false
        }
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("Share ${activeGarden?.name ?: "this garden"}") },
            text = {
                Column {
                    when {
                        loadingShareCode -> Text("Loading…", fontSize = 12.sp, color = Color.Gray)
                        shareCode == null -> Text("No code yet — tap \"Generate\" below to create one for others to enter under \"Have code?\" on their own device.", fontSize = 12.sp, color = Color.Gray)
                        else -> {
                            Text("Share this code:", fontSize = 12.sp, color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                shareCode ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Garden invite code", shareCode))
                                    scope.launch { snackbarHostState.showSnackbar("Code copied") }
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("(tap to copy)", fontSize = 11.sp, color = Color.Gray)
                            Spacer(Modifier.height(10.dp))
                            Text("Regenerating invalidates this code for anyone you haven't shared it with yet.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    loadingShareCode = true
                    scope.launch {
                        when (val result = GardenMembershipClient.regenerateInviteCode(context, activeGardenId)) {
                            is GardenMembershipResult.Success -> shareCode = result.value
                            else -> snackbarHostState.showSnackbar("Couldn't generate a code — try again.")
                        }
                        loadingShareCode = false
                    }
                }, enabled = !loadingShareCode) { Text(if (shareCode == null) "Generate" else "Regenerate") }
            },
            dismissButton = { TextButton(onClick = { showShareDialog = false }) { Text("Close") } }
        )
    }

    if (showJoinDialog) {
        var code by remember { mutableStateOf("") }
        var wantsWrite by remember { mutableStateOf(true) }
        var displayName by remember { mutableStateOf(GardenMembershipStore.getDeviceDisplayName(context)) }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Request access to a garden") },
            text = {
                Column {
                    OutlinedTextField(
                        value = displayName, onValueChange = { displayName = it },
                        label = { Text("Your name") }, placeholder = { Text("e.g. My phone") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Shown to the garden's owner so they know whose request this is.", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Invite code") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Request edit access", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = wantsWrite, onCheckedChange = { wantsWrite = it })
                    }
                    Text(
                        if (wantsWrite) "The owner can still grant view-only instead." else "View-only — you won't be able to make changes.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedCode = code.trim()
                        val trimmedName = displayName.trim()
                        GardenMembershipStore.setDeviceDisplayName(context, trimmedName)
                        showJoinDialog = false
                        scope.launch {
                            when (GardenMembershipClient.requestJoinGarden(context, trimmedCode, if (wantsWrite) "write" else "read", trimmedName.ifBlank { null })) {
                                is GardenMembershipResult.Success -> {
                                    refresh()
                                    snackbarHostState.showSnackbar("Request sent — the owner needs to approve it.")
                                }
                                else -> snackbarHostState.showSnackbar("Couldn't find that code — check it and try again.")
                            }
                        }
                    },
                    enabled = code.isNotBlank()
                ) { Text("Request") }
            },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRenameDialog) {
        var name by remember { mutableStateOf(activeGarden?.name?.takeIf { it != "My Garden" } ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename garden") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Garden name") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim().ifBlank { "My Garden" }
                        showRenameDialog = false
                        scope.launch {
                            when (GardenMembershipClient.renameGarden(context, activeGardenId, trimmed)) {
                                is GardenMembershipResult.Success -> {
                                    knownGardens = GardenMembershipStore.getKnownGardens(context)
                                    snackbarHostState.showSnackbar("Renamed to \"$trimmed\"")
                                }
                                else -> snackbarHostState.showSnackbar("Couldn't rename — try again.")
                            }
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }

    if (showManageAccessDialog) {
        var members by remember { mutableStateOf<List<GardenMember>>(emptyList()) }
        var loadingMembers by remember { mutableStateOf(true) }
        LaunchedEffect(showManageAccessDialog) {
            loadingMembers = true
            when (val result = GardenMembershipClient.listMembers(context, activeGardenId)) {
                is GardenMembershipResult.Success -> members = result.value
                else -> snackbarHostState.showSnackbar("Couldn't load members — try again.")
            }
            loadingMembers = false
        }
        AlertDialog(
            onDismissRequest = { showManageAccessDialog = false },
            title = { Text("Who has access") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        loadingMembers -> Text("Loading…", fontSize = 12.sp, color = Color.Gray)
                        members.isEmpty() -> Text("No one else has access yet.", fontSize = 12.sp, color = Color.Gray)
                        else -> members.forEach { member ->
                            val memberLabel = member.displayName?.takeIf { it.isNotBlank() }
                                ?: (if (member.role == "owner") "You" else "Device ${member.deviceId.take(8)}")
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AvatarBubble(memberLabel)
                                    Spacer(Modifier.width(10.dp))
                                    Text(memberLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (member.role == "owner") {
                                        PermissionChip("Owner", Color(0xFF3A5A40))
                                    } else {
                                        PermissionChip(if (member.permission == "write") "Editor" else "View-only", Color(0xFF6E6E6E))
                                    }
                                }
                                if (member.role != "owner") {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 42.dp)) {
                                        Text("Edit access", fontSize = 12.sp, modifier = Modifier.weight(1f), color = Color.Gray)
                                        Switch(
                                            checked = member.permission == "write",
                                            onCheckedChange = { checked ->
                                                val newPermission = if (checked) "write" else "read"
                                                members = members.map { if (it.deviceId == member.deviceId) it.copy(permission = newPermission) else it }
                                                scope.launch {
                                                    when (GardenMembershipClient.updateMemberPermission(context, activeGardenId, member.deviceId, newPermission)) {
                                                        is GardenMembershipResult.Success -> {}
                                                        else -> snackbarHostState.showSnackbar("Couldn't update access — try again.")
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    TextButton(
                                        contentPadding = PaddingValues(start = 42.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                                        onClick = {
                                            scope.launch {
                                                when (GardenMembershipClient.removeMember(context, activeGardenId, member.deviceId)) {
                                                    is GardenMembershipResult.Success -> {
                                                        members = members.filterNot { it.deviceId == member.deviceId }
                                                        snackbarHostState.showSnackbar("Removed")
                                                    }
                                                    else -> snackbarHostState.showSnackbar("Couldn't remove — try again.")
                                                }
                                            }
                                        }
                                    ) { Text("Remove access", color = Color(0xFFB23B3B), fontSize = 12.sp) }
                                }
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showManageAccessDialog = false }) { Text("Close") } }
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave ${activeGarden?.name ?: "this garden"}?") },
            text = { Text("You'll lose access to its plants and history until someone invites you back in.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val leavingId = activeGardenId
                    showLeaveConfirm = false
                    scope.launch {
                        when (GardenMembershipClient.leaveGarden(context, leavingId)) {
                            is GardenMembershipResult.Success -> {
                                knownGardens = GardenMembershipStore.getKnownGardens(context)
                                activeGardenId = ActiveGardenState.activeGardenId ?: installId
                                snackbarHostState.showSnackbar("Left the garden")
                            }
                            else -> snackbarHostState.showSnackbar("Couldn't leave — try again.")
                        }
                    }
                }) { Text("Leave", color = Color(0xFFB23B3B)) }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteGardenConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deletingGarden) showDeleteGardenConfirm = false },
            title = { Text("Delete ${activeGarden?.name ?: "this garden"}?") },
            text = {
                Text(
                    "This permanently deletes the garden and every member's access to it — including yours. All its plants, care history, and settings on the server are gone. This can't be undone.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletingId = activeGardenId
                        deletingGarden = true
                        scope.launch {
                            when (GardenMembershipClient.deleteGarden(context, deletingId)) {
                                is GardenMembershipResult.Success -> {
                                    knownGardens = GardenMembershipStore.getKnownGardens(context)
                                    activeGardenId = ActiveGardenState.activeGardenId ?: installId
                                    snackbarHostState.showSnackbar("Garden deleted")
                                }
                                else -> snackbarHostState.showSnackbar("Couldn't delete — try again.")
                            }
                            deletingGarden = false
                            showDeleteGardenConfirm = false
                        }
                    },
                    enabled = !deletingGarden
                ) { Text(if (deletingGarden) "Deleting…" else "Delete", color = Color(0xFFB23B3B)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteGardenConfirm = false }, enabled = !deletingGarden) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    viewModel: PlantViewModel, wateringViewModel: WateringZoneViewModel, pathViewModel: IrrigationPathViewModel,
    snackbarHostState: SnackbarHostState, scope: CoroutineScope,
    onOpenFaq: () -> Unit
) {
    val context = LocalContext.current
    val plants by viewModel.plants.collectAsState()
    val isGardenOwner = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) }
    val canEditActiveGarden = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }
    var showResetDialog by remember { mutableStateOf(false) }
    var photoMode by remember { mutableStateOf(getPhotoStorageMode(context)) }
    var importResultDialog by remember { mutableStateOf<CsvImportOutcome?>(null) }
    val focusWeatherSection = PendingHelpFocusState.focusWeatherSection
    LaunchedEffect(Unit) { PendingHelpFocusState.focusWeatherSection = false }

    val zoneRows = remember(ActiveGardenState.activeGardenId) {
        val initial = getTuyaZoneMappings(context).map { Triple(it.zone, it.deviceId, it.outlet) }
        mutableStateListOf(*(if (initial.isEmpty()) listOf(Triple("", "", "1")) else initial).toTypedArray())
    }
    val rachioZoneRows = remember(ActiveGardenState.activeGardenId) {
        val initial = getRachioZoneMappings(context).map { Triple(it.zone, it.deviceId, it.zoneId) }
        mutableStateListOf(*(if (initial.isEmpty()) listOf(Triple("", "", "")) else initial).toTypedArray())
    }
    val irrigationEvents by wateringViewModel.events.collectAsState()
    val irrigationPaths by pathViewModel.paths.collectAsState()

    val irrigationExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(wateringEventsToCsv(irrigationEvents).toByteArray())
                    }
                    snackbarHostState.showSnackbar("Exported ${irrigationEvents.size} irrigation event(s)")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Export failed: ${e.message}")
                }
            }
        }
    }

    val irrigationImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    if (text == null) {
                        importResultDialog = CsvImportOutcome("Import failed", "Couldn't read that file.")
                        return@launch
                    }
                    val result = parseIrrigationCsv(text)
                    if (result is CsvImportResult.Success) wateringViewModel.importEvents(result.items)
                    importResultDialog = csvImportResultToOutcome(result)
                } catch (e: Exception) {
                    importResultDialog = CsvImportOutcome("Import failed", e.message ?: "Unknown error")
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    val sb = StringBuilder()
                    sb.append(CSV_HEADERS.joinToString(",")).append("\n")
                    plants.forEach { p ->
                        val row = listOf(
                            p.id, p.name, p.qty.toString(), p.sci, p.location, p.date, p.source,
                            p.sun, p.soil, p.soilPh, p.category, p.water, p.frost, p.native, p.pollinator, p.notes,
                            p.lat?.toString() ?: "", p.lng?.toString() ?: "", p.wateringSystem
                        ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                        sb.append(row).append("\n")
                    }
                    out.write(sb.toString().toByteArray())
                }
                scope.launch { snackbarHostState.showSnackbar("Exported ${plants.size} plants") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.message}") }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                importResultDialog = if (text == null) {
                    CsvImportOutcome("Import failed", "Couldn't read that file.")
                } else {
                    importPlantsCsv(context, text, plants, viewModel)
                }
            }
        }
    }

    var dropboxCsvExporting by remember { mutableStateOf(false) }
    var dropboxCsvImporting by remember { mutableStateOf(false) }
    var showDropboxCsvPicker by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            setLocalPhotoFolderUri(context, uri)
        }
    }

    // Deliberately separate from folderPickerLauncher above — irrigation_log.csv used to silently
    // default to wherever photos were configured to go, with no way for the user to choose a
    // different location for it specifically.
    var irrigationLogFolder by remember { mutableStateOf(getIrrigationLogFolderUri(context)) }
    val irrigationLogFolderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            setIrrigationLogFolderUri(context, uri)
            irrigationLogFolder = uri
        }
    }

    val helpScrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(helpScrollState).imePadding().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable { onOpenFaq() }) {
            Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Frequently Asked Questions", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = Color.Gray)
            }
        }

        // 1) App settings & notifications
        ExpandableSection(title = "App settings & notifications", initiallyExpanded = true) {
            var landingTab by remember { mutableStateOf(getDefaultLandingTab(context)) }
            DropdownField(
                label = "Default tab on open",
                options = landingTabOptions.map { it.label },
                selected = landingTabOptions.firstOrNull { it.key == landingTab }?.label ?: "Map",
                onSelect = { label ->
                    val key = landingTabOptions.firstOrNull { it.label == label }?.key ?: "map"
                    landingTab = key
                    setDefaultLandingTab(context, key)
                }
            )
            Text("Takes effect next time you open the app.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            GardenAddressSection(context, scope, snackbarHostState, initiallyExpanded = focusWeatherSection)

            GardenZonesSection(context, plants)

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            ExpandableSection(title = "Plant notifications") {
            Text("Get reminded when your plants require care.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))

            var notifsEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getNotificationsEnabled(context)) }
            var notifStyle by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getNotificationStyle(context)) }
            var notifOffsets by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getNotificationOffsets(context)) }
            var notifHour by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getNotificationHour(context)) }
            var notifMinute by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getNotificationMinute(context)) }
            var hasNotifPermission by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasNotifPermission = granted
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val hasExactAlarmPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Enable notifications", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = notifsEnabled, onCheckedChange = { checked ->
                    notifsEnabled = checked
                    setNotificationsEnabled(context, checked)
                    if (checked) {
                        scheduleWateringReminders(context)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else cancelWateringReminders(context)
                })
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && notifsEnabled && !hasNotifPermission) {
                Spacer(Modifier.height(6.dp))
                Text("Notification permission isn't granted — enable it in system settings for reminders to show.", fontSize = 11.sp, color = Color(0xFFB23B3B))
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && notifsEnabled && !hasExactAlarmPermission) {
                Spacer(Modifier.height(6.dp))
                Text("Exact alarm permission isn't granted — reminders may fire late or not at all.", fontSize = 11.sp, color = Color(0xFFB23B3B))
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }) { Text("Grant exact alarm permission") }
            }

            if (notifsEnabled) {
                Spacer(Modifier.height(12.dp))
                Text("Style", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("lockscreen" to "Lock screen", "popup" to "Pop-up", "both" to "Both").forEach { (key, label) ->
                        Button(
                            onClick = { notifStyle = key; setNotificationStyle(context, key) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (notifStyle == key) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                                contentColor = if (notifStyle == key) Color.White else Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Remind me", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                listOf(0 to "On the day", 1 to "1 day before", 2 to "2 days before", 3 to "3 days before").forEach { (days, label) ->
                    val checked = notifOffsets.contains(days)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val updated = (if (checked) notifOffsets - days else notifOffsets + days).ifEmpty { setOf(0) }
                            notifOffsets = updated; setNotificationOffsets(context, updated)
                        }.padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            val updated = (if (checked) notifOffsets - days else notifOffsets + days).ifEmpty { setOf(0) }
                            notifOffsets = updated; setNotificationOffsets(context, updated)
                        })
                        Text(label, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Overdue repeat reminders", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                var overdueRepeatEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getOverdueRepeatEnabled(context)) }
                var overdueRepeatDaysText by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getOverdueRepeatDays(context).toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep reminding while overdue", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = overdueRepeatEnabled, onCheckedChange = {
                        overdueRepeatEnabled = it; setOverdueRepeatEnabled(context, it)
                    })
                }
                if (overdueRepeatEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = overdueRepeatDaysText,
                        onValueChange = { new ->
                            overdueRepeatDaysText = new.filter { it.isDigit() }
                            overdueRepeatDaysText.toIntOrNull()?.let { setOverdueRepeatDays(context, it) }
                        },
                        label = { Text("Repeat every (days)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("Re-notify for overdue plants until \"last watered\" is updated", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                var fertiliseReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getFertiliseRemindersEnabled(context)) }
                var pruneReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getPruneRemindersEnabled(context)) }
                var feedReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getFeedRemindersEnabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Include fertilising reminders", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = fertiliseReminders, onCheckedChange = { fertiliseReminders = it; setFertiliseRemindersEnabled(context, it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Include pruning reminders", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = pruneReminders, onCheckedChange = { pruneReminders = it; setPruneRemindersEnabled(context, it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Include feeding reminders", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = feedReminders, onCheckedChange = { feedReminders = it; setFeedRemindersEnabled(context, it) })
                }

                Spacer(Modifier.height(10.dp))
                Text("Notify at", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                var showTimeDialog by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showTimeDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(String.format("%02d:%02d", notifHour, notifMinute))
                }
                if (showTimeDialog) {
                    val timeState = rememberTimePickerState(initialHour = notifHour, initialMinute = notifMinute)
                    Dialog(onDismissRequest = { showTimeDialog = false }) {
                        Card {
                            Column(Modifier.padding(16.dp)) {
                                TimePicker(state = timeState)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { showTimeDialog = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                    Button(
                                        onClick = {
                                            notifHour = timeState.hour; notifMinute = timeState.minute
                                            setNotificationTime(context, notifHour, notifMinute)
                                            scheduleWateringReminders(context)
                                            showTimeDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Set") }
                                }
                            }
                        }
                    }
                }
            }
            }

            ExpandableSection(title = "Hemisphere") {
            Text("Which months count as summer vs winter for each plant's seasonal watering frequency overrides (set on the Add/Edit plant screen).", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            val gardenHasAddress = remember(ActiveGardenState.activeGardenId) { getGardenLatLng(context) != null }
            if (gardenHasAddress) {
                val hemisphere = remember(ActiveGardenState.activeGardenId) { getHemisphere(context) }
                Text(
                    "Auto-detected: ${if (hemisphere == Hemisphere.NORTHERN) "Northern" else "Southern"} (based on your garden address)",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3A5A40)
                )
                Text("Set a different garden address above to change this.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
            } else {
                var hemisphere by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getHemisphere(context)) }
                Text("No garden address set yet — pick manually for now, or set an address above to detect this automatically.", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Hemisphere.SOUTHERN to "Southern (e.g. Australia)", Hemisphere.NORTHERN to "Northern").forEach { (value, label) ->
                        Button(
                            onClick = { hemisphere = value; setHemisphere(context, value) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hemisphere == value) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                                contentColor = if (hemisphere == value) Color.White else Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
            }

            if (FeatureVisibility.shouldShow(context, Feature.WEATHER_AWARE_REMINDERS)) {
            ExpandableSection(title = "Weather-aware reminders") {
            Text("When enabled, watering reminders will flag when significant rain is expected, so you know to consider skipping.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))

            var weatherSkipEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getWeatherSkipEnabled(context)) }
            var rainThreshold by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getRainProbabilityThreshold(context)) }
            val hasGardenAddress = remember { getGardenLatLng(context) != null }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Flag reminders when rain is likely", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = weatherSkipEnabled, onCheckedChange = { weatherSkipEnabled = it; setWeatherSkipEnabled(context, it) })
            }
            if (!hasGardenAddress) {
                Text("Set your garden address above (Garden address) to use this.", fontSize = 11.sp, color = Color.Gray)
            }
            var frostWarningsEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getFrostWarningsEnabled(context)) }
            var frostThreshold by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getFrostTempThreshold(context).toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Warn about frost risk for tender outdoor plants", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = frostWarningsEnabled, onCheckedChange = { frostWarningsEnabled = it; setFrostWarningsEnabled(context, it) })
            }
            if (frostWarningsEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("Warn when the forecast minimum is at or below ${"%.1f".format(frostThreshold)}°C", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = frostThreshold, onValueChange = { frostThreshold = it },
                    onValueChangeFinished = { setFrostTempThreshold(context, frostThreshold.toDouble()) },
                    valueRange = -5f..8f, steps = 12
                )
            }

                if (weatherSkipEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text("Flag if rain probability is at least $rainThreshold%", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = rainThreshold.toFloat(), onValueChange = { rainThreshold = it.toInt() },
                        onValueChangeFinished = { setRainProbabilityThreshold(context, rainThreshold) },
                        valueRange = 10f..100f, steps = 8
                    )

                    Spacer(Modifier.height(8.dp))
                    var rainAmountThreshold by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getRainAmountThreshold(context)) }
                    Text(
                        "And at least ${"%.1f".format(rainAmountThreshold)}mm forecast (filters out high-probability drizzle)",
                        fontSize = 12.sp, color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = rainAmountThreshold, onValueChange = { rainAmountThreshold = it },
                        onValueChangeFinished = { setRainAmountThreshold(context, rainAmountThreshold) },
                        valueRange = 0f..20f, steps = 39
                    )
                }
            }
        }
        }

        // 2) Photos & cloud storage — device-wide Dropbox connection/storage mode, not tied to any
        // one garden, so hidden entirely for a non-owner viewing someone else's shared garden
        // (matches the Custom garden map / Irrigation gating below).
        if (isGardenOwner) {
        ExpandableSection(title = "Photos & cloud storage") {
            Text("Photo storage", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Choose whether new photos are stored on this device, or automatically saved to your own cloud storage. See the FAQ above for the pros and cons of each.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { photoMode = "local"; setPhotoStorageMode(context, "local") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (photoMode == "local") Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                        contentColor = if (photoMode == "local") Color.White else Color.Black
                    )
                ) { Text("On this device", fontSize = 12.sp) }
                Button(
                    onClick = { photoMode = "cloud"; setPhotoStorageMode(context, "cloud") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (photoMode == "cloud") Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                        contentColor = if (photoMode == "cloud") Color.White else Color.Black
                    )
                ) { Text("Cloud link", fontSize = 12.sp) }
            }
            if (photoMode == "cloud") {
                Spacer(Modifier.height(8.dp))
                val dropboxToken = DropboxAuthState.token
                if (dropboxToken != null) {
                    Text("✅ Connected to Dropbox", fontSize = 12.sp, color = Color(0xFF3A5A40))
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { DropboxAuthState.clear(context) }) { Text("Disconnect") }
                } else {
                    Button(onClick = { startDropboxSignIn(context) }, modifier = Modifier.fillMaxWidth()) { Text("Connect Dropbox") }
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("Auto-link photos by Plant ID", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Photos named after a Plant ID (e.g. \"P0001.jpg\") link automatically. Only new, unlinked photos are added.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))

            if (photoMode == "local") {
                var localFolder by remember { mutableStateOf(getLocalPhotoFolderUri(context)) }
                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (localFolder != null) "Change photo folder" else "Choose photo folder")
                }
                if (localFolder != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            val count = autoLinkLocalPhotos(context, localFolder!!, plants) { viewModel.save(it) }
                            snackbarHostState.showSnackbar("Linked $count new photo(s)")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Auto-link photos now") }
                }
            } else {
                var dropboxPath by remember { mutableStateOf(getDropboxPhotoFolderPath(context) ?: "") }
                var showFolderPicker by remember { mutableStateOf(false) }
                var testResult by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = dropboxPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text("Dropbox folder") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Browse Dropbox…") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                testing = true; testResult = null
                                val result = countDropboxImages(context, dropboxPath)
                                testing = false
                                testResult = result.fold({ "✅ Found $it image(s) in this folder" }, { "❌ ${it.message}" })
                            }
                        },
                        modifier = Modifier.weight(1f), enabled = !testing && !DropboxLinkState.linking,
                        contentPadding = CompactButtonPadding
                    ) { Text(if (testing) "Testing…" else "Test connection") }

                    Button(
                        onClick = { setDropboxPhotoFolderPath(context, dropboxPath); viewModel.runDropboxAutoLink(context, dropboxPath) },
                        modifier = Modifier.weight(1f), enabled = !testing && !DropboxLinkState.linking,
                        contentPadding = CompactButtonPadding
                    ) { Text(if (DropboxLinkState.linking) "Linking…" else "Auto-link now") }
                }

                testResult?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = if (it.startsWith("✅")) Color(0xFF3A5A40) else Color(0xFFB23B3B))
                }
                if (DropboxLinkState.linking) {
                    Spacer(Modifier.height(10.dp))
                    val current = DropboxLinkState.current; val total = DropboxLinkState.total
                    LinearProgressIndicator(progress = { if (total > 0) current.toFloat() / total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("$current of $total images linked", fontSize = 12.sp, color = Color.Gray)
                }
                val linkResult = DropboxLinkState.result ?: getLastDropboxLinkResult(context)
                linkResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (it.startsWith("Linking unsuccessful")) Color(0xFFB23B3B) else Color(0xFF3A5A40))
                }
                if (showFolderPicker) {
                    DropboxFolderPickerDialog(
                        context = context, onDismiss = { showFolderPicker = false },
                        onFolderSelected = { path -> dropboxPath = path; setDropboxPhotoFolderPath(context, path) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        plants.forEach { viewModel.save(it.copy(photoUri = null, photoUris = emptyList(), photoThumbnailBase64 = null)) }
                        snackbarHostState.showSnackbar("Cleared photos from ${plants.size} plant(s)")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
            ) { Text("Clear auto-linked photos (keep plants)") }
        }
        }

        // 3) Irrigation (Advanced mode + Pro only — hiding it never touches the saved Tuya or Rachio credentials/zones below).
        // Also device-wide/owner-only, same reasoning as Photos & cloud storage above.
        if (isGardenOwner && FeatureVisibility.shouldShow(context, Feature.TUYA_INTEGRATION)) {
        ExpandableSection(title = "Irrigation") {
            var irrigationSystem by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getIrrigationSystem(context)) }
            Text("Which irrigation system do you have?", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(IrrigationSystem.NONE to "None", IrrigationSystem.TUYA to "Tuya", IrrigationSystem.RACHIO to "Rachio").forEach { (value, label) ->
                    Button(
                        onClick = { irrigationSystem = value; setIrrigationSystem(context, value) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (irrigationSystem == value) Color(0xFF3A5A40) else Color(0xFFE3DDCF),
                            contentColor = if (irrigationSystem == value) Color.White else Color.Black
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = CompactButtonPadding
                    ) { Text(label, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Switching doesn't delete the other system's saved credentials or zones — it just hides them.", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            if (irrigationSystem == IrrigationSystem.TUYA) {
            var tuyaClientId by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getTuyaClientId(context)) }
            var tuyaClientSecret by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getTuyaClientSecret(context)) }
            var tuyaSecretVisible by remember { mutableStateOf(false) }
            var tuyaEditing by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getTuyaClientId(context).isBlank() || getTuyaClientSecret(context).isBlank()) }

            Text("Tuya connection", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect your own Tuya Cloud project to sync smart-irrigation history. Stored only on this device — never shared with other users of this app, and not included in backups.",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = tuyaClientId,
                onValueChange = { tuyaClientId = it },
                label = { Text("Tuya Client ID") },
                singleLine = true,
                readOnly = !tuyaEditing,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tuyaClientSecret,
                onValueChange = { tuyaClientSecret = it },
                label = { Text("Tuya Client Secret") },
                singleLine = true,
                readOnly = !tuyaEditing,
                visualTransformation = if (tuyaSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { tuyaSecretVisible = !tuyaSecretVisible }) {
                        Text(if (tuyaSecretVisible) "Hide" else "Show", fontSize = 11.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (tuyaClientId.isBlank() || tuyaClientSecret.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Both fields are required to sync irrigation history.", fontSize = 11.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            if (tuyaEditing) {
                Button(
                    onClick = {
                        setTuyaClientId(context, tuyaClientId)
                        setTuyaClientSecret(context, tuyaClientSecret)
                        TuyaClient.invalidateToken()
                        tuyaEditing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Tuya credentials") }
            } else {
                OutlinedButton(onClick = { tuyaEditing = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Tuya credentials") }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            var zonesExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { zonesExpanded = !zonesExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Irrigation zones (Tuya)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (zonesExpanded) "▾" else "▸ ${zoneRows.count { it.first.isNotBlank() }}", color = Color.Gray, fontSize = 13.sp)
            }
            if (zonesExpanded) {
                Spacer(Modifier.height(6.dp))
                Text("Add a friendly zone name for each physical outlet on your Tuya devices. Zone names should match your Watering System field values.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(10.dp))
                zoneRows.forEachIndexed { index, (zoneName, deviceId, outlet) ->
                    Column(Modifier.padding(bottom = 12.dp)) {
                        OutlinedTextField(value = zoneName, onValueChange = { zoneRows[index] = Triple(it, deviceId, outlet) }, label = { Text("Zone name") }, placeholder = { Text("e.g. Front Garden") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = deviceId, onValueChange = { zoneRows[index] = Triple(zoneName, it, outlet) }, label = { Text("Tuya Device ID") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                DropdownField(label = "Outlet", options = listOf("1", "2"), selected = outlet, onSelect = { zoneRows[index] = Triple(zoneName, deviceId, it) })
                            }
                            if (zoneRows.size > 1) IconButton(onClick = { zoneRows.removeAt(index) }) { Text("✕") }
                        }
                    }
                }
                OutlinedButton(onClick = { zoneRows.add(Triple("", "", "1")) }, modifier = Modifier.fillMaxWidth()) { Text("+ Add zone") }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val mappings = zoneRows.filter { it.first.isNotBlank() && it.second.isNotBlank() }.map { TuyaZoneMapping(it.first, it.second, it.third) }
                        setTuyaZoneMappings(context, mappings)
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping saved") }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save zone mapping") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        zoneRows.clear(); zoneRows.add(Triple("", "", "1")); setTuyaZoneMappings(context, emptyList())
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping cleared") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
                ) { Text("Clear all zones") }
            }
            }

            if (irrigationSystem == IrrigationSystem.RACHIO) {
            var rachioApiToken by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getRachioApiToken(context)) }
            var rachioTokenVisible by remember { mutableStateOf(false) }
            var rachioEditing by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getRachioApiToken(context).isBlank()) }
            var rachioTesting by remember { mutableStateOf(false) }
            var rachioTestResult by remember { mutableStateOf<String?>(null) }

            Text("Rachio connection", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect your Rachio account with the API key from the Rachio app (Profile → API key) to sync smart-irrigation history. Stored only on this device — never shared with other users of this app, and not included in backups.",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = rachioApiToken,
                onValueChange = { rachioApiToken = it },
                label = { Text("Rachio API token") },
                singleLine = true,
                readOnly = !rachioEditing,
                visualTransformation = if (rachioTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { rachioTokenVisible = !rachioTokenVisible }) {
                        Text(if (rachioTokenVisible) "Hide" else "Show", fontSize = 11.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (rachioApiToken.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("An API token is required to sync irrigation history.", fontSize = 11.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            if (rachioEditing) {
                Button(
                    onClick = {
                        setRachioApiToken(context, rachioApiToken)
                        rachioEditing = false
                        rachioTestResult = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Rachio API token") }
            } else {
                OutlinedButton(onClick = { rachioEditing = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Rachio API token") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            rachioTesting = true
                            rachioTestResult = try {
                                val devices = RachioClient.getDevices(context)
                                val zoneCount = devices.sumOf { it.zones.size }
                                "Connected — found ${devices.size} device(s), $zoneCount zone(s)."
                            } catch (e: Exception) {
                                "Test failed: ${e.message ?: "unknown error"}"
                            }
                            rachioTesting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !rachioTesting
                ) { Text(if (rachioTesting) "Testing…" else "Test connection") }
                rachioTestResult?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = Color.Gray) }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            var rachioZonesExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { rachioZonesExpanded = !rachioZonesExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Irrigation zones (Rachio)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (rachioZonesExpanded) "▾" else "▸ ${rachioZoneRows.count { it.first.isNotBlank() }}", color = Color.Gray, fontSize = 13.sp)
            }
            if (rachioZonesExpanded) {
                Spacer(Modifier.height(6.dp))
                Text("Add a friendly zone name for each Rachio zone (use Test connection above to find your device/zone IDs). Zone names should match your Watering System field values.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(10.dp))
                rachioZoneRows.forEachIndexed { index, (zoneName, deviceId, zoneId) ->
                    Column(Modifier.padding(bottom = 12.dp)) {
                        OutlinedTextField(value = zoneName, onValueChange = { rachioZoneRows[index] = Triple(it, deviceId, zoneId) }, label = { Text("Zone name") }, placeholder = { Text("e.g. Front Garden") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = deviceId, onValueChange = { rachioZoneRows[index] = Triple(zoneName, it, zoneId) }, label = { Text("Rachio Device ID") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = zoneId, onValueChange = { rachioZoneRows[index] = Triple(zoneName, deviceId, it) }, label = { Text("Rachio Zone ID") }, modifier = Modifier.weight(1f))
                            if (rachioZoneRows.size > 1) IconButton(onClick = { rachioZoneRows.removeAt(index) }) { Text("✕") }
                        }
                    }
                }
                OutlinedButton(onClick = { rachioZoneRows.add(Triple("", "", "")) }, modifier = Modifier.fillMaxWidth()) { Text("+ Add zone") }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val mappings = rachioZoneRows.filter { it.first.isNotBlank() && it.second.isNotBlank() && it.third.isNotBlank() }.map { RachioZoneMapping(it.first, it.second, it.third) }
                        setRachioZoneMappings(context, mappings)
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping saved") }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save zone mapping") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        rachioZoneRows.clear(); rachioZoneRows.add(Triple("", "", "")); setRachioZoneMappings(context, emptyList())
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping cleared") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
                ) { Text("Clear all zones") }
            }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("Irrigation log location", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Where irrigation_log.csv is read from and appended to — separate from wherever your photos are stored.",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            if (getPhotoStorageMode(context) == "cloud") {
                var irrigationDropboxPath by remember { mutableStateOf(getIrrigationLogDropboxFolderPath(context) ?: "") }
                var showIrrigationFolderPicker by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = irrigationDropboxPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text("Dropbox folder") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showIrrigationFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Browse Dropbox…") }
                if (showIrrigationFolderPicker) {
                    DropboxFolderPickerDialog(
                        context = context, onDismiss = { showIrrigationFolderPicker = false },
                        onFolderSelected = { path -> irrigationDropboxPath = path; setIrrigationLogDropboxFolderPath(context, path) }
                    )
                }
            } else {
                OutlinedButton(onClick = { irrigationLogFolderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (irrigationLogFolder != null) "Change irrigation log folder" else "Choose irrigation log folder")
                }
            }
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            if (irrigationSystem != IrrigationSystem.NONE) {
            Text("Sync irrigation history", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (irrigationSystem == IrrigationSystem.RACHIO)
                    "Pulls the last 30 days of watering activity from Rachio and appends new sessions to irrigation_log.csv."
                else
                    "Pulls the last 30 days of watering activity from Tuya and appends new sessions to irrigation_log.csv so history isn't lost once Tuya's ~7-day retention rolls over.",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            val syncing by wateringViewModel.syncing.collectAsState()
            val syncResult by wateringViewModel.lastSyncResult.collectAsState()
            Button(onClick = { wateringViewModel.sync(context) }, modifier = Modifier.fillMaxWidth(), enabled = !syncing) { Text(if (syncing) "Syncing…" else "Sync watering history") }
            syncResult?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = Color.Gray) }
            Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { irrigationImportLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Import from device") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = fetchIrrigationCsvFromDropbox(context)
                            if (text != null) {
                                val result = parseIrrigationCsv(text)
                                if (result is CsvImportResult.Success) wateringViewModel.importEvents(result.items)
                                importResultDialog = csvImportResultToOutcome(result)
                            } else {
                                importResultDialog = CsvImportOutcome("Import failed", "Couldn't find irrigation_log.csv in your Dropbox folder.")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = DropboxAuthState.token != null
                ) { Text("Import from Dropbox") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { irrigationExportLauncher.launch("irrigation_log.csv") }, modifier = Modifier.weight(1f), enabled = irrigationEvents.isNotEmpty()) { Text("Export to device") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val saved = saveIrrigationCsvDropbox(context, irrigationEvents)
                            snackbarHostState.showSnackbar(if (saved) "Exported to Dropbox" else "Export to Dropbox failed")
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = irrigationEvents.isNotEmpty() && DropboxAuthState.token != null
                ) { Text("Export to Dropbox") }
            }

        }
        }

        // 3a) Sage assistant on/off
        ExpandableSection(title = "Sage assistant") {
            var sageChatEnabled by remember { mutableStateOf(FeatureVisibility.isSageChatEnabled(context)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Sage assistant", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = sageChatEnabled, onCheckedChange = {
                    sageChatEnabled = it
                    FeatureVisibility.setSageChatEnabled(context, it)
                })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Turn off the floating 🌿 button and plant-form suggestions if you'd rather not see Sage.",
                fontSize = 11.sp, color = Color.Gray
            )
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text(
                "Drag the floating 🌿 button up or down if it's covering something. Stuck somewhere awkward?",
                fontSize = 11.sp, color = Color.Gray
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                FeatureVisibility.setSageFabOffsetDp(context, 0f)
                SageFabResetState.requested = true
            }) {
                Text("Reset button position", fontSize = 12.sp)
            }
        }

        // 3b) Basic / Advanced mode
        ExpandableSection(title = "Basic / Advanced mode") {
            var advancedMode by remember { mutableStateOf(FeatureVisibility.isAdvancedModeEnabled(context)) }

            Text(
                "Basic mode keeps things simple: your plant list, watering schedule and reminders, photo log, plant care widget, and Dropbox backup. Advanced mode adds everything else — weather-aware reminders, irrigation, the sun map, the audit, cost & water usage tracking, watering history, growth photo timelines, the custom garden map, progress photos, soil pH, seasonal watering, fertilising/pruning/feeding, Sage's care-frequency suggestions, plant history, coordinates, place-on-map buttons, extra photos, and multi-device/garden sharing.",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Sage isn't affected by this toggle — it's available in both modes (its own on/off switch is above).",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(if (advancedMode) "Advanced mode" else "Basic mode", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = advancedMode,
                    onCheckedChange = {
                        advancedMode = it
                        FeatureVisibility.setAdvancedModeEnabled(context, it)
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing is deleted when you switch — your Tuya/Rachio setup, sun map, and history stay saved.",
                fontSize = 11.sp, color = Color.Gray
            )

            Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            var promoCode by remember { mutableStateOf("") }
            var redeemingPromo by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = promoCode, onValueChange = { promoCode = it.uppercase() },
                label = { Text("Promo code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    redeemingPromo = true
                    scope.launch {
                        val message = when (val result = EntitlementManager.redeemPromoCode(context, promoCode)) {
                            is PromoRedemptionResult.Success -> { promoCode = ""; "Promo code redeemed!" }
                            PromoRedemptionResult.InvalidCode -> "That code isn't valid."
                            PromoRedemptionResult.Expired -> "That code has expired."
                            PromoRedemptionResult.RedemptionCapReached -> "That code has already been fully redeemed."
                            PromoRedemptionResult.NetworkError -> "Couldn't reach Sage — check your connection and try again."
                        }
                        redeemingPromo = false
                        snackbarHostState.showSnackbar(message)
                    }
                },
                enabled = promoCode.isNotBlank() && !redeemingPromo,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (redeemingPromo) "Redeeming…" else "Redeem promo code") }
        }

        // 4) Custom garden map
        val canManageActiveGardenMap = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) }
        if (canManageActiveGardenMap && FeatureVisibility.shouldShow(context, Feature.CUSTOM_MAP)) {
        ExpandableSection(title = "Custom garden map") {
            Text("Upload a hand-drawn or custom image of your garden instead of using the real-world map.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))

            var customMapUri by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getCustomMapUri(context)) }
            var useCustomMap by remember(ActiveGardenState.activeGardenId) { mutableStateOf(isUsingCustomMap(context)) }
            val mapImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    setCustomMapUri(context, uri); customMapUri = uri
                }
            }
            OutlinedButton(onClick = { mapImageLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (customMapUri != null) "Change custom map image" else "Upload custom map image")
            }
            if (customMapUri != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use custom map instead of real-world map", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = useCustomMap, onCheckedChange = { useCustomMap = it; setUsingCustomMap(context, it) })
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(10.dp))
                var mapRotationDeg by remember(ActiveGardenState.activeGardenId) { mutableStateOf(getCustomMapRotation(context)) }
                Text("Orientation", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        mapRotationDeg = (mapRotationDeg + 90) % 360
                        setCustomMapRotation(context, mapRotationDeg)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Rotate 90° (currently ${mapRotationDeg}°)") }
                Text(
                    "Rotating shifts what's shown at each spot on the map — recheck any markers, paths, or sun zones you've already placed after rotating.",
                    fontSize = 11.sp, color = Color(0xFFB23B3B), modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { setCustomMapUri(context, null); setUsingCustomMap(context, false); customMapUri = null; useCustomMap = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
                ) { Text("Clear custom map") }
            }
        }
        }

        // 5) Data — export/import/backup/reset all mutate or dump the whole garden's data; a
        // view-only member shouldn't see any of it, not just have individual buttons disabled.
        if (canEditActiveGarden) {
        ExpandableSection(title = "Data") {
            Text("Export to spreadsheet", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Downloads all your plant data (excluding photos) as a CSV file.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { exportLauncher.launch("sage_garden_plantdata.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export CSV to device") }
            if (DropboxAuthState.token != null) {
                Spacer(Modifier.height(8.dp))
                var dropboxCsvFolderPath by remember {
                    mutableStateOf(getDropboxCsvFolderPath(context) ?: getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: "")
                }
                var showDropboxCsvFolderPicker by remember { mutableStateOf(false) }
                Text("Dropbox folder", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = dropboxCsvFolderPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text("Dropbox folder") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDropboxCsvFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Browse Dropbox…") }
                if (showDropboxCsvFolderPicker) {
                    DropboxFolderPickerDialog(
                        context = context, onDismiss = { showDropboxCsvFolderPicker = false },
                        onFolderSelected = { path -> dropboxCsvFolderPath = path; setDropboxCsvFolderPath(context, path) }
                    )
                }
                Spacer(Modifier.height(8.dp))

                var checkingExistingCsv by remember { mutableStateOf(false) }
                var existingCsvDate by remember { mutableStateOf<Date?>(null) }
                var showReplaceCsvConfirm by remember { mutableStateOf(false) }

                fun runDropboxCsvExport(fileName: String) {
                    scope.launch {
                        dropboxCsvExporting = true
                        val sb = StringBuilder()
                        sb.append(CSV_HEADERS.joinToString(",")).append("\n")
                        plants.forEach { p ->
                            val row = listOf(
                                p.id, p.name, p.qty.toString(), p.sci, p.location, p.date, p.source,
                                p.sun, p.soil, p.soilPh, p.category, p.water, p.frost, p.native, p.pollinator, p.notes,
                                p.lat?.toString() ?: "", p.lng?.toString() ?: "", p.wateringSystem
                            ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                            sb.append(row).append("\n")
                        }
                        val ok = uploadTextFileToDropbox(context, dropboxCsvFolderPath, fileName, sb.toString())
                        dropboxCsvExporting = false
                        snackbarHostState.showSnackbar(if (ok) "Exported ${plants.size} plants to Dropbox as $fileName" else "Export to Dropbox failed")
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            checkingExistingCsv = true
                            val defaultName = csvExportFileNameForGarden(context, effectiveGardenId(context))
                            val existing = withContext(Dispatchers.IO) {
                                try {
                                    val client = getDropboxClient(context)
                                    val path = "$dropboxCsvFolderPath/$defaultName".replace("//", "/")
                                    (client?.files()?.getMetadata(path) as? com.dropbox.core.v2.files.FileMetadata)?.serverModified
                                } catch (_: Exception) { null }
                            }
                            checkingExistingCsv = false
                            if (existing != null) {
                                existingCsvDate = existing
                                showReplaceCsvConfirm = true
                            } else {
                                runDropboxCsvExport(defaultName)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !dropboxCsvExporting && !checkingExistingCsv
                ) { Text(if (dropboxCsvExporting) "Exporting…" else if (checkingExistingCsv) "Checking…" else "☁️ Export CSV to Dropbox") }
                if (showReplaceCsvConfirm) {
                    val sdf = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
                    val defaultName = remember { csvExportFileNameForGarden(context, effectiveGardenId(context)) }
                    AlertDialog(
                        onDismissRequest = { showReplaceCsvConfirm = false },
                        title = { Text("Replace existing CSV?") },
                        text = { Text("A CSV from ${existingCsvDate?.let { sdf.format(it) } ?: "earlier"} already exists in this Dropbox folder. Replace it, or keep it and save this as a separate new file?") },
                        confirmButton = { TextButton(onClick = { showReplaceCsvConfirm = false; runDropboxCsvExport(defaultName) }) { Text("Replace") } },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { showReplaceCsvConfirm = false }) { Text("Cancel") }
                                TextButton(onClick = {
                                    showReplaceCsvConfirm = false
                                    val timestamped = defaultName.removeSuffix(".csv") + "_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.csv"
                                    runDropboxCsvExport(timestamped)
                                }) { Text("Create new") }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("Import from spreadsheet", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Upload a CSV in the same format to add or update plants in bulk.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                modifier = Modifier.fillMaxWidth(), enabled = canEditActiveGarden
            ) { Text("Choose CSV file from device") }
            if (DropboxAuthState.token != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDropboxCsvPicker = true },
                    modifier = Modifier.fillMaxWidth(), enabled = canEditActiveGarden && !dropboxCsvImporting
                ) { Text(if (dropboxCsvImporting) "Importing…" else "☁️ Choose CSV from Dropbox") }
            }
            if (!canEditActiveGarden) { Spacer(Modifier.height(6.dp)); Text("You have view-only access to this garden.", fontSize = 11.sp, color = Color.Gray) }
            if (showDropboxCsvPicker) {
                DropboxCsvPickerDialog(
                    context = context,
                    onDismiss = { showDropboxCsvPicker = false },
                    onFileSelected = { content ->
                        scope.launch {
                            dropboxCsvImporting = true
                            importResultDialog = importPlantsCsv(context, content, plants, viewModel)
                            dropboxCsvImporting = false
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("Backup & restore all data", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Backs up all plants (including seasonal watering, fertilising, pruning, and indoor/manual-watering settings), irrigation paths and watering history, sun exposure zones, growth timeline photos, fertilise/prune history, Tuya zone mappings, your custom map drawing and its rotation, and all app preferences (weather-aware reminders, garden address, notification and reminder settings, default tab, and more) to a Dropbox folder you choose.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            if (isGardenOwner) {
                Text("Note: locally-stored photos can't be backed up this way — switch to cloud photo storage above first if you want photos to carry across too.", fontSize = 11.sp, color = Color(0xFFB23B3B))
            }
            Spacer(Modifier.height(10.dp))

            var backupWorking by remember { mutableStateOf(false) }
            var restoreWorking by remember { mutableStateOf(false) }
            var backupResultText by remember { mutableStateOf<String?>(null) }
            var showRestoreConfirm by remember { mutableStateOf(false) }
            var showBackupFolderPicker by remember { mutableStateOf(false) }
            var backupFolderPath by remember {
                mutableStateOf(getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: "")
            }

            if (DropboxAuthState.token != null) {
                Text("Backup folder", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = backupFolderPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text("Dropbox folder") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showBackupFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Browse Dropbox…") }
                if (showBackupFolderPicker) {
                    DropboxFolderPickerDialog(
                        context = context, onDismiss = { showBackupFolderPicker = false },
                        onFolderSelected = { path -> backupFolderPath = path; setDropboxBackupFolderPath(context, path) }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            var checkingExistingBackup by remember { mutableStateOf(false) }
            var existingBackupDate by remember { mutableStateOf<Date?>(null) }
            var showReplaceBackupConfirm by remember { mutableStateOf(false) }

            fun runDropboxBackup(jsonFileName: String = BackupHelper.defaultBackupFileNameForGarden(context, effectiveGardenId(context))) {
                scope.launch {
                    backupWorking = true; backupResultText = null
                    val result = BackupHelper.createBackup(context, plants, irrigationPaths, irrigationEvents, jsonFileName)
                    backupWorking = false; backupResultText = result.message
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        checkingExistingBackup = true
                        val existing = BackupHelper.existingBackupModifiedAt(context, BackupHelper.defaultBackupFileNameForGarden(context, effectiveGardenId(context)))
                        checkingExistingBackup = false
                        if (existing != null) {
                            existingBackupDate = existing
                            showReplaceBackupConfirm = true
                        } else {
                            runDropboxBackup()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !backupWorking && !checkingExistingBackup && !restoreWorking && DropboxAuthState.token != null
            ) {
                Text(
                    when {
                        backupWorking -> "Backing up…"
                        checkingExistingBackup -> "Checking…"
                        else -> "Back up to Dropbox now"
                    }
                )
            }
            if (showReplaceBackupConfirm) {
                val sdf = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
                AlertDialog(
                    onDismissRequest = { showReplaceBackupConfirm = false },
                    title = { Text("Replace existing backup?") },
                    text = {
                        Text(
                            "A backup from ${existingBackupDate?.let { sdf.format(it) } ?: "earlier"} already exists in this Dropbox folder. Replace it, or keep it and save this as a separate new backup?"
                        )
                    },
                    confirmButton = { TextButton(onClick = { showReplaceBackupConfirm = false; runDropboxBackup() }) { Text("Replace") } },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { showReplaceBackupConfirm = false }) { Text("Cancel") }
                            TextButton(onClick = {
                                showReplaceBackupConfirm = false
                                runDropboxBackup(BackupHelper.newDatedBackupFileName())
                            }) { Text("Create new") }
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            var showBackupPicker by remember { mutableStateOf(false) }
            var loadingBackupList by remember { mutableStateOf(false) }
            var availableBackups by remember { mutableStateOf<List<BackupHelper.DropboxBackupInfo>>(emptyList()) }
            var selectedRestoreFileName by remember { mutableStateOf<String?>(null) }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loadingBackupList = true
                        availableBackups = BackupHelper.listAvailableBackups(context)
                        loadingBackupList = false
                        // Skip the picker only when there's nothing to choose BETWEEN — either just
                        // one backup total, or this garden's own default-named backup is the only
                        // one present. A shared Dropbox folder can otherwise hold other gardens'
                        // backups too (per-garden filenames), which should still show the picker
                        // rather than silently guessing which file is "the" one to restore.
                        val ownDefaultName = BackupHelper.defaultBackupFileNameForGarden(context, effectiveGardenId(context))
                        val ownDefault = availableBackups.firstOrNull { it.fileName == ownDefaultName }
                        if (availableBackups.size <= 1) {
                            selectedRestoreFileName = availableBackups.firstOrNull()?.fileName ?: ownDefaultName
                            showRestoreConfirm = true
                        } else if (ownDefault != null && availableBackups.none { it.fileName != ownDefaultName && it.modifiedAt.after(ownDefault.modifiedAt) }) {
                            // This garden's own backup exists and nothing else in the folder is
                            // newer than it — treat it as the obvious choice rather than forcing a
                            // picker every single time for the common single-garden case.
                            selectedRestoreFileName = ownDefaultName
                            showRestoreConfirm = true
                        } else {
                            showBackupPicker = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !backupWorking && !restoreWorking && !loadingBackupList && DropboxAuthState.token != null && canEditActiveGarden
            ) { Text(if (restoreWorking) "Restoring…" else if (loadingBackupList) "Checking…" else "Restore from Dropbox") }

            if (showBackupPicker) {
                val sdf = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
                AlertDialog(
                    onDismissRequest = { showBackupPicker = false },
                    title = { Text("Choose a backup to restore") },
                    text = {
                        Column {
                            availableBackups.forEach { info ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            showBackupPicker = false
                                            selectedRestoreFileName = info.fileName
                                            showRestoreConfirm = true
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Column {
                                        Text(info.fileName, fontSize = 13.sp)
                                        Text(sdf.format(info.modifiedAt), fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showBackupPicker = false }) { Text("Cancel") } }
                )
            }

            if (DropboxAuthState.token == null && isGardenOwner) { Spacer(Modifier.height(6.dp)); Text("Connect Dropbox above first.", fontSize = 11.sp, color = Color.Gray) }
            backupResultText?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.sp, color = Color(0xFF3A5A40)) }

            if (showRestoreConfirm) {
                var forceFreshRestore by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showRestoreConfirm = false },
                    title = { Text("Restore from Dropbox?") },
                    text = {
                        Column {
                            Text("Restoring \"${selectedRestoreFileName ?: BackupHelper.defaultBackupFileNameForGarden(context, effectiveGardenId(context))}\" adds/updates plants, irrigation paths, watering history, sun zones, growth photos, care history, and all settings from it. Existing entries with matching IDs will be overwritten. This can't be undone.")
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { forceFreshRestore = !forceFreshRestore }
                            ) {
                                Checkbox(checked = forceFreshRestore, onCheckedChange = { forceFreshRestore = it })
                                Text("Make this the current version everywhere (overrides other devices' more recent edits too)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestoreConfirm = false
                            val fileName = selectedRestoreFileName ?: BackupHelper.defaultBackupFileNameForGarden(context, effectiveGardenId(context))
                            scope.launch {
                                restoreWorking = true; backupResultText = null
                                val result = BackupHelper.restoreBackup(context, viewModel, pathViewModel, wateringViewModel, fileName, forceFreshRestore)
                                restoreWorking = false; backupResultText = result.message
                            }
                        }) { Text("Restore") }
                    },
                    dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
                )
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("Export backup to device", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Saves the same backup to a folder you choose on this device — or a synced folder like Google Drive/OneDrive — no Dropbox connection needed. Uses the system file picker, so no extra app permissions are required.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))

            var localBackupFolder by remember { mutableStateOf(getLocalBackupFolderUri(context)) }
            var localBackupWorking by remember { mutableStateOf(false) }
            var localBackupResultText by remember { mutableStateOf<String?>(null) }
            var showLocalRestoreConfirm by remember { mutableStateOf(false) }

            val backupFolderPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    setLocalBackupFolderUri(context, uri)
                    localBackupFolder = uri
                }
            }

            OutlinedButton(onClick = { backupFolderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (localBackupFolder != null) "Change backup folder" else "Choose backup folder")
            }

            if (localBackupFolder != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            localBackupWorking = true; localBackupResultText = null
                            val result = BackupHelper.createLocalBackup(context, plants, irrigationPaths, irrigationEvents, localBackupFolder!!)
                            localBackupWorking = false; localBackupResultText = result.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !localBackupWorking
                ) { Text(if (localBackupWorking) "Backing up…" else "Export backup now") }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showLocalRestoreConfirm = true },
                    modifier = Modifier.fillMaxWidth(), enabled = !localBackupWorking && canEditActiveGarden
                ) { Text("Restore from this folder") }
            }

            localBackupResultText?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.sp, color = Color(0xFF3A5A40)) }

            if (showLocalRestoreConfirm) {
                var forceFreshLocalRestore by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showLocalRestoreConfirm = false },
                    title = { Text("Restore from device backup?") },
                    text = {
                        Column {
                            Text("This adds/updates plants, irrigation paths, watering history, sun zones, growth photos, care history, and all settings from the backup in this folder. Existing entries with matching IDs will be overwritten. This can't be undone.")
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { forceFreshLocalRestore = !forceFreshLocalRestore }
                            ) {
                                Checkbox(checked = forceFreshLocalRestore, onCheckedChange = { forceFreshLocalRestore = it })
                                Text("Make this the current version everywhere (overrides other devices' more recent edits too)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showLocalRestoreConfirm = false
                            scope.launch {
                                localBackupWorking = true; localBackupResultText = null
                                val result = BackupHelper.restoreLocalBackup(context, viewModel, pathViewModel, wateringViewModel, localBackupFolder!!, forceFreshLocalRestore)
                                localBackupWorking = false; localBackupResultText = result.message
                            }
                        }) { Text("Restore") }
                    },
                    dismissButton = { TextButton(onClick = { showLocalRestoreConfirm = false }) { Text("Cancel") } }
                )
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text("Reset all data", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFFB23B3B))
            Spacer(Modifier.height(6.dp))
            Text("Clears every plant in this app. Cannot be undone.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showResetDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = isGardenOwner,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB23B3B))
            ) { Text("Reset garden") }
            if (!isGardenOwner) { Spacer(Modifier.height(6.dp)); Text("Only this garden's owner can reset it.", fontSize = 11.sp, color = Color.Gray) }
        }
        }

        if (FeatureVisibility.shouldShow(context, Feature.GARDEN_SHARING)) {
        ExpandableSection(title = "Sync with other devices") {
            Text(
                "Keep this garden's plants and care history in sync between this phone, another phone sharing this garden, and the desktop app. Enter this device's Install ID (below) into the desktop app once to link them, then use \"Sync plants & care history\" on either device whenever you want to pull in the other's changes. (This is separate from the Irrigation section's \"Sync watering history\" button, which only pulls Tuya/Rachio watering events.)",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(14.dp)); HorizontalDivider(); Spacer(Modifier.height(14.dp))

            GardenSharingControls(context, scope, snackbarHostState)

            Spacer(Modifier.height(14.dp)); HorizontalDivider(); Spacer(Modifier.height(14.dp))

            Text(
                "Only relevant if you also use the desktop app — enter this device's Install ID there once to link them. Not needed for phone-to-phone garden sharing above.",
                fontSize = 11.sp, color = Color.Gray
            )
            Spacer(Modifier.height(6.dp))
            val syncInstallId = remember { getOrCreateInstallId(context) }
            Text(
                "Install ID: $syncInstallId (tap to copy)",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Install ID", syncInstallId))
                    scope.launch { snackbarHostState.showSnackbar("Install ID copied") }
                }
            )
            Spacer(Modifier.height(10.dp))
            var syncing by remember { mutableStateOf(false) }
            var lastSyncedAt by remember { mutableStateOf(GardenSyncStore.getLastSyncedAt(context)) }
            if (lastSyncedAt > 0) {
                Text(
                    "Last synced: ${SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(lastSyncedAt))}",
                    fontSize = 11.sp, color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    syncing = true
                    scope.launch {
                        when (val result = GardenSyncClient.sync(context, syncInstallId, effectiveGardenId(context))) {
                            is GardenSyncResult.Success -> {
                                lastSyncedAt = GardenSyncStore.getLastSyncedAt(context)
                                val note = if (result.permission == "read") " (view-only)" else ""
                                snackbarHostState.showSnackbar("Synced — ${result.plantCount} plant(s) up to date$note")
                            }
                            GardenSyncResult.NetworkError -> snackbarHostState.showSnackbar("Couldn't reach the sync server — check your connection.")
                            GardenSyncResult.ServerError -> snackbarHostState.showSnackbar("Sync failed — try again shortly.")
                            GardenSyncResult.NotAuthorized -> snackbarHostState.showSnackbar("This device no longer has access to that garden.")
                        }
                        syncing = false
                    }
                },
                enabled = !syncing && canEditActiveGarden,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (syncing) "Syncing…" else "Sync plants & care history") }
            if (!canEditActiveGarden) { Spacer(Modifier.height(6.dp)); Text("You have view-only access to this garden — it stays up to date automatically.", fontSize = 11.sp, color = Color.Gray) }
        }
        }

        ExpandableSection(title = "Support Sage Garden") {
            Text(
                "Sage Garden is free, with no ads. If it's useful to you, a small tip helps cover running costs (Sage AI, hosting).",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(SUPPORT_LINK_URL))
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        scope.launch { snackbarHostState.showSnackbar("Couldn't open the link.") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("☕ Buy me a coffee") }
        }

        ExpandableSection(title = "Contact & feedback") {
            Text(
                "Found a bug, or have an idea for the app? We'd love to hear from you at gardenwizardry685@gmail.com.",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val emailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:gardenwizardry685@gmail.com")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Sage Garden feedback")
                    }
                    try {
                        context.startActivity(emailIntent)
                    } catch (_: Exception) {
                        scope.launch { snackbarHostState.showSnackbar("No email app found — you can reach us at gardenwizardry685@gmail.com") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("📧 Email us") }
            Spacer(Modifier.height(10.dp))
            val installId = remember { getOrCreateInstallId(context) }
            Text(
                "Install ID: $installId (tap to copy — quote this if you contact support)",
                fontSize = 10.sp, color = Color.Gray,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Install ID", installId))
                    scope.launch { snackbarHostState.showSnackbar("Install ID copied") }
                }
            )
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset your garden?") },
            text = { Text("This will permanently delete every plant you've added. This can't be undone.") },
            confirmButton = { TextButton(onClick = { showResetDialog = false; viewModel.resetAll(); scope.launch { snackbarHostState.showSnackbar("Garden reset.") } }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
        )
    }
    importResultDialog?.let { outcome ->
        AlertDialog(
            onDismissRequest = { importResultDialog = null },
            title = { Text(outcome.title) },
            text = { Text(outcome.message) },
            confirmButton = { TextButton(onClick = { importResultDialog = null }) { Text("OK") } }
        )
    }
}

// ============================================================================
// DROPBOX FOLDER ENTRY
// ============================================================================

data class DropboxFolderEntry(val name: String, val path: String)

/** Lists subfolders at a given path. Empty string "" = root. */
suspend fun listDropboxFolders(context: Context, path: String): Result<List<DropboxFolderEntry>> {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext Result.failure(Exception("Not connected to Dropbox"))
            val result = client.files().listFolder(path)
            val folders = result.entries
                .filterIsInstance<com.dropbox.core.v2.files.FolderMetadata>()
                .map { DropboxFolderEntry(it.name, it.pathLower ?: "") }
                .sortedBy { it.name.lowercase() }
            Result.success(folders)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Counts image files at a given path — used by "Test connection". */
suspend fun countDropboxImages(context: Context, path: String): Result<Int> {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext Result.failure(Exception("Not connected to Dropbox"))
            val result = client.files().listFolder(path)
            val imageCount = result.entries.count { entry ->
                entry is com.dropbox.core.v2.files.FileMetadata &&
                        entry.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
            }
            Result.success(imageCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ============================================================================
// PHOTO PICKER DIALOG
// ============================================================================
sealed class DropboxEntry {
    data class Folder(val name: String, val path: String) : DropboxEntry()
    data class Image(val name: String, val path: String, val clientModified: Long?) : DropboxEntry()
    data class File(val name: String, val path: String) : DropboxEntry()
}

private val DROPBOX_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

/** [fileExtensions] controls which non-folder entries show at all (case-insensitive, no dot) — an
 * image-type extension always becomes [DropboxEntry.Image] regardless of what's requested (matching
 * every existing image-picker call site's behavior unchanged), anything else requested becomes a
 * generic [DropboxEntry.File] (used by the CSV picker). */
suspend fun listDropboxEntries(
    context: Context, path: String, fileExtensions: Set<String> = DROPBOX_IMAGE_EXTENSIONS
): Result<List<DropboxEntry>> = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext Result.failure(Exception("Not connected to Dropbox"))
        val entries = client.files().listFolder(path).entries.mapNotNull { entry ->
            when (entry) {
                is com.dropbox.core.v2.files.FolderMetadata -> DropboxEntry.Folder(entry.name, entry.pathLower ?: "")
                is com.dropbox.core.v2.files.FileMetadata -> {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in DROPBOX_IMAGE_EXTENSIONS && ext in fileExtensions ->
                            DropboxEntry.Image(entry.name, entry.pathLower ?: "", entry.clientModified.time)
                        ext in fileExtensions -> DropboxEntry.File(entry.name, entry.pathLower ?: "")
                        else -> null
                    }
                }
                else -> null
            }
        }.sortedWith(compareBy({ it !is DropboxEntry.Folder }, {
            when (it) { is DropboxEntry.Folder -> it.name.lowercase(); is DropboxEntry.Image -> it.name.lowercase(); is DropboxEntry.File -> it.name.lowercase() }
        }))
        Result.success(entries)
    } catch (e: Exception) { Result.failure(e) }
}

suspend fun getDropboxDirectLink(context: Context, filePath: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: run {
            android.util.Log.w("DropboxLink", "getDropboxDirectLink: not connected to Dropbox")
            return@withContext null
        }
        // Uses the same retrying create-or-lookup as a freshly uploaded photo (fetchOrCreateSharedLink)
        // — an existing file being picked shouldn't usually hit the same "link not visible yet" lag a
        // brand-new upload can, but there's no reason this path should be less resilient than that one.
        val link = fetchOrCreateSharedLink(client, filePath)
        if (link == null) android.util.Log.w("DropboxLink", "getDropboxDirectLink: no link resolved for path='$filePath'")
        link?.let { toDirectDropboxLink(it) }
    } catch (e: Exception) {
        android.util.Log.w("DropboxLink", "getDropboxDirectLink threw for path='$filePath'", e)
        null
    }
}

@Composable
fun DropboxImagePickerDialog(
    context: Context, onDismiss: () -> Unit, onImageSelected: (String, Long?) -> Unit,
    initialPath: String = "", onPathChanged: ((String) -> Unit)? = null
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var currentLabel by remember { mutableStateOf(initialPath.trim('/').substringAfterLast("/").ifBlank { "Dropbox (root)" }) }
    var entries by remember { mutableStateOf<List<DropboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Reconstructs breadcrumbs for an initialPath so "‹ Back" still works from a remembered
    // starting folder — segment names are just the path's own segments (Dropbox doesn't give us
    // the real display name without an extra lookup per level, but the path segment reads fine).
    val pathStack = remember {
        val stack = mutableStateListOf<Pair<String, String>>()
        val segments = initialPath.trim('/').split("/").filter { it.isNotBlank() }
        if (segments.isNotEmpty()) {
            stack.add("" to "Dropbox (root)")
            var acc = ""
            for (i in 0 until segments.size - 1) {
                acc = "$acc/${segments[i]}"
                stack.add(acc to segments[i])
            }
        }
        stack
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        onPathChanged?.invoke(currentPath)
        loading = true; error = null
        listDropboxEntries(context, currentPath).onSuccess { entries = it }.onFailure { error = it.message }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = {
                            val prev = pathStack.removeAt(pathStack.size - 1)
                            currentPath = prev.first; currentLabel = prev.second
                        }) { Text("‹ Back") }
                    }
                }
                Text(currentLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading || resolving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        error != null -> Text("Error: $error", color = Color(0xFFB23B3B), fontSize = 13.sp)
                        entries.isEmpty() -> Text("Nothing here.", color = Color.Gray, fontSize = 13.sp)
                        else -> LazyColumn {
                            items(entries) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        when (entry) {
                                            is DropboxEntry.Folder -> {
                                                pathStack.add(currentPath to currentLabel)
                                                currentPath = entry.path; currentLabel = entry.name
                                            }
                                            is DropboxEntry.Image -> scope.launch {
                                                resolving = true; error = null
                                                val link = getDropboxDirectLink(context, entry.path)
                                                resolving = false
                                                if (link != null) {
                                                    onImageSelected(link, entry.clientModified); onDismiss()
                                                } else {
                                                    error = "Couldn't get a link for that photo — check its sharing settings in Dropbox, or try a different file."
                                                }
                                            }
                                            is DropboxEntry.File -> {}
                                        }
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (entry is DropboxEntry.Folder) "📁" else "🖼️", fontSize = 18.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        when (entry) { is DropboxEntry.Folder -> entry.name; is DropboxEntry.Image -> entry.name; is DropboxEntry.File -> entry.name },
                                        fontSize = 14.sp, modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

/** Downloads a Dropbox text file (e.g. a CSV) and returns its content, or null on any failure. */
suspend fun downloadDropboxTextFile(context: Context, path: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext null
        val out = java.io.ByteArrayOutputStream()
        client.files().download(path).download(out)
        out.toString("UTF-8")
    } catch (_: Exception) {
        null
    }
}

/** Uploads (overwriting any existing file of the same name) a plain-text file to a Dropbox folder — used for CSV export. */
suspend fun uploadTextFileToDropbox(context: Context, folderPath: String, fileName: String, content: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext false
        val filePath = "$folderPath/$fileName".replace("//", "/")
        client.files().uploadBuilder(filePath).withMode(WriteMode.OVERWRITE).uploadAndFinish(content.toByteArray().inputStream())
        true
    } catch (_: Exception) {
        false
    }
}

// ============================================================================
// DROPBOX CSV PICKER DIALOG
// ============================================================================

/** Browses Dropbox for a .csv file (folders navigable same as the image/folder pickers) and downloads
 * its text content on selection — used by "Choose CSV from Dropbox", analogous to
 * DropboxImagePickerDialog but returning file content instead of a shareable link, since CSV import
 * needs the actual bytes, not a URL. */
@Composable
fun DropboxCsvPickerDialog(context: Context, onDismiss: () -> Unit, onFileSelected: (String) -> Unit) {
    var currentPath by remember { mutableStateOf("") }
    var currentLabel by remember { mutableStateOf("Dropbox (root)") }
    var entries by remember { mutableStateOf<List<DropboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pathStack = remember { mutableStateListOf<Pair<String, String>>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        loading = true; error = null
        listDropboxEntries(context, currentPath, fileExtensions = setOf("csv")).onSuccess { entries = it }.onFailure { error = it.message }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = {
                            val prev = pathStack.removeAt(pathStack.size - 1)
                            currentPath = prev.first; currentLabel = prev.second
                        }) { Text("‹ Back") }
                    }
                }
                Text(currentLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading || resolving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        error != null -> Text("Error: $error", color = Color(0xFFB23B3B), fontSize = 13.sp)
                        entries.isEmpty() -> Text("No CSV files here.", color = Color.Gray, fontSize = 13.sp)
                        else -> LazyColumn {
                            items(entries) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        when (entry) {
                                            is DropboxEntry.Folder -> {
                                                pathStack.add(currentPath to currentLabel)
                                                currentPath = entry.path; currentLabel = entry.name
                                            }
                                            is DropboxEntry.File -> scope.launch {
                                                resolving = true; error = null
                                                val content = downloadDropboxTextFile(context, entry.path)
                                                resolving = false
                                                if (content != null) {
                                                    onFileSelected(content); onDismiss()
                                                } else {
                                                    error = "Couldn't download that file — try again."
                                                }
                                            }
                                            is DropboxEntry.Image -> {}
                                        }
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (entry is DropboxEntry.Folder) "📁" else "📄", fontSize = 18.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        when (entry) { is DropboxEntry.Folder -> entry.name; is DropboxEntry.Image -> entry.name; is DropboxEntry.File -> entry.name },
                                        fontSize = 14.sp, modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

// ============================================================================
// DROPBOX FOLDER PICKER DIALOG
// ============================================================================

@Composable
fun DropboxFolderPickerDialog(
    context: Context,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    var currentLabel by remember { mutableStateOf("Dropbox (root)") }
    var folders by remember { mutableStateOf<List<DropboxFolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val pathStack = remember { mutableStateListOf<Pair<String, String>>() } // path, label

    suspend fun load(path: String) {
        loading = true
        error = null
        val result = listDropboxFolders(context, path)
        loading = false
        result.onSuccess { folders = it }
        result.onFailure { error = it.message ?: "Couldn't load this folder" }
    }

    LaunchedEffect(currentPath) { load(currentPath) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = {
                            val previous = pathStack.removeAt(pathStack.size - 1)
                            currentPath = previous.first
                            currentLabel = previous.second
                        }) { Text("‹ Back") }
                    }
                    Spacer(Modifier.weight(1f))
                }
                Text(currentLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        error != null -> Text("Error: $error", color = Color(0xFFB23B3B), fontSize = 13.sp)
                        folders.isEmpty() -> Text("No subfolders here.", color = Color.Gray, fontSize = 13.sp)
                        else -> LazyColumn {
                            items(folders) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pathStack.add(currentPath to currentLabel)
                                            currentPath = folder.path
                                            currentLabel = folder.name
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📁", fontSize = 18.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(folder.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { onFolderSelected(currentPath); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Use this folder") }
                }
            }
        }
    }
}

val CSV_HEADERS = listOf(
    "Plant ID", "Plant", "Amount", "Scientific name", "Location", "Date planted", "Source",
    "Sun", "Soil", "Soil pH", "Category", "Water", "Frost", "Native/Exotic", "Pollinator-Friendly", "Notes",
    "Latitude", "Longitude", "Watering System"
)

fun generateNextPlantId(existingPlants: List<PlantEntity>, prefix: String = "P"): String =
    nextPlantId(existingPlants.map { it.id }, prefix)

/** Callers that already have a device-wide id set on hand (e.g. CSV import, which needs to check
 * every garden's ids for uniqueness while separately tracking active-garden plants for update
 * matching) can call this directly instead of building a throwaway PlantEntity list. [prefix]
 * should be this garden's own letter from [plantIdPrefixForGarden] — distinct per-garden prefixes
 * (rather than every garden using "P") mean two gardens' auto-generated ids can never collide in
 * the first place, on top of the existing device-wide uniqueness scan as a backstop. */
fun nextPlantId(existingIds: Collection<String>, prefix: String = "P"): String {
    val maxNum = existingIds.mapNotNull { id ->
        Regex("^${Regex.escape(prefix)}(\\d+)$").find(id.trim())?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull() ?: 0
    return "$prefix%04d".format(maxNum + 1)
}

// Letters available to additional (non-default) gardens for their plant-id prefix — "P" is
// reserved for the device's own original default garden (gardenId == installId) so existing
// ids/backups/exports need no migration. Extremely unlikely to ever be exhausted for a personal
// gardening app, but falls back to two-letter codes ("QA", "QB", ...) rather than erroring if it
// somehow is.
private const val PLANT_ID_PREFIX_LETTERS = "QRSTUVWXYZ"

private fun plantIdPrefixAtIndex(index: Int): String {
    if (index < PLANT_ID_PREFIX_LETTERS.length) return PLANT_ID_PREFIX_LETTERS[index].toString()
    val overflow = index - PLANT_ID_PREFIX_LETTERS.length
    val first = PLANT_ID_PREFIX_LETTERS[(overflow / 26) % PLANT_ID_PREFIX_LETTERS.length]
    val second = 'A' + (overflow % 26)
    return "$first$second"
}

/**
 * The plant-id letter prefix ("P", "Q", "R", ...) this garden uses on THIS device — stable once
 * assigned (persisted), so a garden's plants always keep the same prefix rather than shifting
 * around. The device's own original default garden always gets "P"; any other garden (created or
 * joined) gets the next never-yet-used letter the first time this is called for it. Two different
 * devices sharing the same garden may assign it different letters locally — that's fine, since the
 * point is only to guarantee THIS device's own locally-generated ids never collide with each
 * other, not to keep prefixes consistent across devices. See feedback_plant_id_cross_garden_collision.
 */
fun plantIdPrefixForGarden(context: Context, gardenId: String): String {
    if (gardenId.isBlank() || gardenId == getOrCreateInstallId(context)) return "P"
    val prefs = gardenPrefs(context)
    val key = "plant_id_prefix.$gardenId"
    prefs.getString(key, null)?.let { return it }
    val nextIndex = prefs.getInt("plant_id_prefix_next_index", 0)
    val assigned = plantIdPrefixAtIndex(nextIndex)
    prefs.edit().putString(key, assigned).putInt("plant_id_prefix_next_index", nextIndex + 1).apply()
    return assigned
}

fun detectCsvDelimiter(line: String): Char {
    val commaCount = line.count { it == ',' }
    val tabCount = line.count { it == '\t' }
    return if (tabCount > commaCount) '\t' else ','
}

fun parseCsvLine(line: String, delimiter: Char = ','): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
            c == '"' -> inQuotes = !inQuotes
            c == delimiter && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
            else -> current.append(c)
        }
        i++
    }
    result.add(current.toString())
    return result
}