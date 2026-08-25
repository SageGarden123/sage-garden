import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import java.io.File
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
    var filePath by mutableStateOf(file.absolutePath)
        private set

    private fun persist() {
        val fresh = GardenStore(file)
        fresh.load()
        // Re-apply current in-memory lists onto whatever else was in the file (preserves any
        // fields this app doesn't understand, e.g. sun-map/irrigation data from a phone backup).
        fresh.plants.clear(); fresh.plants.addAll(plants)
        fresh.careLog.clear(); fresh.careLog.addAll(careLog)
        fresh.save()
    }

    fun upsertPlant(plant: Plant) {
        val idx = plants.indexOfFirst { it.id == plant.id }
        if (idx >= 0) plants[idx] = plant else plants.add(plant)
        persist()
    }

    fun deletePlant(plantId: String) {
        plants.removeAll { it.id == plantId }
        careLog.removeAll { it.plantId == plantId }
        persist()
    }

    fun logCare(plantId: String, type: String, date: Long) {
        careLog.add(CareLogEntry(plantId = plantId, type = type, date = date))
        val idx = plants.indexOfFirst { it.id == plantId }
        if (idx >= 0) {
            val p = plants[idx]
            plants[idx] = when (type) {
                "watering" -> p.copy(lastWateredDate = date)
                "fertilise" -> p.copy(lastFertilisedDate = date)
                "feed" -> p.copy(lastFedDate = date)
                else -> p.copy(lastPrunedDate = date)
            }
        }
        persist()
    }

    fun deleteCareLogEntry(entryId: String) {
        careLog.removeAll { it.id == entryId }
        persist()
    }

    fun openFile(newFile: File) {
        file = newFile
        val loaded = GardenStore(newFile).also { it.load() }
        plants.clear(); plants.addAll(loaded.plants)
        careLog.clear(); careLog.addAll(loaded.careLog)
        filePath = newFile.absolutePath
    }

    fun saveAs(newFile: File) {
        file = newFile
        filePath = newFile.absolutePath
        persist()
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
    onSaveAs: () -> Unit
) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(SageGreenDark).padding(16.dp)
    ) {
        Text("🌿 Sage Garden", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(24.dp))
        SidebarItem("📊 Dashboard", screen is Screen.Dashboard) { onSelect(Screen.Dashboard) }
        SidebarItem("🌱 Plants", screen is Screen.PlantList || screen is Screen.PlantEdit || screen is Screen.CareHistory) { onSelect(Screen.PlantList) }
        Spacer(Modifier.height(24.dp))
        SidebarItem("📂 Open backup file…", false, onOpenFile)
        SidebarItem("💾 Save as…", false, onSaveAs)
        Spacer(Modifier.weight(1f))
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
