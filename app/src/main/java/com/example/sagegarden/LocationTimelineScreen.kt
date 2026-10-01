package com.example.sagegarden

import androidx.compose.ui.res.stringResource

import androidx.compose.material3.MaterialTheme

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationPhotoSlider(photos: List<LocationPhotoEntity>, modifier: Modifier = Modifier) {
    if (photos.size < 2) return
    val maxIndex = (photos.size - 1).toFloat()
    var position by remember(photos.size) { mutableStateOf(maxIndex) }
    val lowerIndex = position.toInt().coerceIn(0, photos.size - 1)
    val upperIndex = (lowerIndex + 1).coerceAtMost(photos.size - 1)
    val blend = (position - lowerIndex).coerceIn(0f, 1f)
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = Uri.parse(photos[lowerIndex].uri), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onError = { Log.e("LocationPhoto", "slider load failed for ${photos[lowerIndex].uri}", it.result.throwable) },
                onSuccess = { Log.d("LocationPhoto", "slider load OK for ${photos[lowerIndex].uri}") }
            )
            if (upperIndex != lowerIndex) {
                AsyncImage(
                    model = Uri.parse(photos[upperIndex].uri), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer(alpha = blend), contentScale = ContentScale.Crop,
                    onError = { Log.e("LocationPhoto", "slider load failed for ${photos[upperIndex].uri}", it.result.throwable) },
                    onSuccess = { Log.d("LocationPhoto", "slider load OK for ${photos[upperIndex].uri}") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(value = position, onValueChange = { position = it }, valueRange = 0f..maxIndex)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(sdf.format(Date(photos.first().takenAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val shownDate = if (blend < 0.5f) photos[lowerIndex].takenAt else photos[upperIndex].takenAt
            Text(sdf.format(Date(shownDate)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(sdf.format(Date(photos.last().takenAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LocationTimelineScreen(location: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val canEdit = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }
    val locationViewModel: LocationPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val gardenId = remember { effectiveGardenId(context) }
    val photos by remember(location) { locationViewModel.getForLocation(location, gardenId) }.collectAsState()
    val sorted = remember(photos) { photos.sortedBy { it.takenAt } }
    var pendingCameraUri by rememberSaveable(stateSaver = UriSaver) { mutableStateOf<Uri?>(null) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Uploads run one at a time in tap order (Mutex is first-come-first-served), and a tapped photo's
    // name is pinned until it's done — so tapping "_1" then "_2" in quick succession always gives _1
    // and _2. Previously they ran in parallel: if _2 finished first, _1 re-numbered itself past it.
    val uploadQueue = remember { Mutex() }
    val queuedUploadNames = remember { mutableStateMapOf<String, String>() }
    val uploadFailedIds = remember { mutableStateListOf<String>() }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) locationViewModel.addPhoto(location, pendingCameraUri.toString())
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
    }
    // Multi-select: progress photos are often added in a batch (a season's worth at once).
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            locationViewModel.addPhoto(location, uri.toString())
        }
    }

    // One Dropbox upload name per not-yet-uploaded photo, numbered oldest first and continuing from
    // the highest progress_<zone>_N already in Dropbox — so two pending photos preview _3 and _4
    // rather than both showing _3. Recomputed whenever a photo is added, uploaded or deleted.
    val localPhotos = remember(sorted) {
        sorted.filter { Uri.parse(it.uri).scheme.let { s -> s != "http" && s != "https" } }
    }
    var uploadNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // The zone's Dropbox folder only changes via the "Dropbox folder › Change" row; bumping this re-reads it.
    var zoneFolderVersion by remember { mutableStateOf(0) }
    var showFolderPicker by remember { mutableStateOf(false) }
    // Where uploads land (see uploadPhotoToDropboxAsProgressPhoto).
    val uploadFolderName = remember(zoneFolderVersion, location) {
        progressPhotoUploadFolder(context, location).trim('/').substringAfterLast('/').ifBlank { null }
    }
    LaunchedEffect(localPhotos.map { it.id }, DropboxAuthState.token, canEdit, uploadFolderName, queuedUploadNames.toMap()) {
        // Uploaded (now a Dropbox link) or deleted photos release their pin.
        val localIds = localPhotos.map { it.id }.toSet()
        queuedUploadNames.keys.filter { it !in localIds }.forEach { queuedUploadNames.remove(it) }
        // Queued photos keep their pinned names; everyone else is numbered around them.
        val unqueued = localPhotos.filter { it.id !in queuedUploadNames }
        val reserved = queuedUploadNames.values.mapNotNull { progressPhotoSuffixOf(it) }.toSet()
        uploadNames = if (!canEdit || DropboxAuthState.token == null || unqueued.isEmpty()) emptyMap()
        else previewProgressPhotoDropboxUploadNames(context, location, unqueued.size, reserved)
            ?.let { names -> unqueued.zip(names).associate { (photo, name) -> photo.id to name } } ?: emptyMap()
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.care_back)) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.zonephotos_progress_photos, location), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.zonephotos_for_the_best_before_after_comparison),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // Per-zone opt-out: people often only track a few zones, and only want reminders for those.
        val gardenSettings = remember(gardenId) { GardenSettings.of(context, gardenId) }
        val remindersOn = remember(gardenId) { gardenSettings.progressPhotoRemindersEnabled }
        var remindForZone by remember(gardenId, location) { mutableStateOf(location !in gardenSettings.progressPhotoMutedZones) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.zonephotos_remind_me), fontSize = 14.sp)
                Text(
                    stringResource(if (remindersOn) R.string.zonephotos_remind_me_desc else R.string.zonephotos_reminders_off_in_settings),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = remindForZone && remindersOn, enabled = remindersOn,
                onCheckedChange = { on ->
                    remindForZone = on
                    gardenSettings.progressPhotoMutedZones =
                        if (on) gardenSettings.progressPhotoMutedZones - location else gardenSettings.progressPhotoMutedZones + location
                }
            )
        }

        // The zone's Dropbox folder: where its uploads go and where its Dropbox photo picker opens.
        if (canEdit && DropboxAuthState.token != null) {
            val folderDisplay = remember(zoneFolderVersion, location) {
                getProgressPhotoDropboxFolderDisplay(context, location)?.takeIf { getProgressPhotoDropboxFolder(context, location)?.isNotBlank() == true }
            }
            val mainFolderDisplay = remember { getDropboxPhotoFolderPath(context)?.trim('/')?.ifBlank { null } ?: "Dropbox root" }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.zonephotos_dropbox_folder), fontSize = 14.sp)
                    Text(
                        folderDisplay ?: stringResource(R.string.zonephotos_dropbox_folder_main, mainFolderDisplay),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showFolderPicker = true }) { Text(stringResource(R.string.zonephotos_change)) }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (sorted.size >= 2) {
            LocationPhotoSlider(photos = sorted)
            Spacer(Modifier.height(20.dp))
        } else {
            Text(stringResource(R.string.zonephotos_add_at_least_2_photos_of), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
        }

        if (canEdit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding
            ) { Text(stringResource(R.string.form_camera), fontSize = 12.sp) }
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text(stringResource(R.string.form_gallery), fontSize = 12.sp) }
            if (DropboxAuthState.token != null) {
                OutlinedButton(onClick = { showDropboxPicker = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text(stringResource(R.string.form_dropbox), fontSize = 12.sp) }
            }
        }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.growth_all_photos, sorted.size), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
        val newestFirst = sorted.reversed()
        newestFirst.forEachIndexed { index, photo ->
            // Same-day photos can be reordered (only the date is shown, so their real order is
            // otherwise arbitrary). Newest is on top, so "up" means later in the sequence.
            val day = sdf.format(Date(photo.takenAt))
            val above = newestFirst.getOrNull(index - 1)?.takeIf { sdf.format(Date(it.takenAt)) == day }
            val below = newestFirst.getOrNull(index + 1)?.takeIf { sdf.format(Date(it.takenAt)) == day }
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = Uri.parse(photo.uri), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop,
                            onError = { Log.e("LocationPhoto", "list thumbnail load failed for ${photo.uri}", it.result.throwable) },
                            onSuccess = { Log.d("LocationPhoto", "list thumbnail load OK for ${photo.uri}") }
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(day, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (canEdit && (above != null || below != null)) {
                            IconButton(onClick = { above?.let { locationViewModel.swapOrder(photo, it, moveLater = true) } }, enabled = above != null) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.zonephotos_move_up))
                            }
                            IconButton(onClick = { below?.let { locationViewModel.swapOrder(photo, it, moveLater = false) } }, enabled = below != null) {
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.zonephotos_move_down))
                            }
                        }
                        if (canEdit) {
                            TextButton(onClick = { locationViewModel.delete(photo.id) }) { Text(stringResource(R.string.care_delete), fontSize = 12.sp) }
                        }
                    }
                    val localUriScheme = Uri.parse(photo.uri).scheme
                    if (canEdit && DropboxAuthState.token != null && localUriScheme != "http" && localUriScheme != "https") {
                        val queuedName = queuedUploadNames[photo.id]
                        val previewName = (queuedName ?: uploadNames[photo.id])?.removeSuffix(".jpg")
                        TextButton(
                            onClick = {
                                val name = uploadNames[photo.id] ?: return@TextButton
                                queuedUploadNames[photo.id] = name
                                uploadFailedIds.remove(photo.id)
                                scope.launch {
                                    val link = uploadQueue.withLock {
                                        uploadPhotoToDropboxAsProgressPhoto(context, Uri.parse(photo.uri), location, preferredName = name)
                                    }
                                    // On success it stays pinned until the row actually turns into a Dropbox
                                    // link (cleared below) — unpinning now left a moment where the button
                                    // was live again and a second tap could upload it twice.
                                    if (link != null) locationViewModel.updateUri(photo, link)
                                    else { uploadFailedIds.add(photo.id); queuedUploadNames.remove(photo.id) }
                                }
                            },
                            enabled = queuedName == null && uploadNames[photo.id] != null
                        ) {
                            Text(
                                if (queuedName != null) stringResource(R.string.form_uploading) + " " + queuedName.removeSuffix(".jpg")
                                else stringResource(R.string.form_upload_to_dropbox) + (previewName?.let { " as $it" } ?: "") +
                                    (uploadFolderName?.let { " in $it" } ?: ""),
                                fontSize = 12.sp
                            )
                        }
                        if (photo.id in uploadFailedIds) {
                            Text(stringResource(R.string.form_upload_failed_try_again), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    if (showDropboxPicker) {
        DropboxImagePickerDialog(
            context, onDismiss = { showDropboxPicker = false },
            onImageSelected = { link, clientModified -> locationViewModel.addPhoto(location, link, takenAtOverride = clientModified) },
            onImagesSelected = { picked -> picked.forEach { (link, clientModified) -> locationViewModel.addPhoto(location, link, takenAtOverride = clientModified) } },
            // Opens in the zone's folder; browsing elsewhere no longer changes it (see the Change row).
            initialPath = remember(location, zoneFolderVersion) { progressPhotoUploadFolder(context, location) }
        )
    }

    if (showFolderPicker) {
        DropboxFolderPickerDialog(
            context, onDismiss = { showFolderPicker = false },
            onFolderSelected = {},
            initialPath = remember(location, zoneFolderVersion) { progressPhotoUploadFolder(context, location) },
            initialDisplayPath = remember(location, zoneFolderVersion) { getProgressPhotoDropboxFolderDisplay(context, location) ?: "" },
            onFolderSelectedWithDisplayPath = { path, displayPath ->
                setProgressPhotoDropboxFolder(context, location, path, displayPath)
                zoneFolderVersion++
            }
        )
    }
}
