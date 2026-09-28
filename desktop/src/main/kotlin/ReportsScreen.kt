import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Asks where to save, suggesting [suggestedName]; returns null if cancelled. */
private fun chooseSaveFile(suggestedName: String, description: String, extension: String): File? {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(description, extension)
        selectedFile = File(System.getProperty("user.home"), suggestedName)
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val f = chooser.selectedFile
    return if (f.name.lowercase().endsWith(".$extension")) f else File(f.parentFile, "${f.name}.$extension")
}

/** Writes one file per map: the first to [target], others alongside with the map type in the name. */
private fun eachMapFile(target: File, kinds: List<MapKind>, write: (MapKind, File) -> Unit) {
    kinds.forEachIndexed { i, kind ->
        val file = if (i == 0) target else File(target.parentFile, "${target.nameWithoutExtension} - ${kind.label}.${target.extension}")
        write(kind, file)
    }
}

private fun fileSafe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifBlank { "Garden" }

@Composable
fun ReportsScreen(
    input: ReportInput,
    fetchSatellite: suspend (SatelliteView) -> CloudResult<SatelliteImage>,
    planStatus: String?,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var showSunZones by remember { mutableStateOf(false) }
    var numbered by remember { mutableStateOf(true) }
    val hasPlan = input.hasPlanImage()
    val satelliteView = remember(input.plants, input.meta) { satelliteViewFor(input) }
    var includePlan by remember(hasPlan) { mutableStateOf(hasPlan) }
    var includeSatellite by remember { mutableStateOf(false) }
    var satellite by remember { mutableStateOf<SatelliteImage?>(null) }
    var satelliteStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(includeSatellite, satelliteView) {
        if (!includeSatellite || satelliteView == null || satellite?.view == satelliteView) return@LaunchedEffect
        satelliteStatus = "Fetching satellite imagery…"
        when (val r = fetchSatellite(satelliteView)) {
            is CloudResult.Ok -> { satellite = r.value; satelliteStatus = null }
            is CloudResult.Failed -> { satelliteStatus = r.reason; includeSatellite = false }
            is CloudResult.NotAuthorized -> { satelliteStatus = "Not authorised for this garden."; includeSatellite = false }
        }
    }
    val kinds = buildList {
        if (includePlan && hasPlan) add(MapKind.PLAN)
        if (includeSatellite && satellite != null) add(MapKind.SATELLITE)
    }.ifEmpty { listOf(MapKind.POSITIONS) }
    val exportInput = input.copy(satellite = satellite)
    var previews by remember { mutableStateOf<List<Pair<MapKind, ImageBitmap>>>(emptyList()) }
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(input.now))
    val base = fileSafe(input.gardenName)
    val mapOptions = MapOptions(width = 1400, numbered = numbered, showSunZones = showSunZones, showLegend = true)

    LaunchedEffect(input, satellite, kinds, showSunZones, numbered) {
        previews = withContext(Dispatchers.Default) {
            kinds.mapNotNull { kind ->
                runCatching {
                    kind to org.jetbrains.skia.Image.makeFromEncoded(renderPngBytes(mapSvg(exportInput, mapOptions.copy(kind = kind)), 1100f)).toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }

    fun export(label: String, name: String, description: String, ext: String, write: (File) -> Unit) {
        val target = chooseSaveFile(name, description, ext) ?: return
        busy = label
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { write(target) } }
            busy = null
            result.onSuccess {
                onMessage("Saved ${target.name}")
                runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(target) }
            }.onFailure { onMessage("Couldn't create ${target.name}: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Reports & map", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Printable reports for ${input.gardenName}, made from the latest synced data.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (planStatus != null) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(planStatus, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReportCard(
                Icons.Outlined.Summarize, "Garden report",
                "At a glance, garden health and a full-page map with a numbered plant index.",
                busy == "garden", Modifier.weight(1f)
            ) { export("garden", "$base - Garden report - $stamp.pdf", "PDF", "pdf") { writePdf(gardenReportXhtml(exportInput, kinds), it) } }
            ReportCard(
                Icons.Outlined.Checklist, "Plant care checklist",
                "What to water, prune, fertilise and feed over the next 2 weeks — grouped by zone, with tick boxes.",
                busy == "care", Modifier.weight(1f)
            ) { export("care", "$base - Care checklist - $stamp.pdf", "PDF", "pdf") { writePdf(careReportXhtml(input), it) } }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Maps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = numbered, onCheckedChange = { numbered = it }); Text("Number plants")
                Spacer(Modifier.width(12.dp))
                Checkbox(checked = showSunZones, onCheckedChange = { showSunZones = it }, enabled = includePlan && hasPlan); Text("Show sun zones")
            }
        }
        Text("Choose which maps go into the garden report and map exports:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includePlan && hasPlan, onCheckedChange = { includePlan = it }, enabled = hasPlan)
            Text(if (hasPlan) "Your garden map — with irrigation lines and zones" else "Your garden map — none uploaded yet")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeSatellite, onCheckedChange = { includeSatellite = it; satelliteStatus = null }, enabled = satelliteView != null)
            Text(if (satelliteView != null) "Satellite map (Google) — plants and legend only" else "Satellite map — place plants on the map or set the garden's address first")
        }
        satelliteStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp)) }
        if (!(includePlan && hasPlan) && satellite == null) {
            Text("With neither selected, maps show plant positions drawn to scale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { export("map-pdf", "$base - Map - $stamp.pdf", "PDF", "pdf") { writePdf(mapExportXhtml(exportInput, mapOptions, kinds), it) } }, enabled = busy == null) { Text("Export PDF") }
            // Image formats hold one map each: the first chosen map is saved under the chosen name,
            // any other as a sibling file with the map type added (e.g. "… - Satellite map.png").
            OutlinedButton(onClick = { export("map-png", "$base - Map - $stamp.png", "PNG image", "png") { f -> eachMapFile(f, kinds) { k, t -> writePng(mapSvg(exportInput, mapOptions.copy(kind = k)), t) } } }, enabled = busy == null) { Text("Export PNG") }
            OutlinedButton(onClick = { export("map-svg", "$base - Map - $stamp.svg", "SVG image", "svg") { f -> eachMapFile(f, kinds) { k, t -> writeSvg(mapSvg(exportInput, mapOptions.copy(kind = k)), t) } } }, enabled = busy == null) { Text("Export SVG") }
            if (busy != null) CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(12.dp))
        if (previews.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        previews.forEach { (kind, image) ->
            Text(kind.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Image(
                image, contentDescription = "Preview: ${kind.label} with legend",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportCard(icon: ImageVector, title: String, description: String, busy: Boolean, modifier: Modifier, onCreate: () -> Unit) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCreate, enabled = !busy) { Text(if (busy) "Creating…" else "Create PDF") }
        }
    }
}
