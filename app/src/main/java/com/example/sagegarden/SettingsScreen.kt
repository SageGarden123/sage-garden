@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.ui.res.stringResource

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*

import androidx.compose.material3.MaterialTheme

import com.example.sagegarden.ui.theme.appColors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FetchPlaceResponse
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsResponse
import com.google.maps.android.compose.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    SettingsGroup(title = "Garden address", faq = Faq.SET_ADDRESS) {
        if (!canEdit) {
            Text(stringResource(R.string.settings_view_only_synced_from_the_garden), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(stringResource(R.string.settings_garden_location_set), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
            label = { Text(stringResource(R.string.settings_garden_address)) }, placeholder = { Text(stringResource(R.string.settings_start_typing_to_search)) },
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
                                        GardenSettings.active(context).setLatLng(latLng.latitude, latLng.longitude)
                                        scope.launch { snackbarHostState.showSnackbar("Garden location saved") }
                                    }
                                }
                                gardenSessionToken = AutocompleteSessionToken.newInstance() // this session is spent — start a fresh one for the next search
                                gardenAddressQuery = prediction.getFullText(null).toString()
                                GardenSettings.active(context).address = gardenAddressQuery; GardenSettingsEdits.count++
                                gardenAddressEditedByUser = false
                                gardenPredictions = emptyList()
                            }.padding(12.dp),
                            fontSize = 13.sp
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    gardenGeocoderPredictions.forEach { address ->
                        Text(
                            text = address.getAddressLine(0) ?: stringResource(R.string.settings_unknown_address),
                            modifier = Modifier.fillMaxWidth().clickable {
                                gardenCoords = address.latitude to address.longitude
                                GardenSettings.active(context).setLatLng(address.latitude, address.longitude)
                                gardenAddressQuery = address.getAddressLine(0) ?: ""
                                GardenSettings.active(context).address = gardenAddressQuery; GardenSettingsEdits.count++
                                gardenAddressEditedByUser = false
                                gardenGeocoderPredictions = emptyList()
                                scope.launch { snackbarHostState.showSnackbar("Garden location saved") }
                            }.padding(12.dp),
                            fontSize = 13.sp
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        if (gardenCoords != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    GardenSettings.active(context).clearMapCameraPosition()
                    MapResetState.count++
                    scope.launch { snackbarHostState.showSnackbar("The map will open centred on the garden's address") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_map_position)) }
        }
    }
}

