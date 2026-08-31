package com.example.sagegarden

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
            Text(sdf.format(Date(photos.first().takenAt)), fontSize = 11.sp, color = Color.Gray)
            val shownDate = if (blend < 0.5f) photos[lowerIndex].takenAt else photos[upperIndex].takenAt
            Text(sdf.format(Date(shownDate)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3A5A40))
            Text(sdf.format(Date(photos.last().takenAt)), fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
fun GrowthTimelineScreen(plantId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val canEdit = remember(ActiveGardenState.activeGardenId) { hasWriteAccessToActiveGarden(context) }
    val growthViewModel: GrowthPhotoViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val gardenId = remember { effectiveGardenId(context) }
    val photos by remember(plantId) { growthViewModel.getForPlant(plantId, gardenId) }.collectAsState()
    val sorted = remember(photos) { photos.sortedBy { it.takenAt } }
    var pendingCameraUri by rememberSaveable(stateSaver = UriSaver) { mutableStateOf<Uri?>(null) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var uploadingPhotoId by remember { mutableStateOf<String?>(null) }
    var uploadFailedId by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) growthViewModel.addPhoto(plantId, pendingCameraUri.toString())
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            growthViewModel.addPhoto(plantId, uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text("Growth timeline", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(14.dp))

        if (sorted.size >= 2) {
            GrowthPhotoSlider(photos = sorted)
            Spacer(Modifier.height(20.dp))
        } else {
            Text("Add at least 2 growth photos to compare then vs now.", color = Color.Gray, fontSize = 13.sp)
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
            ) { Text("📷 Camera", fontSize = 12.sp) }
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text("🖼️ Gallery", fontSize = 12.sp) }
            if (DropboxAuthState.token != null) {
                OutlinedButton(onClick = { showDropboxPicker = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text("☁️ Dropbox", fontSize = 12.sp) }
            }
        }
        }

        Spacer(Modifier.height(20.dp))
        Text("All photos (${sorted.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                            TextButton(onClick = { growthViewModel.delete(photo.id) }) { Text("Delete", fontSize = 11.sp) }
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
        Spacer(Modifier.height(30.dp))
    }

    if (showDropboxPicker) {
        DropboxImagePickerDialog(context, onDismiss = { showDropboxPicker = false },
            onImageSelected = { link, clientModified -> growthViewModel.addPhoto(plantId, link, takenAtOverride = clientModified) })
    }
}