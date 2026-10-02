import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Same page as the Android app's Help → Support Sage Garden link — keep these in sync if it ever changes. */
const val SUPPORT_LINK_URL = "https://www.buymeacoffee.com/spokolabs"

/** Runs [action] via java.awt.Desktop (mail client / default browser) if the current platform supports it. Returns false on any failure so the caller can show a fallback message. */
private fun openInDesktop(action: (Desktop) -> Unit): Boolean = try {
    if (!Desktop.isDesktopSupported()) false else { action(Desktop.getDesktop()); true }
} catch (_: Exception) { false }

sealed class Screen {
    data object Dashboard : Screen()
    data object PlantList : Screen()
    data class PlantEdit(val plantId: String?) : Screen()
    data class CareHistory(val plantId: String) : Screen()
    data object GardenCheck : Screen()
    data object ProgressPhotos : Screen()
    data object Reports : Screen()
}

/** Local data file for a synced garden — one per garden, so switching gardens never mixes their plants. */
private fun gardenFile(gardenId: String): File =
    File(System.getProperty("user.home"), "SageGardenDesktop/gardens/${gardenId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

/**
 * Holds the open garden as Compose state and persists every mutation immediately. The desktop can
 * belong to several gardens (the linked phone's own garden plus any shared garden it has joined
 * with an invite code); each has its own local file and member token.
 */
class GardenAppState {
    val ownDeviceId = GardenSyncSettings.getOwnDeviceId()
    var linkedDeviceId by mutableStateOf(GardenSyncSettings.getLinkedDeviceId() ?: "")
        private set
    var activeGardenId by mutableStateOf(GardenSyncSettings.getActiveGardenId() ?: linkedDeviceId.ifBlank { null })
        private set
    var knownGardens by mutableStateOf<List<KnownGarden>>(emptyList())
        private set
    var pendingRequests by mutableStateOf<List<PendingRequest>>(emptyList())
        private set
    var lastSyncedAt by mutableStateOf(GardenSyncSettings.getLastSyncedAt())
        private set
    var meta by mutableStateOf(GardenMeta())
        private set
    var plan by mutableStateOf<GardenPlan?>(null)
        private set
    /** Set by any local edit; the live-update loop pushes it within a few seconds. */
    @Volatile var hasLocalChanges = false

    private var file: File = currentFile()
    private var store = GardenStore(file).also { it.load() }
    val plants = mutableStateListOf<Plant>().also { it.addAll(store.plants) }
    val careLog = mutableStateListOf<CareLogEntry>().also { it.addAll(store.careLog) }
    /** Synced from the phone, read-only here (see GardenPhoto). */
    val photos = mutableStateListOf<GardenPhoto>().also { it.addAll(store.photos) }
    private var plantTombstones = store.plantTombstones.toMutableList()
    private var careLogTombstones = store.careLogTombstones.toMutableList()
    var filePath by mutableStateOf(file.absolutePath)
        private set

    val activeGardenName: String
        get() = knownGardens.firstOrNull { it.gardenId == activeGardenId }?.name
            ?: activeGardenId?.let { GardenSyncSettings.getGardenName(it) } ?: "My Garden"

    val canEdit: Boolean
        get() = knownGardens.firstOrNull { it.gardenId == activeGardenId }?.permission != "read"

    init {
        meta = store.meta
        plan = activeGardenId?.let { GardenPlanCache.load(it) }
        runAutoBackupIfDue()
    }

    private fun currentFile(): File {
        val id = activeGardenId ?: return defaultGardenFile()
        val f = gardenFile(id)
        // Earlier versions kept the linked phone's garden in garden_data.json — carry it over once.
        if (!f.exists() && id == linkedDeviceId && defaultGardenFile().exists()) {
            f.parentFile?.mkdirs(); defaultGardenFile().copyTo(f)
        }
        return f
    }

    private fun loadFrom(newFile: File) {
        file = newFile
        store = GardenStore(newFile).also { it.load() }
        plants.clear(); plants.addAll(store.plants)
        careLog.clear(); careLog.addAll(store.careLog)
        photos.clear(); photos.addAll(store.photos)
        plantTombstones = store.plantTombstones.toMutableList()
        careLogTombstones = store.careLogTombstones.toMutableList()
        meta = store.meta
        filePath = newFile.absolutePath
    }

    /** A silent daily safety net, rotating through 7 weekday-named files (same scheme as the phone). */
    private fun runAutoBackupIfDue() {
        if (plants.isEmpty()) return
        val last = GardenSyncSettings.getLastAutoBackupAt()
        if (System.currentTimeMillis() - last < 20 * 60 * 60 * 1000L) return
        runCatching {
            val weekday = SimpleDateFormat("EEEE", Locale.US).format(Date())
            val snapshot = GardenStore(File(autoBackupDir(), "$weekday.json"))
            snapshot.plants.clear(); snapshot.plants.addAll(plants)
            snapshot.careLog.clear(); snapshot.careLog.addAll(careLog)
            snapshot.setPlantTombstones(plantTombstones)
            snapshot.setCareLogTombstones(careLogTombstones)
            snapshot.save()
        }
        GardenSyncSettings.setLastAutoBackupAt(System.currentTimeMillis())
    }

    fun listAutoBackups(): List<Pair<String, Long>> =
        autoBackupDir().listFiles()?.filter { it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") to it.lastModified() }?.sortedByDescending { it.second } ?: emptyList()

    fun restoreAutoBackup(weekday: String) {
        val backupFile = File(autoBackupDir(), "$weekday.json")
        if (!backupFile.exists()) return
        val restored = GardenStore(backupFile).also { it.load() }
        plants.clear(); plants.addAll(restored.plants)
        careLog.clear(); careLog.addAll(restored.careLog)
        plantTombstones = restored.plantTombstones.toMutableList()
        careLogTombstones = restored.careLogTombstones.toMutableList()
        persist(); hasLocalChanges = true
    }

    private fun persist() {
        val fresh = GardenStore(file)
        fresh.load()
        // Re-apply in-memory state onto whatever else is in the file (keeps fields this app doesn't use).
        fresh.plants.clear(); fresh.plants.addAll(plants)
        fresh.careLog.clear(); fresh.careLog.addAll(careLog)
        fresh.setPlantTombstones(plantTombstones)
        fresh.setCareLogTombstones(careLogTombstones)
        fresh.meta = meta
        fresh.photos = photos.toList()
        fresh.save()
    }

    fun upsertPlant(plant: Plant) {
        val stamped = plant.copy(updatedAt = System.currentTimeMillis())
        val idx = plants.indexOfFirst { it.id == stamped.id }
        if (idx >= 0) plants[idx] = stamped else plants.add(stamped)
        persist(); hasLocalChanges = true
    }

    fun deletePlant(plantId: String) {
        plants.removeAll { it.id == plantId }
        careLog.removeAll { it.plantId == plantId }
        recordTombstone(plantTombstones, plantId)
        persist(); hasLocalChanges = true
    }

    fun logCare(plantId: String, type: String, date: Long) {
        val now = System.currentTimeMillis()
        careLog.add(CareLogEntry(plantId = plantId, type = type, date = date, updatedAt = now))
        val idx = plants.indexOfFirst { it.id == plantId }
        if (idx >= 0) {
            val p = plants[idx]
            plants[idx] = when (type) {
                "watering" -> p.copy(lastWateredDate = date)
                "fertilise" -> p.copy(lastFertilisedDate = date)
                "feed" -> p.copy(lastFedDate = date)
                else -> p.copy(lastPrunedDate = date)
            }.copy(updatedAt = now)
        }
        persist(); hasLocalChanges = true
    }

    fun deleteCareLogEntry(entryId: String) {
        careLog.removeAll { it.id == entryId }
        recordTombstone(careLogTombstones, entryId)
        persist(); hasLocalChanges = true
    }

    private fun recordTombstone(list: MutableList<SyncTombstone>, id: String) {
        val existingAt = list.firstOrNull { it.id == id }?.deletedAt ?: 0L
        list.removeAll { it.id == id }
        list.add(SyncTombstone(id, maxOf(existingAt, System.currentTimeMillis())))
    }

    /** Opens any phone backup file for viewing and editing (it syncs into the open garden like any other edit). */
    fun openFile(newFile: File) = loadFrom(newFile)

    fun saveAs(newFile: File) {
        file = newFile
        filePath = newFile.absolutePath
        persist()
    }

    fun updateLinkedDeviceId(id: String) {
        linkedDeviceId = id.trim()
        GardenSyncSettings.setLinkedDeviceId(linkedDeviceId)
        if (activeGardenId == null && linkedDeviceId.isNotBlank()) switchGarden(linkedDeviceId)
    }

    fun switchGarden(gardenId: String) {
        if (gardenId == activeGardenId) return
        activeGardenId = gardenId
        GardenSyncSettings.setActiveGardenId(gardenId)
        loadFrom(currentFile())
        plan = GardenPlanCache.load(gardenId)
    }

    // ---- Network (call off the UI thread) -------------------------------------------------------

    fun refreshGardens(): CloudResult<Unit> = when (val r = Cloud.listMyGardens(ownDeviceId)) {
        is CloudResult.Ok -> {
            knownGardens = r.value.first
            pendingRequests = r.value.second
            GardenSyncSettings.setGardenNames(r.value.first.associate { it.gardenId to it.name })
            r.value.first.forEach { g -> if (g.memberToken.isNotBlank()) GardenSyncSettings.setMemberToken(g.gardenId, g.memberToken) }
            if (activeGardenId == null) r.value.first.firstOrNull()?.let { switchGarden(it.gardenId) }
            CloudResult.Ok(Unit)
        }
        is CloudResult.NotAuthorized -> r
        is CloudResult.Failed -> r
    }

    fun joinGarden(inviteCode: String, permission: String): CloudResult<String> {
        val r = Cloud.requestJoinGarden(ownDeviceId, inviteCode, permission)
        refreshGardens()
        return r
    }

    fun syncNow(): GardenSyncResult {
        val gardenId = activeGardenId ?: return GardenSyncResult.ServerError
        hasLocalChanges = false
        val result = GardenSyncClient.sync(
            ownDeviceId, gardenId, GardenSyncSettings.getMemberToken(gardenId),
            plants.toList(), careLog.toList(), plantTombstones, careLogTombstones
        )
        if (result is GardenSyncResult.NotAuthorized) GardenSyncSettings.setMemberToken(gardenId, null)
        if (result is GardenSyncResult.Success && gardenId == activeGardenId) {
            result.memberToken?.let { GardenSyncSettings.setMemberToken(gardenId, it) }
            result.signalRev?.let { GardenSyncSettings.setSeenRev(gardenId, "rev", it) }
            plants.clear(); plants.addAll(result.plants)
            plantTombstones = result.plantTombstones.toMutableList()
            careLog.clear(); careLog.addAll(result.careLog)
            careLogTombstones = result.careLogTombstones.toMutableList()
            meta = result.meta
            result.photos?.let { photos.clear(); photos.addAll(it) }
            persist()
            lastSyncedAt = System.currentTimeMillis()
            GardenSyncSettings.setLastSyncedAt(lastSyncedAt)
        }
        return result
    }

    fun pullPlan() {
        val gardenId = activeGardenId ?: return
        val token = GardenSyncSettings.getMemberToken(gardenId) ?: return
        val r = Cloud.pullPlan(ownDeviceId, gardenId, token)
        if (r is CloudResult.Ok && gardenId == activeGardenId) plan = r.value
    }

    /**
     * Live updates: a cheap change check (see Cloud.gardenSignal) — plants/care log, the garden plan
     * and memberships each have their own counter, and only what moved is fetched.
     */
    fun checkForChanges() {
        val gardenId = activeGardenId ?: return
        if (hasLocalChanges) syncNow()
        val token = GardenSyncSettings.getMemberToken(gardenId)
        if (token == null) { syncNow(); pullPlan(); return }
        when (val r = Cloud.gardenSignal(ownDeviceId, gardenId, token)) {
            is CloudResult.Ok -> {
                val s = r.value
                if (s.rev > GardenSyncSettings.getSeenRev(gardenId, "rev")) syncNow()
                if (s.planRev > GardenSyncSettings.getSeenRev(gardenId, "planRev")) {
                    pullPlan(); GardenSyncSettings.setSeenRev(gardenId, "planRev", s.planRev)
                }
                if (s.membershipRev > GardenSyncSettings.getSeenRev(gardenId, "membershipRev")) {
                    refreshGardens(); GardenSyncSettings.setSeenRev(gardenId, "membershipRev", s.membershipRev)
                }
            }
            is CloudResult.NotAuthorized -> { GardenSyncSettings.setMemberToken(gardenId, null); refreshGardens() }
            is CloudResult.Failed -> {}
        }
        if (pendingRequests.isNotEmpty()) refreshGardens()
    }

    private val satelliteCache = mutableMapOf<Pair<String, SatelliteView>, ByteArray>()

    /** Satellite imagery for the open garden, kept in memory for this session so re-exporting doesn't re-fetch. */
    fun fetchSatellite(view: SatelliteView): CloudResult<SatelliteImage> {
        val gardenId = activeGardenId ?: return CloudResult.Failed("Open a garden first.")
        satelliteCache[gardenId to view]?.let { return CloudResult.Ok(SatelliteImage(view, it)) }
        val token = GardenSyncSettings.getMemberToken(gardenId) ?: return CloudResult.Failed("Sync this garden first.")
        return when (val r = Cloud.satelliteImage(ownDeviceId, gardenId, token, view)) {
            is CloudResult.Ok -> { satelliteCache[gardenId to view] = r.value; CloudResult.Ok(SatelliteImage(view, r.value)) }
            is CloudResult.NotAuthorized -> r
            is CloudResult.Failed -> r
        }
    }

    fun reportInput() = ReportInput(
        gardenName = activeGardenName, meta = meta, plants = plants.toList(), plan = plan,
        planImage = activeGardenId?.let { GardenPlanCache.imageFile(it) },
    )
}

@Composable
fun App() {
    val appState = remember { GardenAppState() }
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showContactDialog by remember { mutableStateOf(false) }
    var showAutoBackupDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    // Live updates: fetch the garden list and do a full sync on open (and on switching garden), then
    // check for changes every 30 seconds and push local edits within a few seconds.
    LaunchedEffect(appState.activeGardenId) {
        withContext(Dispatchers.IO) {
            appState.refreshGardens()
            if (appState.activeGardenId != null) { appState.syncNow(); appState.pullPlan() }
        }
        var tick = 0
        while (true) {
            delay(5_000)
            tick++
            withContext(Dispatchers.IO) {
                if (appState.hasLocalChanges) appState.syncNow()
                if (tick % 6 == 0) appState.checkForChanges()
            }
        }
    }

    SageGardenTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    appState = appState,
                    screen = screen,
                    onSelect = { screen = it },
                    syncing = syncing,
                    onSyncNow = {
                        if (appState.activeGardenId == null) {
                            scope.launch { snackbarHostState.showSnackbar("Link your phone first — enter its Install ID (on the phone: Settings → About).") }
                        } else {
                            syncing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { appState.syncNow().also { appState.pullPlan() } }
                                syncing = false
                                snackbarHostState.showSnackbar(when (result) {
                                    is GardenSyncResult.Success -> "Synced — ${result.plants.size} plant(s) up to date"
                                    GardenSyncResult.NetworkError -> "Couldn't reach the sync server — check your connection."
                                    GardenSyncResult.ServerError -> "Sync failed — try again shortly."
                                    GardenSyncResult.NotAuthorized -> "Not authorised — ask the garden's owner if this computer was removed."
                                })
                            }
                        }
                    },
                    onJoin = { showJoinDialog = true },
                    onOpenFile = {
                        val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Garden backup JSON", "json") }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            appState.openFile(chooser.selectedFile)
                            scope.launch { snackbarHostState.showSnackbar("Opened ${chooser.selectedFile.name}") }
                        }
                    },
                    onSaveAs = {
                        val chooser = JFileChooser().apply {
                            fileFilter = FileNameExtensionFilter("Garden backup JSON", "json")
                            selectedFile = File("garden_mapper_backup.json")
                        }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            var target = chooser.selectedFile
                            if (!target.name.endsWith(".json")) target = File(target.parentFile, target.name + ".json")
                            appState.saveAs(target)
                            scope.launch { snackbarHostState.showSnackbar("Saved to ${target.name}") }
                        }
                    },
                    onRestoreAutoBackup = { showAutoBackupDialog = true },
                    onAppearance = { showAppearanceDialog = true },
                    onContact = { showContactDialog = true },
                    onSupport = {
                        if (!openInDesktop { it.browse(URI(SUPPORT_LINK_URL)) }) {
                            scope.launch { snackbarHostState.showSnackbar("Couldn't open the link — visit $SUPPORT_LINK_URL") }
                        }
                    }
                )
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    when (val s = screen) {
                        is Screen.Dashboard -> DashboardScreen(
                            appState.plants,
                            onGoToPlants = { screen = Screen.PlantList },
                            onEditPlant = { screen = Screen.PlantEdit(it.id) }
                        )
                        is Screen.PlantList -> PlantListScreen(
                            plants = appState.plants,
                            onAdd = { screen = Screen.PlantEdit(null) },
                            onEdit = { screen = Screen.PlantEdit(it.id) },
                            onHistory = { screen = Screen.CareHistory(it.id) },
                            onLogCare = { plant, type -> appState.logCare(plant.id, type, System.currentTimeMillis()) }
                        )
                        is Screen.PlantEdit -> {
                            val existing = s.plantId?.let { id -> appState.plants.firstOrNull { it.id == id } }
                            PlantEditScreen(
                                existing = existing,
                                onSave = { appState.upsertPlant(it); screen = Screen.PlantList },
                                onDelete = existing?.let { { appState.deletePlant(it.id); screen = Screen.PlantList } },
                                onCancel = { screen = Screen.PlantList },
                                onViewHistory = existing?.let { { screen = Screen.CareHistory(it.id) } },
                                photos = existing?.let { p -> appState.photos.filter { it.plantId == p.id } }.orEmpty()
                            )
                        }
                        is Screen.CareHistory -> {
                            val plant = appState.plants.firstOrNull { it.id == s.plantId }
                            if (plant != null) {
                                CareHistoryScreen(
                                    plant = plant,
                                    entries = appState.careLog.filter { it.plantId == plant.id }.sortedByDescending { it.date },
                                    onLogCare = { type -> appState.logCare(plant.id, type, System.currentTimeMillis()) },
                                    onDeleteEntry = { appState.deleteCareLogEntry(it) },
                                    onBack = { screen = Screen.PlantList }
                                )
                            } else screen = Screen.PlantList
                        }
                        is Screen.GardenCheck -> GardenCheckScreen(appState.plants)
                        is Screen.ProgressPhotos -> ProgressPhotosScreen(
                            photos = appState.photos.toList(),
                            // Same zones as the phone's Progress photos picker: the ones plants actually
                            // use. Not meta.zones — the garden's saved zone list can differ in
                            // capitalisation from plants' locations, which listed duplicates with 0 photos.
                            zones = appState.plants.map { it.location }.filter { it.isNotBlank() }.distinct()
                        )
                        is Screen.Reports -> ReportsScreen(
                            input = appState.reportInput(),
                            fetchSatellite = { view -> withContext(Dispatchers.IO) { appState.fetchSatellite(view) } },
                            planStatus = if (appState.plan == null)
                                "Your garden map and irrigation layout will appear here once the garden's owner has opened Sage Garden 1.7.1 or later on their phone (it uploads them automatically). Until then, maps use plant positions only."
                            else null,
                            onMessage = { scope.launch { snackbarHostState.showSnackbar(it) } }
                        )
                    }
                }
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.padding(16.dp)) { Snackbar(it) }

            if (showJoinDialog) JoinGardenDialog(appState, onDismiss = { showJoinDialog = false }) { message ->
                showJoinDialog = false
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
            if (showAppearanceDialog) AppearanceDialog(onDismiss = { showAppearanceDialog = false })

            if (showContactDialog) {
                AlertDialog(
                    onDismissRequest = { showContactDialog = false },
                    title = { Text("Contact & feedback") },
                    text = {
                        Column {
                            Text("Found a bug, or have an idea for the app? We'd love to hear from you.")
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "spokolabs@gmail.com", fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("spokolabs@gmail.com"), null)
                                    scope.launch { snackbarHostState.showSnackbar("Email address copied") }
                                }
                            )
                            Text("(click to copy)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = { TextButton(onClick = { showContactDialog = false }) { Text("Close") } }
                )
            }

            if (showAutoBackupDialog) {
                var selectedWeekday by remember { mutableStateOf<String?>(null) }
                val available = remember { appState.listAutoBackups() }
                val sdf = remember { SimpleDateFormat("EEEE, dd MMM yyyy, h:mm a", Locale.getDefault()) }
                AlertDialog(
                    onDismissRequest = { showAutoBackupDialog = false },
                    title = { Text("Restore from automatic backup") },
                    text = {
                        Column {
                            if (selectedWeekday == null) {
                                if (available.isEmpty()) {
                                    Text("None yet — the first one is created a day after you first open the app with data loaded.")
                                } else {
                                    Text("Runs silently once a day as a safety net — pick a snapshot to restore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(10.dp))
                                    available.forEach { (weekday, modifiedAt) ->
                                        Text(sdf.format(Date(modifiedAt)), modifier = Modifier.fillMaxWidth().clickable { selectedWeekday = weekday }.padding(vertical = 8.dp))
                                    }
                                }
                            } else {
                                Text("This replaces every plant and care-log entry currently shown with what's in this snapshot, then saves immediately. This can't be undone.")
                            }
                        }
                    },
                    confirmButton = {
                        if (selectedWeekday != null) TextButton(onClick = {
                            appState.restoreAutoBackup(selectedWeekday!!)
                            showAutoBackupDialog = false
                            scope.launch { snackbarHostState.showSnackbar("Restored from automatic backup") }
                        }) { Text("Restore") }
                    },
                    dismissButton = { TextButton(onClick = { showAutoBackupDialog = false }) { Text("Cancel") } }
                )
            }
        }
    }
}

