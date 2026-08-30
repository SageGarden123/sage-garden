import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
const val SUPPORT_LINK_URL = "https://www.buymeacoffee.com/sagegarden"

/** Runs [action] via java.awt.Desktop (mail client / default browser) if the current platform supports it. Returns false on any failure so the caller can show a fallback message, matching the Android app's pattern for the same links. */
private fun openInDesktop(action: (Desktop) -> Unit): Boolean {
    return try {
        if (!Desktop.isDesktopSupported()) return false
        action(Desktop.getDesktop())
        true
    } catch (_: Exception) {
        false
    }
}

sealed class Screen {
    data object Dashboard : Screen()
    data object PlantList : Screen()
    data class PlantEdit(val plantId: String?) : Screen()
    data class CareHistory(val plantId: String) : Screen()
    data object Audit : Screen()
}

/** Holds the loaded garden data as Compose state and persists every mutation immediately — a
 * single local user on a single machine has no need for debounced/batched writes. */
class GardenAppState(private var file: File) {
    private val store = GardenStore(file).also { it.load() }
    val plants = mutableStateListOf<Plant>().also { it.addAll(store.plants) }
    val careLog = mutableStateListOf<CareLogEntry>().also { it.addAll(store.careLog) }
    private var plantTombstones = store.plantTombstones.toMutableList()
    private var careLogTombstones = store.careLogTombstones.toMutableList()
    var filePath by mutableStateOf(file.absolutePath)
        private set
    var linkedDeviceId by mutableStateOf(GardenSyncSettings.getLinkedDeviceId() ?: "")
        private set
    var lastSyncedAt by mutableStateOf(GardenSyncSettings.getLastSyncedAt())
        private set

    init {
        runAutoBackupIfDue()
    }

    /** A silent safety net beneath "Save as..." — no setup needed, runs at most once a day, and
     * rotates through 7 weekday-named files rather than growing forever (mirrors the same scheme
     * on the Android side's AutoBackupScheduler). Skips entirely if there's nothing loaded yet, so
     * a brand-new/empty file never overwrites a real snapshot from a previous day. */
    private fun runAutoBackupIfDue() {
        if (plants.isEmpty()) return
        val last = GardenSyncSettings.getLastAutoBackupAt()
        if (System.currentTimeMillis() - last < 20 * 60 * 60 * 1000L) return
        runCatching {
            val weekday = java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(java.util.Date())
            val backupFile = File(autoBackupDir(), "$weekday.json")
            val snapshot = GardenStore(backupFile)
            snapshot.plants.clear(); snapshot.plants.addAll(plants)
            snapshot.careLog.clear(); snapshot.careLog.addAll(careLog)
            snapshot.setPlantTombstones(plantTombstones)
            snapshot.setCareLogTombstones(careLogTombstones)
            snapshot.save()
        }
        GardenSyncSettings.setLastAutoBackupAt(System.currentTimeMillis())
    }

    /** Every automatic backup slot present, newest first — up to 7 (one per weekday), for a restore picker. */
    fun listAutoBackups(): List<Pair<String, Long>> =
        autoBackupDir().listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") to it.lastModified() }
            ?.sortedByDescending { it.second }
            ?: emptyList()

    /** Restores the automatic backup slot named [weekday] (from [listAutoBackups]) as the current
     * garden state, and saves it to the main file immediately. */
    fun restoreAutoBackup(weekday: String) {
        val backupFile = File(autoBackupDir(), "$weekday.json")
        if (!backupFile.exists()) return
        val restored = GardenStore(backupFile).also { it.load() }
        plants.clear(); plants.addAll(restored.plants)
        careLog.clear(); careLog.addAll(restored.careLog)
        plantTombstones = restored.plantTombstones.toMutableList()
        careLogTombstones = restored.careLogTombstones.toMutableList()
        persist()
    }

    private fun persist() {
        val fresh = GardenStore(file)
        fresh.load()
        // Re-apply current in-memory lists onto whatever else was in the file (preserves any
        // fields this app doesn't understand, e.g. sun-map/irrigation data from a phone backup).
        fresh.plants.clear(); fresh.plants.addAll(plants)
        fresh.careLog.clear(); fresh.careLog.addAll(careLog)
        fresh.setPlantTombstones(plantTombstones)
        fresh.setCareLogTombstones(careLogTombstones)
        fresh.save()
    }

