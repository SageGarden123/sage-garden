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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

sealed class Screen {
    data object Dashboard : Screen()
    data object PlantList : Screen()
    data class PlantEdit(val plantId: String?) : Screen()
    data class CareHistory(val plantId: String) : Screen()
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
                    }
                )
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    when (val s = screen) {
                        is Screen.Dashboard -> DashboardScreen(appState.plants, onGoToPlants = { screen = Screen.PlantList })
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
                                onCancel = { screen = Screen.PlantList }
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
                    }
                }
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.padding(16.dp)) { Snackbar(it) }
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
    onSyncNow: () -> Unit
) {
    Column(
        Modifier.width(240.dp).fillMaxHeight().background(SageGreenDark).padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("🌿 Sage Garden", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(24.dp))
        SidebarItem("📊 Dashboard", screen is Screen.Dashboard) { onSelect(Screen.Dashboard) }
        SidebarItem("🌱 Plants", screen is Screen.PlantList || screen is Screen.PlantEdit || screen is Screen.CareHistory) { onSelect(Screen.PlantList) }
        Spacer(Modifier.height(24.dp))
        SidebarItem("📂 Open backup file…", false, onOpenFile)
        SidebarItem("💾 Save as…", false, onSaveAs)

        Spacer(Modifier.height(24.dp))
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

        Spacer(Modifier.height(24.dp))
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