@Composable
fun GardenZonesSection(context: Context, plants: List<PlantEntity>) {
    // Owner-only — see GardenAddressSection's comment; the server only accepts a zones update from
    // the garden's owner (syncGarden.ts), so a non-owner editor must see this read-only too.
    val canEdit = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) }
    SettingsGroup(title = "Garden zones", faq = Faq.ZONES) {
        Text(
            stringResource(R.string.settings_manage_the_named_areas_of_your),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (!canEdit) {
            Text(stringResource(R.string.settings_view_only_synced_from_the_garden), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        var gardenLocations by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).getOrSeedLocations(plants)) }
        var newLocationText by remember { mutableStateOf("") }
        var renamingIndex by remember { mutableStateOf(-1) }
        var renameText by remember { mutableStateOf("") }

        // Same reactive-staleness fix as GardenAddressSection above — picks up a sync landing after
        // this section already composed with a stale/seeded snapshot. Harmless for the owner too:
        // their own add/rename/remove already writes GardenSettings.locations synchronously, so this just
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
                            GardenSettings.active(context).locations = gardenLocations; GardenSettingsEdits.count++
                        }
                        renamingIndex = -1
                    }) { Text(stringResource(R.string.care_save)) }
                    TextButton(onClick = { renamingIndex = -1 }) { Text(stringResource(R.string.care_cancel)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(loc, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (canEdit) {
                        TextButton(onClick = { renamingIndex = index; renameText = loc }) { Text(stringResource(R.string.settings_rename), fontSize = 12.sp) }
                        TextButton(onClick = {
                            gardenLocations = gardenLocations.filterIndexed { i, _ -> i != index }
                            GardenSettings.active(context).locations = gardenLocations; GardenSettingsEdits.count++
                        }) { Text(stringResource(R.string.map_remove), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (gardenLocations.isEmpty()) {
            Text(stringResource(R.string.settings_no_zones_yet_add_one_below), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        if (canEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newLocationText, onValueChange = { newLocationText = it },
                label = { Text(stringResource(R.string.settings_add_a_zone)) }, modifier = Modifier.weight(1f).imePadding(), singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val trimmed = newLocationText.trim()
                if (trimmed.isNotBlank() && trimmed !in gardenLocations) {
                    gardenLocations = gardenLocations + trimmed
                    GardenSettings.active(context).locations = gardenLocations; GardenSettingsEdits.count++
                }
                newLocationText = ""
            }) { Text(stringResource(R.string.settings_add)) }
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
            .background(MaterialTheme.colorScheme.primary),
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
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
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
    var showDeleteGardenFinalConfirm by remember { mutableStateOf(false) }
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

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = activeGarden?.name ?: "My Garden", onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.settings_active_garden)) },
            leadingIcon = { Icon(Icons.Outlined.Yard, contentDescription = null) },
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
                                PermissionChip("Owner", MaterialTheme.colorScheme.primary)
                            } else {
                                PermissionChip(if (garden.permission == "read") "View-only" else "Editor", MaterialTheme.colorScheme.onSurfaceVariant)
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
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.appColors.warningContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${pendingForActiveGarden.size} request${if (pendingForActiveGarden.size == 1) "" else "s"} to join ${activeGarden?.name ?: "this garden"}",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.appColors.onWarningContainer
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showCreateDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text(stringResource(R.string.settings_new_garden), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (isOwner) {
            OutlinedButton(onClick = { showShareDialog = true; shareCode = null }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text(stringResource(R.string.settings_share), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
        OutlinedButton(onClick = { showJoinDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text(stringResource(R.string.settings_have_code), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
    if (isOwner) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showRenameDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) { Text(stringResource(R.string.settings_rename), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            OutlinedButton(onClick = { showManageAccessDialog = true }, modifier = Modifier.weight(1f), contentPadding = CompactButtonPadding) {
                Text(
                    if (pendingForActiveGarden.isNotEmpty()) stringResource(R.string.settings_manage_access, pendingForActiveGarden.size) else stringResource(R.string.settings_manage_access_2),
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete ${activeGarden?.name ?: "this garden"}", fontSize = 12.sp) }
        }
    } else if (activeGardenId != installId) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showLeaveConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Leave ${activeGarden?.name ?: "this garden"}", fontSize = 12.sp) }
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
                        PermissionChip(if (req.requestedPermission == "read") "View-only" else "Edit", MaterialTheme.colorScheme.onSurfaceVariant)
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
                        ) { Text(stringResource(R.string.settings_approve), fontSize = 12.sp) }
                        OutlinedButton(
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                scope.launch {
                                    GardenMembershipClient.respondToJoinRequest(context, activeGardenId, req.requestingDeviceId, approve = false)
                                    pendingForActiveGarden = pendingForActiveGarden.filterNot { it.requestingDeviceId == req.requestingDeviceId }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.settings_reject), fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    if (pendingMyRequests.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.settings_your_pending_requests), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        pendingMyRequests.forEach { req ->
            Text(stringResource(R.string.settings_waiting_for_approval, req.name), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.settings_create_a_new_garden)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.settings_garden_name)) }, modifier = Modifier.fillMaxWidth())
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
                ) { Text(stringResource(R.string.settings_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.care_cancel)) } }
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
                        loadingShareCode -> Text(stringResource(R.string.settings_loading), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        shareCode == null -> Text(stringResource(R.string.settings_no_code_yet_tap_generate_below), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            Text(stringResource(R.string.settings_share_this_code), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.settings_tap_to_copy), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.settings_regenerating_invalidates_this_code_for_anyone), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }, enabled = !loadingShareCode) { Text(if (shareCode == null) stringResource(R.string.settings_generate) else stringResource(R.string.settings_regenerate)) }
            },
            dismissButton = { TextButton(onClick = { showShareDialog = false }) { Text(stringResource(R.string.overview_close)) } }
        )
    }

    if (showJoinDialog) {
        var code by remember { mutableStateOf("") }
        var wantsWrite by remember { mutableStateOf(false) }
        var displayName by remember { mutableStateOf(GardenMembershipStore.getDeviceDisplayName(context)) }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text(stringResource(R.string.settings_request_access_to_a_garden)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = displayName, onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.settings_your_name)) }, placeholder = { Text(stringResource(R.string.settings_e_g_my_phone)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.settings_shown_to_the_garden_s_owner), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text(stringResource(R.string.settings_invite_code)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_request_edit_access), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = wantsWrite, onCheckedChange = { wantsWrite = it })
                    }
                    Text(
                        if (wantsWrite) stringResource(R.string.settings_the_owner_can_still_grant_view) else stringResource(R.string.settings_view_only_you_won_t_be),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
                ) { Text(stringResource(R.string.settings_request)) }
            },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text(stringResource(R.string.care_cancel)) } }
        )
    }

    if (showRenameDialog) {
        var name by remember { mutableStateOf(activeGarden?.name?.takeIf { it != "My Garden" } ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.settings_rename_garden)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.settings_garden_name)) }, modifier = Modifier.fillMaxWidth())
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
                ) { Text(stringResource(R.string.settings_rename)) }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.care_cancel)) } }
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
            title = { Text(stringResource(R.string.settings_who_has_access)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        loadingMembers -> Text(stringResource(R.string.settings_loading), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        members.isEmpty() -> Text(stringResource(R.string.settings_no_one_else_has_access_yet), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> members.forEach { member ->
                            val memberLabel = member.displayName?.takeIf { it.isNotBlank() }
                                ?: (if (member.role == "owner") "You" else "Device ${member.deviceId.take(8)}")
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AvatarBubble(memberLabel)
                                    Spacer(Modifier.width(10.dp))
                                    Text(memberLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (member.role == "owner") {
                                        PermissionChip("Owner", MaterialTheme.colorScheme.primary)
                                    } else {
                                        PermissionChip(if (member.permission == "write") "Editor" else "View-only", MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (member.role != "owner") {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 42.dp)) {
                                        Text(stringResource(R.string.settings_edit_access), fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    ) { Text(stringResource(R.string.settings_remove_access), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                                }
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showManageAccessDialog = false }) { Text(stringResource(R.string.overview_close)) } }
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave ${activeGarden?.name ?: "this garden"}?") },
            text = { Text(stringResource(R.string.settings_you_ll_lose_access_to_its), fontSize = 13.sp) },
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
                }) { Text(stringResource(R.string.settings_leave), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text(stringResource(R.string.care_cancel)) } }
        )
    }

    // Two-step confirmation for a whole-garden delete: a warning dialog first, then a second "are
    // you sure" dialog that only unlocks once the garden's exact name is typed in — a plain second
    // tap is too easy to do by muscle memory/reflex for something this irreversible.
    if (showDeleteGardenConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteGardenConfirm = false },
            title = { Text("Delete ${activeGarden?.name ?: "this garden"}?") },
            text = {
                Text(
                    stringResource(R.string.settings_this_permanently_deletes_the_garden_and),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteGardenConfirm = false; showDeleteGardenFinalConfirm = true }) {
                    Text(stringResource(R.string.settings_continue), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteGardenConfirm = false }) { Text(stringResource(R.string.care_cancel)) } }
        )
    }
    if (showDeleteGardenFinalConfirm) {
        val gardenName = activeGarden?.name ?: "this garden"
        var confirmText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!deletingGarden) { showDeleteGardenFinalConfirm = false; confirmText = "" } },
            title = { Text(stringResource(R.string.settings_are_you_sure)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_type_below_to_confirm_you_want, gardenName), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmText, onValueChange = { confirmText = it },
                        label = { Text(stringResource(R.string.settings_garden_name)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !deletingGarden
                    )
                }
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
                            showDeleteGardenFinalConfirm = false
                            confirmText = ""
                        }
                    },
                    enabled = !deletingGarden && confirmText == gardenName
                ) { Text(if (deletingGarden) stringResource(R.string.settings_deleting) else stringResource(R.string.care_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteGardenFinalConfirm = false; confirmText = "" },
                    enabled = !deletingGarden
                ) { Text(stringResource(R.string.care_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    viewModel: PlantViewModel, wateringViewModel: WateringZoneViewModel, pathViewModel: IrrigationPathViewModel,
    snackbarHostState: SnackbarHostState, scope: CoroutineScope,
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

    val zoneRows = remember(ActiveGardenState.activeGardenId, TuyaZoneMappingState.mappings) {
        val initial = TuyaZoneMappingState.mappings.map { Triple(it.zone, it.deviceId, it.outlet) }
        mutableStateListOf(*(if (initial.isEmpty()) listOf(Triple("", "", "1")) else initial).toTypedArray())
    }
    val rachioZoneRows = remember(ActiveGardenState.activeGardenId) {
        val initial = GardenSettings.active(context).rachioZoneMappings.map { Triple(it.zone, it.deviceId, it.zoneId) }
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
                            p.lat?.toString() ?: "", p.lng?.toString() ?: "", p.wateringSystem,
                            if (p.manualWateringOnly) "Yes" else "No", if (p.isIndoor) "Yes" else "No"
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


        // 1) App settings & notifications
        if (page == SettingsPage.APPEARANCE) {
            AppearanceSettings()
        }
        if (page == SettingsPage.APP) {

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
            Text(stringResource(R.string.settings_takes_effect_next_time_you_open), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            
        }
        if (page == SettingsPage.GARDEN) {
        if (FeatureVisibility.shouldShow(context, Feature.GARDEN_SHARING)) {
        SettingsGroup(title = "Gardens & sharing", faq = Faq.SHARE_GARDEN) {
            Spacer(Modifier.height(14.dp)); HorizontalDivider(); Spacer(Modifier.height(14.dp))

            GardenSharingControls(context, scope, snackbarHostState)

            Spacer(Modifier.height(14.dp)); HorizontalDivider(); Spacer(Modifier.height(14.dp))

            Text(
                stringResource(R.string.settings_only_relevant_if_you_also_use),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val syncInstallId = remember { getOrCreateInstallId(context) }
            Text(
                stringResource(R.string.settings_install_id_tap_to_copy, syncInstallId),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Install ID", syncInstallId))
                    scope.launch { snackbarHostState.showSnackbar("Install ID copied") }
                }
            )
            Spacer(Modifier.height(10.dp))

            var showRecoverInstallId by remember { mutableStateOf(false) }
            TextButton(onClick = { showRecoverInstallId = !showRecoverInstallId }) {
                Text(if (showRecoverInstallId) stringResource(R.string.settings_recover_after_reinstall) else stringResource(R.string.settings_recover_after_reinstall_2), fontSize = 12.sp)
            }
            if (showRecoverInstallId) {
                var recoverInstallIdText by remember { mutableStateOf("") }
                Text(
                    stringResource(R.string.settings_reinstalling_always_generates_a_brand_new),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = recoverInstallIdText, onValueChange = { recoverInstallIdText = it },
                    label = { Text(stringResource(R.string.settings_old_install_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        setInstallId(context, recoverInstallIdText.trim())
                        scope.launch { snackbarHostState.showSnackbar("Saved — close and reopen the app for this to take effect") }
                    },
                    enabled = recoverInstallIdText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_save_and_use_this_install_id)) }
            }
            Spacer(Modifier.height(10.dp))
            var syncing by remember { mutableStateOf(false) }
            var lastSyncedAt by remember { mutableStateOf(GardenSyncStore.getLastSyncedAt(context)) }
            if (lastSyncedAt > 0) {
                Text(
                    "Last synced: ${SimpleDateFormat("dd MMM yyyy, h:mm a", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(lastSyncedAt))}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
            ) { Text(if (syncing) stringResource(R.string.settings_syncing) else stringResource(R.string.settings_sync_plants_care_history)) }
            if (!canEditActiveGarden) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.settings_you_have_view_only_access_to), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        }
        }
        if (page == SettingsPage.GARDEN) {
            GardenAddressSection(context, scope, snackbarHostState, initiallyExpanded = focusWeatherSection)
            GardenZonesSection(context, plants)
            SettingsGroup(title = "Hemisphere", faq = Faq.SEASONAL) {
            val gardenHasAddress = remember(ActiveGardenState.activeGardenId) { GardenSettings.active(context).latLng != null }
            if (gardenHasAddress) {
                val hemisphere = remember(ActiveGardenState.activeGardenId) { GardenSettings.active(context).hemisphere }
                Text(
                    "Auto-detected: ${if (hemisphere == Hemisphere.NORTHERN) "Northern" else "Southern"} (based on your garden address)",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.settings_set_a_different_garden_address_above), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            } else {
                var hemisphere by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).hemisphere) }
                Text(stringResource(R.string.settings_no_garden_address_set_yet_pick), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Hemisphere.SOUTHERN to "Southern (e.g. Australia)", Hemisphere.NORTHERN to "Northern").forEach { (value, label) ->
                        Button(
                            onClick = { hemisphere = value; GardenSettings.active(context).hemisphereFallback = value },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hemisphere == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (hemisphere == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
            }
        }
        if (page == SettingsPage.REMINDERS) {
            SettingsGroup(title = "Reminders", faq = Faq.TURN_ON_REMINDERS) {
            Text(stringResource(R.string.settings_get_reminded_when_your_plants_require), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))

            var notifsEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).notificationsEnabled) }
            var notifStyle by remember { mutableStateOf(deviceReminderSettings(context).notificationStyle) }
            var notifOffsets by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).notificationOffsets) }
            var notifHour by remember { mutableStateOf(deviceReminderSettings(context).notificationHour) }
            var notifMinute by remember { mutableStateOf(deviceReminderSettings(context).notificationMinute) }
            var hasNotifPermission by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasNotifPermission = granted
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val hasExactAlarmPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_enable_notifications), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = notifsEnabled, onCheckedChange = { checked ->
                    notifsEnabled = checked
                    GardenSettings.active(context).notificationsEnabled = checked
                    if (checked) {
                        scheduleWateringReminders(context)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else if (!anyGardenNotificationsEnabled(context)) cancelWateringReminders(context)
                })
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && notifsEnabled && !hasNotifPermission) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_notification_permission_isn_t_granted_enable), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && notifsEnabled && !hasExactAlarmPermission) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_exact_alarm_permission_isn_t_granted), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }) { Text(stringResource(R.string.settings_grant_exact_alarm_permission)) }
            }

            if (notifsEnabled) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_style), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("lockscreen" to "Lock screen", "popup" to "Pop-up", "both" to "Both").forEach { (key, label) ->
                        Button(
                            onClick = { notifStyle = key; deviceReminderSettings(context).notificationStyle = key },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (notifStyle == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (notifStyle == key) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_remind_me), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                listOf(0 to "On the day", 1 to "1 day before", 2 to "2 days before", 3 to "3 days before").forEach { (days, label) ->
                    val checked = notifOffsets.contains(days)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val updated = (if (checked) notifOffsets - days else notifOffsets + days).ifEmpty { setOf(0) }
                            notifOffsets = updated; GardenSettings.active(context).notificationOffsets = updated
                        }.padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            val updated = (if (checked) notifOffsets - days else notifOffsets + days).ifEmpty { setOf(0) }
                            notifOffsets = updated; GardenSettings.active(context).notificationOffsets = updated
                        })
                        Text(label, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_overdue_repeat_reminders), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                var overdueRepeatEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).overdueRepeatEnabled) }
                var overdueRepeatDaysText by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).overdueRepeatDays.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_keep_reminding_while_overdue), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = overdueRepeatEnabled, onCheckedChange = {
                        overdueRepeatEnabled = it; GardenSettings.active(context).overdueRepeatEnabled = it
                    })
                }
                if (overdueRepeatEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = overdueRepeatDaysText,
                        onValueChange = { new ->
                            overdueRepeatDaysText = new.filter { it.isDigit() }
                            overdueRepeatDaysText.toIntOrNull()?.let { GardenSettings.active(context).overdueRepeatDays = it }
                        },
                        label = { Text(stringResource(R.string.settings_repeat_every_days)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(stringResource(R.string.settings_re_notify_for_overdue_plants_until), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                var fertiliseReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).fertiliseRemindersEnabled) }
                var pruneReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).pruneRemindersEnabled) }
                var feedReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).feedRemindersEnabled) }
                var progressPhotoReminders by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).progressPhotoRemindersEnabled) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_include_fertilising_reminders), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = fertiliseReminders, onCheckedChange = { fertiliseReminders = it; GardenSettings.active(context).fertiliseRemindersEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_include_pruning_reminders), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = pruneReminders, onCheckedChange = { pruneReminders = it; GardenSettings.active(context).pruneRemindersEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_include_feeding_reminders), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = feedReminders, onCheckedChange = { feedReminders = it; GardenSettings.active(context).feedRemindersEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_remind_me_to_add_progress_photos), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = progressPhotoReminders, onCheckedChange = { progressPhotoReminders = it; GardenSettings.active(context).progressPhotoRemindersEnabled = it })
                }

                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_notify_at), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    TextButton(onClick = { showTimeDialog = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.care_cancel)) }
                                    Button(
                                        onClick = {
                                            notifHour = timeState.hour; notifMinute = timeState.minute
                                            deviceReminderSettings(context).setNotificationTime(notifHour, notifMinute)
                                            scheduleWateringReminders(context)
                                            showTimeDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text(stringResource(R.string.water_set)) }
                                }
                            }
                        }
                    }
                }
            }
            }
            if (FeatureVisibility.shouldShow(context, Feature.WEATHER_AWARE_REMINDERS)) {
            SettingsGroup(title = "Weather & frost", faq = Faq.WEATHER) {
            var weatherSkipEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).weatherSkipEnabled) }
            var rainThreshold by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).rainProbabilityThreshold) }
            val hasGardenAddress = remember(ActiveGardenState.activeGardenId, GardenAddressState.latLng) { GardenSettings.active(context).latLng != null }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_flag_reminders_when_rain_is_likely), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = weatherSkipEnabled, onCheckedChange = { weatherSkipEnabled = it; GardenSettings.active(context).weatherSkipEnabled = it })
            }
            if (!hasGardenAddress) {
                Text(stringResource(R.string.settings_set_your_garden_address_above_garden), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var frostWarningsEnabled by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).frostWarningsEnabled) }
            var frostThreshold by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).frostTempThreshold.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_warn_about_frost_risk_for_tender), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = frostWarningsEnabled, onCheckedChange = { frostWarningsEnabled = it; GardenSettings.active(context).frostWarningsEnabled = it })
            }
            if (frostWarningsEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("Warn when the forecast minimum is at or below ${"%.1f".format(frostThreshold)}°C", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = frostThreshold, onValueChange = { frostThreshold = it },
                    onValueChangeFinished = { GardenSettings.active(context).frostTempThreshold = frostThreshold.toDouble() },
                    valueRange = -5f..8f, steps = 12
                )
            }

                if (weatherSkipEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_flag_if_rain_probability_is_at, rainThreshold), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = rainThreshold.toFloat(), onValueChange = { rainThreshold = it.toInt() },
                        onValueChangeFinished = { GardenSettings.active(context).rainProbabilityThreshold = rainThreshold },
                        valueRange = 10f..100f, steps = 8
                    )

                    Spacer(Modifier.height(8.dp))
                    var rainAmountThreshold by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).rainAmountThresholdMm) }
                    Text(
                        "And at least ${"%.1f".format(rainAmountThreshold)}mm forecast (filters out high-probability drizzle)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = rainAmountThreshold, onValueChange = { rainAmountThreshold = it },
                        onValueChangeFinished = { GardenSettings.active(context).rainAmountThresholdMm = rainAmountThreshold },
                        valueRange = 0f..20f, steps = 39
                    )
                }
            }
        }
        }

        // 2) Photos & cloud storage — device-wide Dropbox connection/storage mode, not tied to any
        // one garden, so hidden entirely for a non-owner viewing someone else's shared garden
        // (matches the Custom garden map / Irrigation gating below).
        if (page == SettingsPage.PHOTOS) {
        run {
        SettingsGroup(title = "Photo storage", faq = Faq.PHOTO_STORAGE) {
            Text(stringResource(R.string.settings_photo_storage), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { photoMode = "local"; setPhotoStorageMode(context, "local") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (photoMode == "local") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (photoMode == "local") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                ) { Text(stringResource(R.string.settings_on_this_device), fontSize = 12.sp) }
                Button(
                    onClick = { photoMode = "cloud"; setPhotoStorageMode(context, "cloud") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (photoMode == "cloud") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (photoMode == "cloud") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                ) { Text(stringResource(R.string.settings_cloud_link), fontSize = 12.sp) }
            }
            if (photoMode == "cloud") {
                Spacer(Modifier.height(8.dp))
                val dropboxToken = DropboxAuthState.token
                if (dropboxToken != null) {
                    Text(stringResource(R.string.settings_connected_to_dropbox), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { DropboxAuthState.clear(context) }) { Text(stringResource(R.string.settings_disconnect)) }
                } else {
                    Button(onClick = { startDropboxSignIn(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_connect_dropbox)) }
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_auto_link_photos_by_plant_id), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_photos_named_after_a_plant_id), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))

            if (photoMode == "local") {
                var localFolder by remember { mutableStateOf(getLocalPhotoFolderUri(context)) }
                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (localFolder != null) stringResource(R.string.settings_change_photo_folder) else stringResource(R.string.settings_choose_photo_folder))
                }
                if (localFolder != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            val count = autoLinkLocalPhotos(context, localFolder!!, plants) { viewModel.save(it) }
                            snackbarHostState.showSnackbar("Linked $count new photo(s)")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_auto_link_photos_now)) }
                }
            } else {
                var dropboxPath by remember { mutableStateOf(getDropboxPhotoFolderPath(context) ?: "") }
                var showFolderPicker by remember { mutableStateOf(false) }
                var testResult by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = dropboxPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.settings_dropbox_folder)) }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_browse_dropbox)) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                testing = true; testResult = null
                                val result = countDropboxImages(context, dropboxPath)
                                testing = false
                                testResult = result.fold({ "Found $it image(s) in this folder" }, { "Couldn't read that folder: ${it.message ?: "unknown error"}" })
                            }
                        },
                        modifier = Modifier.weight(1f), enabled = !testing && !DropboxLinkState.linking,
                        contentPadding = CompactButtonPadding
                    ) { Text(if (testing) stringResource(R.string.settings_testing) else stringResource(R.string.settings_test_connection)) }

                    Button(
                        onClick = { setDropboxPhotoFolderPath(context, dropboxPath); viewModel.runDropboxAutoLink(context, dropboxPath) },
                        modifier = Modifier.weight(1f), enabled = !testing && !DropboxLinkState.linking,
                        contentPadding = CompactButtonPadding
                    ) { Text(if (DropboxLinkState.linking) stringResource(R.string.settings_linking) else stringResource(R.string.settings_auto_link_now)) }
                }

                testResult?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = if (it.startsWith("Found ")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                if (DropboxLinkState.linking) {
                    Spacer(Modifier.height(10.dp))
                    val current = DropboxLinkState.current; val total = DropboxLinkState.total
                    LinearProgressIndicator(progress = { if (total > 0) current.toFloat() / total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_of_images_linked, current, total), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val linkResult = DropboxLinkState.result ?: getLastDropboxLinkResult(context)
                linkResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (it.startsWith("Linking unsuccessful")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.settings_clear_auto_linked_photos_keep_plants)) }
        }
        }
        }

        // 3) Irrigation (Advanced mode + Pro only — hiding it never touches the saved Tuya or Rachio credentials/zones below).
        // Also device-wide/owner-only, same reasoning as Photos & cloud storage above.
        if (page == SettingsPage.IRRIGATION) {
        if (isGardenOwner && FeatureVisibility.shouldShow(context, Feature.TUYA_INTEGRATION)) {
        SettingsGroup(title = "Irrigation controller", faq = Faq.IRRIGATION_SUPPORTED) {
            var irrigationSystem by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).irrigationSystem) }
            Text(stringResource(R.string.settings_which_irrigation_system_do_you_have), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(IrrigationSystem.NONE to "None", IrrigationSystem.TUYA to "Tuya", IrrigationSystem.RACHIO to "Rachio").forEach { (value, label) ->
                    Button(
                        onClick = { irrigationSystem = value; GardenSettings.active(context).irrigationSystem = value },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (irrigationSystem == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (irrigationSystem == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = CompactButtonPadding
                    ) { Text(label, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_switching_doesn_t_delete_the_other), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            if (irrigationSystem == IrrigationSystem.TUYA) {
            var tuyaClientId by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).tuyaClientId) }
            var tuyaClientSecret by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).tuyaClientSecret) }
            var tuyaSecretVisible by remember { mutableStateOf(false) }
            var tuyaEditing by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).tuyaClientId.isBlank() || GardenSettings.active(context).tuyaClientSecret.isBlank()) }

            Text(stringResource(R.string.settings_tuya_connection), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = tuyaClientId,
                onValueChange = { tuyaClientId = it },
                label = { Text(stringResource(R.string.settings_tuya_client_id)) },
                singleLine = true,
                readOnly = !tuyaEditing,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tuyaClientSecret,
                onValueChange = { tuyaClientSecret = it },
                label = { Text(stringResource(R.string.settings_tuya_client_secret)) },
                singleLine = true,
                readOnly = !tuyaEditing,
                visualTransformation = if (tuyaSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { tuyaSecretVisible = !tuyaSecretVisible }) {
                        Text(if (tuyaSecretVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show), fontSize = 12.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (tuyaClientId.isBlank() || tuyaClientSecret.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_both_fields_are_required_to_sync), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            if (tuyaEditing) {
                Button(
                    onClick = {
                        GardenSettings.active(context).tuyaClientId = tuyaClientId
                        GardenSettings.active(context).tuyaClientSecret = tuyaClientSecret
                        TuyaClient.invalidateToken()
                        tuyaEditing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_save_tuya_credentials)) }
            } else {
                OutlinedButton(onClick = { tuyaEditing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_edit_tuya_credentials)) }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            var zonesExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { zonesExpanded = !zonesExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_irrigation_zones_tuya), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (zonesExpanded) "▾" else "▸ ${zoneRows.count { it.first.isNotBlank() }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (zonesExpanded) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_add_a_friendly_zone_name_for), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                zoneRows.forEachIndexed { index, (zoneName, deviceId, outlet) ->
                    Column(Modifier.padding(bottom = 12.dp)) {
                        OutlinedTextField(value = zoneName, onValueChange = { zoneRows[index] = Triple(it, deviceId, outlet) }, label = { Text(stringResource(R.string.map_zone_name)) }, placeholder = { Text(stringResource(R.string.settings_e_g_front_garden)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = deviceId, onValueChange = { zoneRows[index] = Triple(zoneName, it, outlet) }, label = { Text(stringResource(R.string.settings_tuya_device_id)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                DropdownField(label = "Outlet", options = listOf("1", "2"), selected = outlet, onSelect = { zoneRows[index] = Triple(zoneName, deviceId, it) })
                            }
                            if (zoneRows.size > 1) IconButton(onClick = { zoneRows.removeAt(index) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove zone") }
                        }
                    }
                }
                OutlinedButton(onClick = { zoneRows.add(Triple("", "", "1")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_add_zone)) }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val mappings = zoneRows.filter { it.first.isNotBlank() && it.second.isNotBlank() }.map { TuyaZoneMapping(it.first, it.second, it.third) }
                        GardenSettings.active(context).tuyaZoneMappings = mappings
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping saved") }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_save_zone_mapping)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        zoneRows.clear(); zoneRows.add(Triple("", "", "1")); GardenSettings.active(context).tuyaZoneMappings = emptyList()
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping cleared") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.settings_clear_all_zones)) }
            }
            }

            if (irrigationSystem == IrrigationSystem.RACHIO) {
            var rachioApiToken by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).rachioApiToken) }
            var rachioTokenVisible by remember { mutableStateOf(false) }
            var rachioEditing by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).rachioApiToken.isBlank()) }
            var rachioTesting by remember { mutableStateOf(false) }
            var rachioTestResult by remember { mutableStateOf<String?>(null) }

            Text(stringResource(R.string.settings_rachio_connection), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = rachioApiToken,
                onValueChange = { rachioApiToken = it },
                label = { Text(stringResource(R.string.settings_rachio_api_token)) },
                singleLine = true,
                readOnly = !rachioEditing,
                visualTransformation = if (rachioTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { rachioTokenVisible = !rachioTokenVisible }) {
                        Text(if (rachioTokenVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show), fontSize = 12.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (rachioApiToken.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_an_api_token_is_required_to), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            if (rachioEditing) {
                Button(
                    onClick = {
                        GardenSettings.active(context).rachioApiToken = rachioApiToken
                        rachioEditing = false
                        rachioTestResult = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_save_rachio_api_token)) }
            } else {
                OutlinedButton(onClick = { rachioEditing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_edit_rachio_api_token)) }
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
                ) { Text(if (rachioTesting) stringResource(R.string.settings_testing) else stringResource(R.string.settings_test_connection)) }
                rachioTestResult?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            var rachioZonesExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { rachioZonesExpanded = !rachioZonesExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_irrigation_zones_rachio), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (rachioZonesExpanded) "▾" else "▸ ${rachioZoneRows.count { it.first.isNotBlank() }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (rachioZonesExpanded) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_add_a_friendly_zone_name_for_2), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                rachioZoneRows.forEachIndexed { index, (zoneName, deviceId, zoneId) ->
                    Column(Modifier.padding(bottom = 12.dp)) {
                        OutlinedTextField(value = zoneName, onValueChange = { rachioZoneRows[index] = Triple(it, deviceId, zoneId) }, label = { Text(stringResource(R.string.map_zone_name)) }, placeholder = { Text(stringResource(R.string.settings_e_g_front_garden)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = deviceId, onValueChange = { rachioZoneRows[index] = Triple(zoneName, it, zoneId) }, label = { Text(stringResource(R.string.settings_rachio_device_id)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = zoneId, onValueChange = { rachioZoneRows[index] = Triple(zoneName, deviceId, it) }, label = { Text(stringResource(R.string.settings_rachio_zone_id)) }, modifier = Modifier.weight(1f))
                            if (rachioZoneRows.size > 1) IconButton(onClick = { rachioZoneRows.removeAt(index) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove zone") }
                        }
                    }
                }
                OutlinedButton(onClick = { rachioZoneRows.add(Triple("", "", "")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_add_zone)) }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val mappings = rachioZoneRows.filter { it.first.isNotBlank() && it.second.isNotBlank() && it.third.isNotBlank() }.map { RachioZoneMapping(it.first, it.second, it.third) }
                        GardenSettings.active(context).rachioZoneMappings = mappings
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping saved") }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_save_zone_mapping)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        rachioZoneRows.clear(); rachioZoneRows.add(Triple("", "", "")); GardenSettings.active(context).rachioZoneMappings = emptyList()
                        scope.launch { snackbarHostState.showSnackbar("Zone mapping cleared") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.settings_clear_all_zones)) }
            }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_irrigation_log_location), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_where_irrigation_log_csv_is_read),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (getPhotoStorageMode(context) == "cloud") {
                var irrigationDropboxPath by remember { mutableStateOf(getIrrigationLogDropboxFolderPath(context) ?: "") }
                var showIrrigationFolderPicker by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = irrigationDropboxPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.settings_dropbox_folder)) }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showIrrigationFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_browse_dropbox)) }
                if (showIrrigationFolderPicker) {
                    DropboxFolderPickerDialog(
                        context = context, onDismiss = { showIrrigationFolderPicker = false },
                        onFolderSelected = { path -> irrigationDropboxPath = path; setIrrigationLogDropboxFolderPath(context, path) }
                    )
                }
            } else {
                OutlinedButton(onClick = { irrigationLogFolderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (irrigationLogFolder != null) stringResource(R.string.settings_change_irrigation_log_folder) else stringResource(R.string.settings_choose_irrigation_log_folder))
                }
            }
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            if (irrigationSystem != IrrigationSystem.NONE) {
            Text(stringResource(R.string.settings_sync_irrigation_history), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (irrigationSystem == IrrigationSystem.RACHIO)
                    stringResource(R.string.settings_pulls_the_last_30_days_of)
                else
                    stringResource(R.string.settings_pulls_the_last_30_days_of_2),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            val syncing by wateringViewModel.syncing.collectAsState()
            val syncResult by wateringViewModel.lastSyncResult.collectAsState()
            Button(onClick = { wateringViewModel.sync(context) }, modifier = Modifier.fillMaxWidth(), enabled = !syncing) { Text(if (syncing) stringResource(R.string.settings_syncing) else stringResource(R.string.settings_sync_watering_history)) }
            syncResult?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Re-read whenever a manual sync finishes (syncing flips back to false) or the garden changes.
            val lastIrrigationSyncAt = remember(ActiveGardenState.activeGardenId, syncing) { GardenSettings.of(context, effectiveGardenId(context)).lastIrrigationSyncAt }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_also_syncs_automatically_every_days_in, IRRIGATION_AUTO_SYNC_DAYS) +
                    if (lastIrrigationSyncAt > 0L) " Last synced ${java.text.SimpleDateFormat("d MMM yyyy, h:mm a", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(java.util.Date(lastIrrigationSyncAt))}." else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { irrigationImportLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_import_from_device)) }
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
                ) { Text(stringResource(R.string.settings_import_from_dropbox)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { irrigationExportLauncher.launch("irrigation_log.csv") }, modifier = Modifier.weight(1f), enabled = irrigationEvents.isNotEmpty()) { Text(stringResource(R.string.settings_export_to_device)) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val saved = saveIrrigationCsvDropbox(context, irrigationEvents)
                            snackbarHostState.showSnackbar(if (saved) "Exported to Dropbox" else "Export to Dropbox failed")
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = irrigationEvents.isNotEmpty() && DropboxAuthState.token != null
                ) { Text(stringResource(R.string.settings_export_to_dropbox)) }
            }

        }
        }
        }

        // 3a) Sage assistant on/off
        if (page == SettingsPage.APP) {
        SettingsGroup(title = "Sage assistant", faq = Faq.SAGE_WHAT) {
            var sageChatEnabled by remember { mutableStateOf(FeatureVisibility.isSageChatEnabled(context)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_sage_assistant), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = sageChatEnabled, onCheckedChange = {
                    sageChatEnabled = it
                    FeatureVisibility.setSageChatEnabled(context, it)
                })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_turn_off_the_floating_leaf_button),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_drag_the_floating_leaf_button_up),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                FeatureVisibility.setSageFabOffsetDp(context, 0f)
                SageFabResetState.requested = true
            }) {
                Text(stringResource(R.string.settings_reset_button_position), fontSize = 12.sp)
            }
        }
        }

        // 3b) Basic / Advanced mode
        if (page == SettingsPage.APP) {
        SettingsGroup(title = "Basic or Advanced", faq = Faq.BASIC_ADVANCED) {
            var advancedMode by remember { mutableStateOf(FeatureVisibility.isAdvancedModeEnabled(context)) }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(if (advancedMode) stringResource(R.string.settings_advanced_mode) else stringResource(R.string.settings_basic_mode), fontSize = 13.sp, modifier = Modifier.weight(1f))
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
                stringResource(R.string.settings_nothing_is_deleted_when_you_switch),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            var promoCode by remember { mutableStateOf("") }
            var redeemingPromo by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = promoCode, onValueChange = { promoCode = it.uppercase() },
                label = { Text(stringResource(R.string.settings_promo_code)) },
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
            ) { Text(if (redeemingPromo) stringResource(R.string.settings_redeeming) else stringResource(R.string.settings_redeem_promo_code)) }
        }
        }

        // 4) Custom garden map
        val canManageActiveGardenMap = remember(ActiveGardenState.activeGardenId) { isOwnerOfActiveGarden(context) }
        if (page == SettingsPage.GARDEN) {
        if (canManageActiveGardenMap && FeatureVisibility.shouldShow(context, Feature.CUSTOM_MAP)) {
        SettingsGroup(title = "Your own garden map", faq = Faq.REAL_VS_CUSTOM) {
            var customMapUri by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).customMapUri) }
            var useCustomMap by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).usingCustomMap) }
            val mapImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    GardenSettings.active(context).customMapUri = uri; customMapUri = uri; GardenPlanEdits.count++
                }
            }
            OutlinedButton(onClick = { mapImageLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (customMapUri != null) stringResource(R.string.settings_change_custom_map_image) else stringResource(R.string.settings_upload_custom_map_image))
            }
            if (customMapUri != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_use_custom_map_instead_of_real), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = useCustomMap, onCheckedChange = { useCustomMap = it; GardenSettings.active(context).usingCustomMap = it })
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(10.dp))
                var mapRotationDeg by remember(ActiveGardenState.activeGardenId) { mutableStateOf(GardenSettings.active(context).customMapRotation) }
                Text(stringResource(R.string.settings_orientation), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        mapRotationDeg = (mapRotationDeg + 90) % 360
                        GardenSettings.active(context).customMapRotation = mapRotationDeg
                        GardenPlanEdits.count++
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_rotate_90_currently, mapRotationDeg)) }
                Text(
                    stringResource(R.string.settings_rotating_shifts_what_s_shown_at),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { GardenSettings.active(context).customMapUri = null; GardenSettings.active(context).usingCustomMap = false; customMapUri = null; useCustomMap = false; GardenPlanEdits.count++ },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.settings_clear_custom_map)) }
            }
        }
        }
        }

        // 5) Data — export/import/backup/reset all mutate or dump the whole garden's data; a
        // view-only member shouldn't see any of it, not just have individual buttons disabled.
        if (page == SettingsPage.DATA) {
        if (canEditActiveGarden) {
        SettingsGroup(title = "Backup & restore", faq = Faq.BACKUP_OPTIONS) {
            Text(stringResource(R.string.settings_export_to_spreadsheet), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_downloads_all_your_plant_data_excluding), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { exportLauncher.launch(csvExportFileNameForGarden(context, effectiveGardenId(context))) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_export_csv_to_device)) }
            if (DropboxAuthState.token != null) {
                Spacer(Modifier.height(8.dp))
                var dropboxCsvFolderPath by remember {
                    mutableStateOf(getDropboxCsvFolderPath(context) ?: getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: "")
                }
                var showDropboxCsvFolderPicker by remember { mutableStateOf(false) }
                Text(stringResource(R.string.settings_dropbox_folder), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = dropboxCsvFolderPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.settings_dropbox_folder)) }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDropboxCsvFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_browse_dropbox)) }
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
                ) { Text(if (dropboxCsvExporting) stringResource(R.string.settings_exporting) else if (checkingExistingCsv) stringResource(R.string.settings_checking) else stringResource(R.string.settings_export_csv_to_dropbox)) }
                if (showReplaceCsvConfirm) {
                    val sdf = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
                    val defaultName = remember { csvExportFileNameForGarden(context, effectiveGardenId(context)) }
                    AlertDialog(
                        onDismissRequest = { showReplaceCsvConfirm = false },
                        title = { Text(stringResource(R.string.settings_replace_existing_csv)) },
                        text = { Text("A CSV from ${existingCsvDate?.let { sdf.format(it) } ?: "earlier"} already exists in this Dropbox folder. Replace it, or keep it and save this as a separate new file?") },
                        confirmButton = { TextButton(onClick = { showReplaceCsvConfirm = false; runDropboxCsvExport(defaultName) }) { Text(stringResource(R.string.settings_replace)) } },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { showReplaceCsvConfirm = false }) { Text(stringResource(R.string.care_cancel)) }
                                TextButton(onClick = {
                                    showReplaceCsvConfirm = false
                                    val timestamped = defaultName.removeSuffix(".csv") + "_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.csv"
                                    runDropboxCsvExport(timestamped)
                                }) { Text(stringResource(R.string.settings_create_new)) }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_import_from_spreadsheet), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_upload_a_csv_in_the_same), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                modifier = Modifier.fillMaxWidth(), enabled = canEditActiveGarden
            ) { Text(stringResource(R.string.settings_choose_csv_file_from_device)) }
            if (DropboxAuthState.token != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDropboxCsvPicker = true },
                    modifier = Modifier.fillMaxWidth(), enabled = canEditActiveGarden && !dropboxCsvImporting
                ) { Text(if (dropboxCsvImporting) stringResource(R.string.settings_importing) else stringResource(R.string.settings_choose_csv_from_dropbox)) }
            }
            if (!canEditActiveGarden) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.settings_you_have_view_only_access_to_2), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

            Text(stringResource(R.string.settings_backup_restore_all_data), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_backs_up_all_plants_including_seasonal), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (isGardenOwner) {
                Text(stringResource(R.string.settings_note_locally_stored_photos_can_t), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
                Text(stringResource(R.string.settings_backup_folder), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = backupFolderPath.ifBlank { "(root)" }, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.settings_dropbox_folder)) }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showBackupFolderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_browse_dropbox)) }
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
                    title = { Text(stringResource(R.string.settings_replace_existing_backup)) },
                    text = {
                        Text(
                            "A backup from ${existingBackupDate?.let { sdf.format(it) } ?: "earlier"} already exists in this Dropbox folder. Replace it, or keep it and save this as a separate new backup?"
                        )
                    },
                    confirmButton = { TextButton(onClick = { showReplaceBackupConfirm = false; runDropboxBackup() }) { Text(stringResource(R.string.settings_replace)) } },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { showReplaceBackupConfirm = false }) { Text(stringResource(R.string.care_cancel)) }
                            TextButton(onClick = {
                                showReplaceBackupConfirm = false
                                runDropboxBackup(BackupHelper.newDatedBackupFileName())
                            }) { Text(stringResource(R.string.settings_create_new)) }
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
            ) { Text(if (restoreWorking) stringResource(R.string.settings_restoring) else if (loadingBackupList) stringResource(R.string.settings_checking) else stringResource(R.string.settings_restore_from_dropbox)) }

            if (showBackupPicker) {
                val sdf = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
                AlertDialog(
                    onDismissRequest = { showBackupPicker = false },
                    title = { Text(stringResource(R.string.settings_choose_a_backup_to_restore)) },
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
                                        Text(sdf.format(info.modifiedAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showBackupPicker = false }) { Text(stringResource(R.string.care_cancel)) } }
                )
            }

            if (DropboxAuthState.token == null && isGardenOwner) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.settings_connect_dropbox_above_first), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            backupResultText?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

            if (showRestoreConfirm) {
                var forceFreshRestore by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showRestoreConfirm = false },
                    title = { Text(stringResource(R.string.settings_restore_from_dropbox_2)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.settings_restoring_adds_updates_plants_irrigation_paths, selectedRestoreFileName ?: BackupHelper.defaultBackupFileNameForGarden(context, effectiveGardenId(context))))
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { forceFreshRestore = !forceFreshRestore }
                            ) {
                                Checkbox(checked = forceFreshRestore, onCheckedChange = { forceFreshRestore = it })
                                Text(stringResource(R.string.settings_make_this_the_current_version_everywhere), fontSize = 12.sp, modifier = Modifier.weight(1f))
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
                        }) { Text(stringResource(R.string.settings_restore)) }
                    },
                    dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.care_cancel)) } }
                )
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_export_backup_to_device), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_saves_the_same_backup_to_a), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(if (localBackupFolder != null) stringResource(R.string.settings_change_backup_folder) else stringResource(R.string.settings_choose_backup_folder))
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
                ) { Text(if (localBackupWorking) stringResource(R.string.settings_backing_up) else stringResource(R.string.settings_export_backup_now)) }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showLocalRestoreConfirm = true },
                    modifier = Modifier.fillMaxWidth(), enabled = !localBackupWorking && canEditActiveGarden
                ) { Text(stringResource(R.string.settings_restore_from_this_folder)) }
            }

            localBackupResultText?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

            if (showLocalRestoreConfirm) {
                var forceFreshLocalRestore by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showLocalRestoreConfirm = false },
                    title = { Text(stringResource(R.string.settings_restore_from_device_backup)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.settings_this_adds_updates_plants_irrigation_paths))
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { forceFreshLocalRestore = !forceFreshLocalRestore }
                            ) {
                                Checkbox(checked = forceFreshLocalRestore, onCheckedChange = { forceFreshLocalRestore = it })
                                Text(stringResource(R.string.settings_make_this_the_current_version_everywhere), fontSize = 12.sp, modifier = Modifier.weight(1f))
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
                        }) { Text(stringResource(R.string.settings_restore)) }
                    },
                    dismissButton = { TextButton(onClick = { showLocalRestoreConfirm = false }) { Text(stringResource(R.string.care_cancel)) } }
                )
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_automatic_backups_this_device), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_runs_silently_once_a_day_no),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            var showAutoBackupPicker by remember { mutableStateOf(false) }
            var selectedAutoBackupWeekday by remember { mutableStateOf<String?>(null) }
            var showAutoRestoreConfirm by remember { mutableStateOf(false) }
            var autoBackupWorking by remember { mutableStateOf(false) }
            var autoBackupResultText by remember { mutableStateOf<String?>(null) }
            val availableAutoBackups = remember(ActiveGardenState.activeGardenId) {
                BackupHelper.listLocalAutoBackups(context, effectiveGardenId(context))
            }

            OutlinedButton(
                onClick = { showAutoBackupPicker = true },
                modifier = Modifier.fillMaxWidth(), enabled = availableAutoBackups.isNotEmpty() && canEditActiveGarden && !autoBackupWorking
            ) { Text(if (autoBackupWorking) stringResource(R.string.settings_restoring) else stringResource(R.string.settings_restore_from_automatic_backup)) }
            if (availableAutoBackups.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_none_yet_the_first_one_is), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (showAutoBackupPicker) {
                val sdf = remember { SimpleDateFormat("EEEE, dd MMM yyyy, h:mm a", Locale.getDefault()) }
                AlertDialog(
                    onDismissRequest = { showAutoBackupPicker = false },
                    title = { Text(stringResource(R.string.settings_choose_an_automatic_backup_to_restore)) },
                    text = {
                        Column {
                            availableAutoBackups.forEach { info ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            showAutoBackupPicker = false
                                            selectedAutoBackupWeekday = info.weekday
                                            showAutoRestoreConfirm = true
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(sdf.format(info.modifiedAt), fontSize = 13.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showAutoBackupPicker = false }) { Text(stringResource(R.string.care_cancel)) } }
                )
            }

            autoBackupResultText?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

            if (showAutoRestoreConfirm) {
                var forceFreshAutoRestore by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showAutoRestoreConfirm = false },
                    title = { Text(stringResource(R.string.settings_restore_this_automatic_backup)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.settings_this_adds_updates_plants_irrigation_paths_2))
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { forceFreshAutoRestore = !forceFreshAutoRestore }
                            ) {
                                Checkbox(checked = forceFreshAutoRestore, onCheckedChange = { forceFreshAutoRestore = it })
                                Text(stringResource(R.string.settings_make_this_the_current_version_everywhere), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAutoRestoreConfirm = false
                            val weekday = selectedAutoBackupWeekday
                            if (weekday != null) {
                                scope.launch {
                                    autoBackupWorking = true; autoBackupResultText = null
                                    val result = BackupHelper.restoreLocalAutoBackup(
                                        context, viewModel, pathViewModel, wateringViewModel,
                                        effectiveGardenId(context), weekday, forceFreshAutoRestore
                                    )
                                    autoBackupWorking = false; autoBackupResultText = result.message
                                }
                            }
                        }) { Text(stringResource(R.string.settings_restore)) }
                    },
                    dismissButton = { TextButton(onClick = { showAutoRestoreConfirm = false }) { Text(stringResource(R.string.care_cancel)) } }
                )
            }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_reset_all_data), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_clears_every_plant_in_this_app), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showResetDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = isGardenOwner,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.settings_reset_garden)) }
            if (!isGardenOwner) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.settings_only_this_garden_s_owner_can), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        }
        }



        if (page == SettingsPage.ABOUT) {
        SettingsGroup(title = "Support Sage Garden") {
            Text(
                stringResource(R.string.settings_sage_garden_is_free_with_no),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
            ) { Text(stringResource(R.string.settings_buy_me_a_coffee)) }
        }
        }

        if (page == SettingsPage.ABOUT) {
        SettingsGroup(title = "Contact & feedback") {
            Text(
                stringResource(R.string.settings_found_a_bug_or_have_an),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
            ) { Text(stringResource(R.string.settings_email_us)) }
            Spacer(Modifier.height(10.dp))
            val installId = remember { getOrCreateInstallId(context) }
            Text(
                stringResource(R.string.settings_install_id_tap_to_copy_quote, installId),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Install ID", installId))
                    scope.launch { snackbarHostState.showSnackbar("Install ID copied") }
                }
            )
        }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.settings_reset_your_garden)) },
            text = { Text(stringResource(R.string.settings_this_will_permanently_delete_every_plant)) },
            confirmButton = { TextButton(onClick = { showResetDialog = false; viewModel.resetAll(); scope.launch { snackbarHostState.showSnackbar("Garden reset.") } }) { Text(stringResource(R.string.settings_reset)) } },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.care_cancel)) } }
        )
    }
    importResultDialog?.let { outcome ->
        AlertDialog(
            onDismissRequest = { importResultDialog = null },
            title = { Text(outcome.title) },
            text = { Text(outcome.message) },
            confirmButton = { TextButton(onClick = { importResultDialog = null }) { Text(stringResource(R.string.field_ok)) } }
        )
    }
}
