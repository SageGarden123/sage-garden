@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.compose.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var bulkApplyToZone by remember { mutableStateOf(false) }
    var bulkApplyToSystem by remember { mutableStateOf(false) }

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
        // Not plant.gardenId directly — a brand-new plant's still carries the blank it was built
        // with (PlantViewModel.saveSync stamps the real one internally but doesn't hand it back);
        // this mirrors that exact stamping so logCareSync gets the garden the row actually landed in.
        val savedGardenId = plant.gardenId.ifBlank { effectiveGardenId(context) }
        if (lastWateredDate != originalLastWateredDate) {
            plant.lastWateredDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "watering", it) }
        }
        if (lastFertilisedDate != originalLastFertilisedDate) {
            plant.lastFertilisedDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "fertilise", it) }
        }
        if (lastPrunedDate != originalLastPrunedDate) {
            plant.lastPrunedDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "prune", it) }
        }
        if (lastFedDate != originalLastFedDate) {
            plant.lastFedDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "feed", it) }
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
        val customMapExists = GardenSettings.active(context).customMapUri != null
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
    // hasWriteAccessToGarden), but leaving the fields editable and a Save button visible would
    // make it look like their edits took effect when they're silently dropped on next sync.
    // Checked against the PLANT'S OWN garden (via the `gardenId` state populated above once
    // `existing` loads), not whichever garden is currently active — a plant opened via a widget/
    // notification deep link can belong to a garden other than the active one (MainActivity always
    // cold-starts back on this device's own default garden), and checking the active garden's
    // permission there answered the wrong question: an owner's own garden is always writable, so
    // editing a *view-only* shared plant this way looked fully editable and "saved" successfully
    // even though the write would be silently discarded server-side. A brand-new plant (plantId ==
    // null) has no owning garden yet, so it still defers to the active garden it'll be stamped with.
    val canEdit = remember(ActiveGardenState.activeGardenId, gardenId) {
        if (plantId == null) hasWriteAccessToActiveGarden(context) else hasWriteAccessToGarden(context, gardenId)
    }

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
            (GardenSettings.active(context).getOrSeedLocations(allPlants) + allPlants.map { it.location }.filter { it.isNotBlank() })
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
            Text("Overrides the frequency above during summer/winter. Leave a season blank to fall back to the frequency above for that season — not to skip it.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(
                "There's no dedicated \"skip this season\" option. Entering 0 does NOT skip watering — it means water every 0 days, so the plant will constantly show as overdue. Leaving the field blank doesn't skip it either — it just uses the frequency above. To effectively skip a season, enter a large number of days here (e.g. 365) so it won't come due again until the season is over.",
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
        if (displayId.isNotBlank() && canEdit && FeatureVisibility.shouldShow(context, Feature.PLACE_ON_MAP)) {
            val hasReal = lat.toDoubleOrNull() != null && lng.toDoubleOrNull() != null
            val hasCustom = mapX != null && mapY != null
            val customMapExists = remember { GardenSettings.active(context).customMapUri != null }
            if (customMapExists) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scope.launch { saveThenNavigateToPlacement("place_custom/$displayId") } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (hasCustom) "📍 Change location on custom map" else "📍 Place on custom map") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { scope.launch { saveThenNavigateToPlacement("place_real/$displayId") } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (hasReal) "📍 Change location on real-world map" else "📍 Place on real-world map") }
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), readOnly = !canEdit)
        Spacer(Modifier.height(20.dp))

        if (displayId.isNotBlank() && FeatureVisibility.shouldShow(context, Feature.EXTRA_PHOTOS)) {
            ExtraPhotosSection(plantId = displayId, canEdit = canEdit, gardenId = gardenId.ifBlank { effectiveGardenId(context) })
            Spacer(Modifier.height(20.dp))
        }

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
                    // Not plant.gardenId directly — see saveThenNavigateToPlacement's identical comment.
                    val savedGardenId = plant.gardenId.ifBlank { effectiveGardenId(context) }
                    if (lastWateredDate != originalLastWateredDate) {
                        plant.lastWateredDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "watering", it) }
                    }
                    if (lastFertilisedDate != originalLastFertilisedDate) {
                        plant.lastFertilisedDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "fertilise", it) }
                    }
                    if (lastPrunedDate != originalLastPrunedDate) {
                        plant.lastPrunedDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "prune", it) }
                    }
                    if (lastFedDate != originalLastFedDate) {
                        plant.lastFedDate?.let { careLogViewModel.logCareSync(plant.id, savedGardenId, "feed", it) }
                    }

                    if (plant.lastWateredDate != null && location.isNotBlank() && lastWateredDate != originalLastWateredDate) {
                        bulkApplyToZone = false
                        bulkApplyToSystem = false
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
                    plantId?.let { viewModel.delete(gardenId, it) }
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
        val systemName = plant?.wateringSystem?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = {
                showBulkWaterPrompt = false
                plant?.let { checkPlacementPrompts(it) }
            },
            title = { Text("Apply this watering to other plants?") },
            text = {
                Column {
                    Text("Set \"last watered\" to $lastWateredDate for \"${plant?.name}\" — also apply it to other plants?")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Also apply to all plants in \"$location\"",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = bulkApplyToZone, onCheckedChange = { bulkApplyToZone = it })
                    }
                    if (systemName != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Also apply to all plants on \"$systemName\" (any zone)",
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = bulkApplyToSystem, onCheckedChange = { bulkApplyToSystem = it })
                        }
                    }
                    if (!bulkApplyToZone && !bulkApplyToSystem) {
                        Spacer(Modifier.height(8.dp))
                        Text("Neither toggle is on, so only \"${plant?.name}\" will be updated — same as \"Just this plant\".", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBulkWaterPrompt = false
                    val dateMillis = plant?.lastWateredDate
                    if (dateMillis != null && (bulkApplyToZone || bulkApplyToSystem)) {
                        scope.launch {
                            allPlants.filter { p ->
                                p.id != plant.id &&
                                    ((bulkApplyToZone && p.location == location) ||
                                        (bulkApplyToSystem && systemName != null && p.wateringSystem == systemName))
                            }.forEach { p ->
                                viewModel.saveSync(p.copy(lastWateredDate = dateMillis))
                                careLogViewModel.logCareSync(p.id, p.gardenId, "watering", dateMillis)
                            }
                            checkPlacementPrompts(plant)
                        }
                    } else {
                        plant?.let { checkPlacementPrompts(it) }
                    }
                }) { Text("Yes, apply") }
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
fun ExtraPhotosSection(plantId: String, canEdit: Boolean = true, gardenId: String = effectiveGardenId(LocalContext.current)) {
    val context = LocalContext.current
    val extraPhotoViewModel: ExtraPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val photos by remember(plantId, gardenId) { extraPhotoViewModel.getForPlant(plantId, gardenId) }.collectAsState()
    var pendingCameraUri by rememberSaveable(stateSaver = UriSaver) { mutableStateOf<Uri?>(null) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var uploadingPhotoId by remember { mutableStateOf<String?>(null) }
    var uploadFailedId by remember { mutableStateOf<String?>(null) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) extraPhotoViewModel.addPhoto(plantId, pendingCameraUri.toString(), gardenId = gardenId)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            extraPhotoViewModel.addPhoto(plantId, uri.toString(), gardenId = gardenId)
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
            val extraPhotoButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                contentPadding = extraPhotoButtonPadding,
                modifier = Modifier.weight(1f)
            ) { Text("📷 Camera", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, contentPadding = extraPhotoButtonPadding, modifier = Modifier.weight(1f)) { Text("🖼️ Gallery", fontSize = 12.sp, maxLines = 1) }
            if (DropboxAuthState.token != null) {
                OutlinedButton(onClick = { showDropboxPicker = true }, contentPadding = extraPhotoButtonPadding, modifier = Modifier.weight(1f)) { Text("☁️ Dropbox", fontSize = 12.sp, maxLines = 1) }
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
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { previewUri = Uri.parse(photo.uri) },
                                contentScale = ContentScale.Crop
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
                            var previewName by remember(photo.id) { mutableStateOf<String?>(null) }
                            LaunchedEffect(photo.id) {
                                previewExtraPhotoDropboxUploadName(context, plantId)?.let { previewName = it.removeSuffix(".jpg") }
                            }
                            TextButton(
                                onClick = {
                                    uploadingPhotoId = photo.id; uploadFailedId = null
                                    scope.launch {
                                        val link = uploadPhotoToDropboxAsExtraPhoto(context, Uri.parse(photo.uri), plantId)
                                        uploadingPhotoId = null
                                        if (link != null) extraPhotoViewModel.updateUri(photo, link) else uploadFailedId = photo.id
                                    }
                                },
                                enabled = uploadingPhotoId != photo.id
                            ) {
                                Text(
                                    if (uploadingPhotoId == photo.id) "Uploading…"
                                    else "☁️ Upload to Dropbox" + (previewName?.let { " as $it" } ?: ""),
                                    fontSize = 11.sp
                                )
                            }
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
            onImageSelected = { link, _ -> extraPhotoViewModel.addPhoto(plantId, link, gardenId = gardenId) })
    }

    if (previewUri != null) {
        var photoLoadFailed by remember(previewUri) { mutableStateOf(false) }
        Dialog(onDismissRequest = { previewUri = null }) {
            Box(modifier = Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (photoLoadFailed) {
                    Text(
                        "Couldn't load this photo — the link may no longer be valid (e.g. its Dropbox sharing permissions changed).",
                        color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(24.dp)
                    )
                } else {
                    AsyncImage(
                        model = previewUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        onError = { photoLoadFailed = true }
                    )
                }
            }
        }
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