    fun upsertPlant(plant: Plant) {
        val stamped = plant.copy(updatedAt = System.currentTimeMillis())
        val idx = plants.indexOfFirst { it.id == stamped.id }
        if (idx >= 0) plants[idx] = stamped else plants.add(stamped)
        persist()
    }

    fun deletePlant(plantId: String) {
        plants.removeAll { it.id == plantId }
        careLog.removeAll { it.plantId == plantId }
        recordTombstone(plantTombstones, plantId)
        persist()
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
        persist()
    }

    fun deleteCareLogEntry(entryId: String) {
        careLog.removeAll { it.id == entryId }
        recordTombstone(careLogTombstones, entryId)
        persist()
    }

    private fun recordTombstone(list: MutableList<SyncTombstone>, id: String) {
        val now = System.currentTimeMillis()
        val existingAt = list.firstOrNull { it.id == id }?.deletedAt ?: 0L
        list.removeAll { it.id == id }
        list.add(SyncTombstone(id, maxOf(existingAt, now)))
    }

    fun openFile(newFile: File) {
        file = newFile
        val loaded = GardenStore(newFile).also { it.load() }
        plants.clear(); plants.addAll(loaded.plants)
        careLog.clear(); careLog.addAll(loaded.careLog)
        plantTombstones = loaded.plantTombstones.toMutableList()
        careLogTombstones = loaded.careLogTombstones.toMutableList()
        filePath = newFile.absolutePath
    }

    fun saveAs(newFile: File) {
        file = newFile
        filePath = newFile.absolutePath
        persist()
    }

    fun updateLinkedDeviceId(id: String) {
        linkedDeviceId = id
        GardenSyncSettings.setLinkedDeviceId(id)
    }

    /**
     * Blocking network call — callers must invoke this off the UI thread (see App()'s "Sync now"
     * handler). The server response is already the full authoritative state for both collections
     * (see syncGarden.ts), so applying it is a plain replace, not a merge — this client does no
     * merge logic of its own.
     */
    fun syncNow(): GardenSyncResult {
        val result = GardenSyncClient.sync(linkedDeviceId, plants.toList(), careLog.toList(), plantTombstones, careLogTombstones)
        if (result is GardenSyncResult.Success) {
            plants.clear(); plants.addAll(result.plants)
            plantTombstones = result.plantTombstones.toMutableList()
            careLog.clear(); careLog.addAll(result.careLog)
            careLogTombstones = result.careLogTombstones.toMutableList()
            persist()
            lastSyncedAt = System.currentTimeMillis()
            GardenSyncSettings.setLastSyncedAt(lastSyncedAt)
        }
        return result
    }
}

