package com.sagegarden.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * The real (Google) map only — no custom drawing/sun-map, matching what was asked. Read-only, same
 * as the rest of this app: tap a marker to view the plant, no add/move-plant interactions. Emoji
 * category markers, same convention as the phone app's real map (categoryMarkerEmoji).
 */
@Composable
fun MapScreen(plants: List<Plant>, gardenLat: Double?, gardenLng: Double?, onPlantClick: (Plant) -> Unit) {
    val located = remember(plants) { plants.filter { it.lat != null && it.lng != null } }

    val cameraPositionState = rememberCameraPositionState {
        position = when {
            gardenLat != null && gardenLng != null ->
                CameraPosition.fromLatLngZoom(LatLng(gardenLat, gardenLng), 18f)
            located.isNotEmpty() -> {
                val bounds = LatLngBounds.builder().apply {
                    located.forEach { include(LatLng(it.lat!!, it.lng!!)) }
                }.build()
                CameraPosition.fromLatLngZoom(bounds.center, 17f)
            }
            else -> CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 1f)
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.HYBRID)
        ) {
            located.forEach { plant ->
                MarkerComposable(
                    state = com.google.maps.android.compose.rememberMarkerState(position = LatLng(plant.lat!!, plant.lng!!)),
                    onClick = { onPlantClick(plant); true }
                ) {
                    Box(
                        Modifier.size(30.dp).background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(categoryMarkerEmoji(plant.category), fontSize = 16.sp)
                    }
                }
            }
        }
        if (located.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        "No plants have a map location yet.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
