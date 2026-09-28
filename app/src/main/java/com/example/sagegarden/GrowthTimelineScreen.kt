package com.example.sagegarden

import androidx.compose.ui.res.stringResource

import androidx.compose.material3.MaterialTheme

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GrowthPhotoSlider(photos: List<GrowthPhotoEntity>, modifier: Modifier = Modifier) {
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
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            if (upperIndex != lowerIndex) {
                AsyncImage(
                    model = Uri.parse(photos[upperIndex].uri), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer(alpha = blend), contentScale = ContentScale.Crop
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
fun GrowthTimelineScreen(plantId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val growthViewModel: GrowthPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    // Resolved from the plant's own record, not effectiveGardenId(context) — this screen can be
    // reached (via FormScreen's "View growth timeline") for a plant belonging to a garden other than
    // whichever one is active in the UI, e.g. opened from a widget/notification deep link. Using the
    // active garden's id here would query/write growth photos under the WRONG gardenId, silently
    // showing none of the plant's real photos and mis-scoping anything newly added.
    var gardenId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(plantId) {
        gardenId = resolvePlantById(context, plantId)?.gardenId ?: effectiveGardenId(context)
    }
    val canEdit = remember(ActiveGardenState.activeGardenId, gardenId) { gardenId?.let { hasWriteAccessToGarden(context, it) } ?: false }
    val photos by remember(plantId, gardenId) { growthViewModel.getForPlant(plantId, gardenId ?: "") }.collectAsState()
    val sorted = remember(photos) { photos.sortedBy { it.takenAt } }
    var pendingCameraUri by rememberSaveable(stateSaver = UriSaver) { mutableStateOf<Uri?>(null) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var uploadingPhotoId by remember { mutableStateOf<String?>(null) }
    var uploadFailedId by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) gardenId?.let { growthViewModel.addPhoto(plantId, pendingCameraUri.toString(), gardenId = it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            gardenId?.let { growthViewModel.addPhoto(plantId, uri.toString(), gardenId = it) }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.care_back)) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.growth_growth_timeline), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(14.dp))

        if (sorted.size >= 2) {
            GrowthPhotoSlider(photos = sorted)
            Spacer(Modifier.height(20.dp))
        } else {
            Text(stringResource(R.string.growth_add_at_least_2_growth_photos), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
        sorted.reversed().forEach { photo ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = Uri.parse(photo.uri), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Text(sdf.format(Date(photo.takenAt)), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (canEdit) {
                            TextButton(onClick = { growthViewModel.delete(photo.id) }) { Text(stringResource(R.string.care_delete), fontSize = 12.sp) }
                        }
                    }
                    val localUriScheme = Uri.parse(photo.uri).scheme
                    if (canEdit && DropboxAuthState.token != null && localUriScheme != "http" && localUriScheme != "https") {
                        var previewName by remember(photo.id) { mutableStateOf<String?>(null) }
                        LaunchedEffect(photo.id) {
                            previewGrowthPhotoDropboxUploadName(context, plantId)?.let { previewName = it.removeSuffix(".jpg") }
                        }
                        TextButton(
                            onClick = {
                                uploadingPhotoId = photo.id; uploadFailedId = null
                                scope.launch {
                                    val link = uploadPhotoToDropboxAsGrowthPhoto(context, Uri.parse(photo.uri), plantId)
                                    uploadingPhotoId = null
                                    if (link != null) growthViewModel.updateUri(photo, link) else uploadFailedId = photo.id
                                }
                            },
                            enabled = uploadingPhotoId != photo.id
                        ) {
                            Text(
                                if (uploadingPhotoId == photo.id) stringResource(R.string.form_uploading)
                                else stringResource(R.string.form_upload_to_dropbox) + (previewName?.let { " as $it" } ?: ""),
                                fontSize = 12.sp
                            )
                        }
                        if (uploadFailedId == photo.id) {
                            Text(stringResource(R.string.form_upload_failed_try_again), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    if (showDropboxPicker) {
        DropboxImagePickerDialog(context, onDismiss = { showDropboxPicker = false },
            onImageSelected = { link, clientModified -> gardenId?.let { growthViewModel.addPhoto(plantId, link, takenAtOverride = clientModified, gardenId = it) } })
    }
}