@Composable
fun App() {
    val appState = remember { GardenAppState(defaultGardenFile()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showContactDialog by remember { mutableStateOf(false) }
    var showAutoBackupDialog by remember { mutableStateOf(false) }

    SageGardenTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                var syncing by remember { mutableStateOf(false) }
                Sidebar(
                    screen = screen,
                    onSelect = { screen = it },
                    filePath = appState.filePath,
                    onOpenFile = {
                        val chooser = JFileChooser().apply {
                            fileFilter = FileNameExtensionFilter("Garden backup JSON", "json")
                        }
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
                    linkedDeviceId = appState.linkedDeviceId,
                    onLinkedDeviceIdChange = { appState.updateLinkedDeviceId(it) },
                    lastSyncedAt = appState.lastSyncedAt,
                    syncing = syncing,
                    onSyncNow = {
                        if (appState.linkedDeviceId.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Enter the phone's Install ID above first (Help → Sync with other devices on the phone).") }
                        } else {
                            syncing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { appState.syncNow() }
                                syncing = false
                                val message = when (result) {
                                    is GardenSyncResult.Success -> "Synced — ${result.plants.size} plant(s) up to date"
                                    GardenSyncResult.NetworkError -> "Couldn't reach the sync server — check your connection."
                                    GardenSyncResult.ServerError -> "Sync failed — try again shortly."
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    },
                    onContact = { showContactDialog = true },
                    onRestoreAutoBackup = { showAutoBackupDialog = true },
                    onSupport = {
                        val opened = openInDesktop { desktop -> desktop.browse(URI(SUPPORT_LINK_URL)) }
                        if (!opened) scope.launch { snackbarHostState.showSnackbar("Couldn't open the link — visit $SUPPORT_LINK_URL") }
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
                                onViewHistory = existing?.let { { screen = Screen.CareHistory(it.id) } }
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
                            } else {
                                screen = Screen.PlantList
                            }
                        }
                        is Screen.Audit -> AuditScreen(appState.plants)
                    }
                }
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.padding(16.dp)) { Snackbar(it) }

            if (showContactDialog) {
                AlertDialog(
                    onDismissRequest = { showContactDialog = false },
                    title = { Text("Contact & feedback") },
                    text = {
                        Column {
                            Text("Found a bug, or have an idea for the app? We'd love to hear from you.", fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "gardenwizardry685@gmail.com",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                    clipboard.setContents(StringSelection("gardenwizardry685@gmail.com"), null)
                                    scope.launch { snackbarHostState.showSnackbar("Email address copied") }
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("(click to copy)", fontSize = 11.sp, color = Color.Gray)
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
                                    Text("None yet — the first one is created a day after you first open the app with data loaded.", fontSize = 13.sp)
                                } else {
                                    Text("Runs silently once a day as a safety net — pick a snapshot to restore.", fontSize = 12.sp, color = Color.Gray)
                                    Spacer(Modifier.height(10.dp))
                                    available.forEach { (weekday, modifiedAt) ->
                                        Text(
                                            sdf.format(Date(modifiedAt)),
                                            fontSize = 13.sp,
                                            modifier = Modifier.fillMaxWidth().clickable { selectedWeekday = weekday }.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            } else {
                                Text("This replaces every plant and care-log entry currently shown with what's in this snapshot, then saves immediately. This can't be undone.", fontSize = 13.sp)
                            }
                        }
                    },
                    confirmButton = {
                        if (selectedWeekday != null) {
                            TextButton(onClick = {
                                appState.restoreAutoBackup(selectedWeekday!!)
                                showAutoBackupDialog = false
                                scope.launch { snackbarHostState.showSnackbar("Restored from automatic backup") }
                            }) { Text("Restore") }
                        }
                    },
                    dismissButton = { TextButton(onClick = { showAutoBackupDialog = false }) { Text("Cancel") } }
                )
            }
        }
    }
}

@Composable
private fun Sidebar(
    screen: Screen,
    onSelect: (Screen) -> Unit,
    filePath: String,
    onOpenFile: () -> Unit,
    onSaveAs: () -> Unit,
    linkedDeviceId: String,
    onLinkedDeviceIdChange: (String) -> Unit,
    lastSyncedAt: Long,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    onContact: () -> Unit,
    onSupport: () -> Unit,
    onRestoreAutoBackup: () -> Unit
) {
    Column(
        Modifier.width(240.dp).fillMaxHeight().background(SageGreenDark).padding(16.dp)
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("🌿 Sage Garden", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(24.dp))
            SidebarItem("📊 Dashboard", screen is Screen.Dashboard) { onSelect(Screen.Dashboard) }
            SidebarItem("🌱 Plants", screen is Screen.PlantList || screen is Screen.PlantEdit || screen is Screen.CareHistory) { onSelect(Screen.PlantList) }
            SidebarItem("🔍 Audit", screen is Screen.Audit) { onSelect(Screen.Audit) }
            Spacer(Modifier.height(24.dp))
            SidebarItem("📂 Open backup file…", false, onOpenFile)
            SidebarItem("💾 Save as…", false, onSaveAs)
            SidebarItem("🕑 Restore automatic backup…", false, onRestoreAutoBackup)

            Spacer(Modifier.height(36.dp))
            Text("Sync with phone", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Enter the phone's Install ID (Help → Sync with other devices, on the phone).",
                color = SageCream, fontSize = 10.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = linkedDeviceId,
                onValueChange = onLinkedDeviceIdChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSyncNow, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                Text(if (syncing) "Syncing…" else "Sync now")
            }
            if (lastSyncedAt > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last synced: ${SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(lastSyncedAt))}",
                    color = SageCream, fontSize = 10.sp
                )
            }
        }

        HorizontalDivider(color = SageCream.copy(alpha = 0.3f))
        Spacer(Modifier.height(12.dp))
        SidebarItem("✉️ Contact & feedback", false, onContact)
        SidebarItem("☕ Buy me a coffee", false, onSupport)
        Spacer(Modifier.height(16.dp))
        Text(
            "Data file:\n$filePath",
            color = SageCream, fontSize = 10.sp
        )
    }
}

@Composable
private fun SidebarItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) SageGreen else Color.Transparent)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
