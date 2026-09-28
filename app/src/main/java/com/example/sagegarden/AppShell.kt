@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.material3.MaterialTheme

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dropbox.core.android.Auth
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

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
                    Text(plant.id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) { Text("✕") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit plant") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
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
    val pathViewModel: IrrigationPathViewModel = viewModel(
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

    // A silent safety net beneath the manual "Export to device"/Dropbox backup buttons — no-ops
    // unless it's genuinely been a day since the last run for whichever garden is active. The short
    // delay gives Room's first query a moment to land so this doesn't run against an empty plants
    // list before real data has loaded.
    LaunchedEffect(ActiveGardenState.activeGardenId) {
        delay(3_000L)
        AutoBackupScheduler.runIfDue(
            context, effectiveGardenId(context),
            viewModel.plants.value, pathViewModel.paths.value, wateringViewModel.events.value
        )
    }

    // Event-driven sync — replaces the old "sync the active garden every 60s" loop:
    //  • OUTBOUND: any local plant/care-log change in the active garden (watering, edits, deletes,
    //    imports — every write path, without each one having to remember to call sync) shows up as
    //    a change in the Room fingerprint below and is pushed ~1.5s later. The fingerprint captured
    //    inside each sync's own merge transaction is what stops the merge's writes re-triggering it.
    //    (Edits to a NON-active garden are already pushed at their call sites — syncIfNotActiveGarden.)
    //  • INBOUND: RealtimeGardenSync listens to each garden's server-side change signal while the app
    //    is in the foreground and syncs only when another device actually changed something.
    //  • SAFETY NET: a slow loop (15 min while the listener is live, 60s — the old behaviour — when it
    //    isn't, e.g. Anonymous Auth unavailable) also keeps the known-gardens cache fresh, which the
    //    widget and reminder worker read via allKnownGardenIds.
    LaunchedEffect(ActiveGardenState.activeGardenId) {
        val gardenId = effectiveGardenId(context)
        val db = AppDatabase.getInstance(context)
        combine(db.plantDao().syncFingerprint(gardenId), db.careLogDao().syncFingerprint(gardenId)) { p, c -> "$p|$c" }
            .debounce(1_500L)
            .collect { fingerprint ->
                if (fingerprint != GardenSyncClient.lastSyncedFingerprint(gardenId)) {
                    GardenSyncClient.sync(context, getOrCreateInstallId(context), gardenId)
                }
            }
    }
    // Address/zones live in prefs, not Room, so the fingerprint above can't see them — the Help
    // screen's address/zone editors bump this counter instead (never sync itself, which also writes
    // them, so a sync can't trigger another sync).
    LaunchedEffect(GardenSettingsEdits.count) {
        if (GardenSettingsEdits.count > 0) GardenSyncClient.sync(context, getOrCreateInstallId(context), effectiveGardenId(context))
    }
    LaunchedEffect(ActiveGardenState.activeGardenId) {
        while (true) {
            GardenMembershipClient.refreshKnownGardens(context)
            RealtimeGardenSync.refreshListeners(context)
            val gardenId = effectiveGardenId(context)
            delay(if (RealtimeGardenSync.isLive(gardenId)) 15 * 60_000L else 60_000L)
            GardenSyncClient.sync(context, getOrCreateInstallId(context), gardenId)
        }
    }
    // Listeners only while visible (no open connection in the background), plus an immediate sync on
    // resume in case anything changed while the app was in the background with no listener attached.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> RealtimeGardenSync.start(context, scope)
                Lifecycle.Event.ON_STOP -> RealtimeGardenSync.stop()
                Lifecycle.Event.ON_RESUME -> scope.launch { GardenSyncClient.sync(context, getOrCreateInstallId(context), effectiveGardenId(context)) }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            RealtimeGardenSync.stop()
        }
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
            // Switch into the plant's own garden first, when known — otherwise resolvePlantById's
            // "prefer the active garden" lookup (see its doc comment) could resolve to a DIFFERENT
            // garden's same-id plant if one happens to be active/colliding, exactly the bug this was
            // added to close. Also just correct UX: tapping a shared garden's plant from the widget
            // should land you in that garden, not leave you on your own while viewing someone else's.
            val targetGardenId = PendingPlantEditState.gardenId
            if (!targetGardenId.isNullOrBlank() && targetGardenId != effectiveGardenId(context)) {
                val installId = getOrCreateInstallId(context)
                GardenMembershipStore.setActiveGardenId(context, if (targetGardenId == installId) null else targetGardenId)
            }
            navController.navigate("form_edit/$id")
            PendingPlantEditState.plantId = null
            PendingPlantEditState.gardenId = null
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
                    startOnCustom = GardenSettings.active(context).usingCustomMap,
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
                    // Returns to a *fresh* form_edit instance (not just popping back) so it reloads
                    // the plant from the DB — the form's own in-memory lat/lng state doesn't know
                    // about the location placement just wrote directly to the DB. This also routes
                    // the user back through the form's own "Save plant" button (and its blank-name
                    // check) instead of dropping them on the list, where a plant saved mid-placement
                    // with no name yet could otherwise go unnoticed.
                    placementModeForPlantId = id, onPlacementSaved = { navController.navigate("form_edit/$id") { popUpTo("map") } },
                    startOnCustom = false
                )
            }
            composable("place_custom/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                MapTabScreen(
                    viewModel = viewModel, onMarkerClick = { },
                    placementModeForPlantId = id, onPlacementSaved = { navController.navigate("form_edit/$id") { popUpTo("map") } },
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
                NotificationDetailsScreen(
                    type = notifType,
                    onBack = { navController.popBackStack() },
                    onOpenZone = { location -> navController.navigate("location_photos/${Uri.encode(location)}") }
                )
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