@Composable
private fun Sidebar(
    appState: GardenAppState,
    screen: Screen,
    onSelect: (Screen) -> Unit,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    onJoin: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveAs: () -> Unit,
    onRestoreAutoBackup: () -> Unit,
    onAppearance: () -> Unit,
    onContact: () -> Unit,
    onSupport: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(260.dp).fillMaxHeight().background(cs.surfaceContainer).padding(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.Eco, contentDescription = null, tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("Sage Garden", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            }
            GardenPicker(appState, onJoin)
            Spacer(Modifier.height(12.dp))
            SidebarItem(Icons.Outlined.Dashboard, "Dashboard", screen is Screen.Dashboard) { onSelect(Screen.Dashboard) }
            SidebarItem(Icons.Outlined.LocalFlorist, "Plants", screen is Screen.PlantList || screen is Screen.PlantEdit || screen is Screen.CareHistory) { onSelect(Screen.PlantList) }
            SidebarItem(Icons.Outlined.FactCheck, "Garden check", screen is Screen.GardenCheck) { onSelect(Screen.GardenCheck) }
            SidebarItem(Icons.Outlined.PhotoLibrary, "Progress photos", screen is Screen.ProgressPhotos) { onSelect(Screen.ProgressPhotos) }
            SidebarItem(Icons.Outlined.Summarize, "Reports & map", screen is Screen.Reports) { onSelect(Screen.Reports) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = cs.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text("Sync", style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                "Updates arrive automatically while the app is open.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            OutlinedTextField(
                value = appState.linkedDeviceId,
                onValueChange = { appState.updateLinkedDeviceId(it) },
                label = { Text("Your phone's Install ID") },
                supportingText = { Text("On the phone: Settings → About") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSyncNow, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Sync, contentDescription = null); Spacer(Modifier.width(8.dp))
                Text(if (syncing) "Syncing…" else "Sync now")
            }
            if (appState.lastSyncedAt > 0) {
                Text(
                    "Last synced ${SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(appState.lastSyncedAt))}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = cs.outlineVariant)
            SidebarItem(Icons.Outlined.FolderOpen, "Open backup file…", false, onOpenFile)
            SidebarItem(Icons.Outlined.Save, "Save as…", false, onSaveAs)
            SidebarItem(Icons.Outlined.History, "Restore automatic backup…", false, onRestoreAutoBackup)
        }
        HorizontalDivider(color = cs.outlineVariant)
        SidebarItem(Icons.Outlined.Palette, "Appearance", false, onAppearance)
        SidebarItem(Icons.Outlined.Email, "Contact & feedback", false, onContact)
        SidebarItem(Icons.Outlined.Coffee, "Buy me a coffee", false, onSupport)
        Text(
            "Data file: ${appState.filePath}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun GardenPicker(appState: GardenAppState, onJoin: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Switch garden") { expanded = true }
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Yard, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Garden", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(appState.activeGardenName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            appState.knownGardens.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.name + if (g.permission == "read") " (view only)" else "") },
                    leadingIcon = { if (g.gardenId == appState.activeGardenId) Icon(Icons.Outlined.Check, contentDescription = "Current garden") },
                    onClick = { expanded = false; appState.switchGarden(g.gardenId) }
                )
            }
            appState.pendingRequests.forEach { p ->
                DropdownMenuItem(text = { Text("${p.name} — waiting for approval") }, onClick = {}, enabled = false,
                    leadingIcon = { Icon(Icons.Outlined.HourglassEmpty, contentDescription = null) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Join a garden…") }, leadingIcon = { Icon(Icons.Outlined.GroupAdd, contentDescription = null) },
                onClick = { expanded = false; onJoin() })
        }
    }
}

@Composable
private fun JoinGardenDialog(appState: GardenAppState, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf("write") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a garden") },
        text = {
            Column {
                Text("Ask the garden's owner for their invite code (on their phone: Settings → This garden → Share).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase(); error = null }, label = { Text("Invite code") }, singleLine = true, isError = error != null,
                    supportingText = { error?.let { Text(it) } })
                Spacer(Modifier.height(8.dp))
                listOf("write" to "Ask to edit", "read" to "View only").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().selectable(permission == value, role = Role.RadioButton) { permission = value }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = permission == value, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = code.isNotBlank() && !working, onClick = {
                working = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { appState.joinGarden(code, permission) }
                    working = false
                    when (r) {
                        is CloudResult.Ok -> onDone(if (r.value == "approved") "Joined — pick the garden from the list." else "Request sent. It'll appear in your gardens once the owner approves it.")
                        is CloudResult.Failed -> error = r.reason
                        is CloudResult.NotAuthorized -> error = "Not authorised."
                    }
                }
            }) { Text(if (working) "Sending…" else "Send request") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AppearanceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                ThemeMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().selectable(Appearance.themeMode == m, role = Role.RadioButton) { Appearance.update(mode = m) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = Appearance.themeMode == m, onClick = null); Spacer(Modifier.width(8.dp)); Text(m.label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Colours", style = MaterialTheme.typography.labelLarge)
                AppPalette.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().selectable(Appearance.palette == p, role = Role.RadioButton) { Appearance.update(palette = p) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = Appearance.palette == p, onClick = null); Spacer(Modifier.width(8.dp)); Text(p.label, modifier = Modifier.weight(1f))
                        val scheme = paletteScheme(p, dark = false)
                        listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary).forEach { c ->
                            Surface(color = c, shape = MaterialTheme.shapes.small, modifier = Modifier.size(18.dp).padding(1.dp)) {}
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(44.dp)
    )
}
