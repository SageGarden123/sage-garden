package com.example.sagegarden

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
            Text(sdf.format(Date(photos.first().takenAt)), fontSize = 11.sp, color = Color.Gray)
            val shownDate = if (blend < 0.5f) photos[lowerIndex].takenAt else photos[upperIndex].takenAt
            Text(sdf.format(Date(shownDate)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3A5A40))
            Text(sdf.format(Date(photos.last().takenAt)), fontSize = 11.sp, color = Color.Gray)
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
    val photos by remember(location) { locationViewModel.getForLocation(location) }.collectAsState()
    val sorted = remember(photos) { photos.sortedBy { it.takenAt } }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showDropboxPicker by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) locationViewModel.addPhoto(location, pendingCameraUri.toString())
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createImageUri(context); pendingCameraUri = uri; cameraLauncher.launch(uri) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            locationViewModel.addPhoto(location, uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.height(6.dp))
        Text("Progress photos — $location", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Spacer(Modifier.height(6.dp))
        Text(
            "For the best before/after comparison, take one photo of this whole area each season, from roughly the same spot.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(14.dp))

        if (sorted.size >= 2) {
            LocationPhotoSlider(photos = sorted)
            Spacer(Modifier.height(20.dp))
        } else {
            Text("Add at least 2 photos of this area to compare then vs now.", color = Color.Gray, fontSize = 13.sp)
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
                modifier = Modifier.weight(1f)
            ) { Text("📷 Camera", fontSize = 12.sp) }
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("🖼️ Gallery", fontSize = 12.sp) }
            if (DropboxAuthState.token != null) {
                OutlinedButton(onClick = { showDropboxPicker = true }, modifier = Modifier.weight(1f)) { Text("☁️ Dropbox", fontSize = 12.sp) }
            }
        }
        }

        Spacer(Modifier.height(20.dp))
        Text("All photos (${sorted.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
        sorted.reversed().forEach { photo ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = Uri.parse(photo.uri), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop,
                        onError = { Log.e("LocationPhoto", "list thumbnail load failed for ${photo.uri}", it.result.throwable) },
                        onSuccess = { Log.d("LocationPhoto", "list thumbnail load OK for ${photo.uri}") }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(sdf.format(Date(photo.takenAt)), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (canEdit) {
                        TextButton(onClick = { locationViewModel.delete(photo.id) }) { Text("Delete", fontSize = 11.sp) }
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
            initialPath = remember(location) { getProgressPhotoDropboxFolder(context, location) ?: "" },
            onPathChanged = { path -> setProgressPhotoDropboxFolder(context, location, path) }
        )
    }
}
