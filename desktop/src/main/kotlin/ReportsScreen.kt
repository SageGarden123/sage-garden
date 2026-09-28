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

private fun fileSafe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifBlank { "Garden" }

@Composable
fun ReportsScreen(input: ReportInput, planStatus: String?, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var showSunZones by remember { mutableStateOf(false) }
    var numbered by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(input.now))
    val base = fileSafe(input.gardenName)
    val mapOptions = MapOptions(width = 1400, numbered = numbered, showSunZones = showSunZones, showLegend = true)

    LaunchedEffect(input, showSunZones, numbered) {
        preview = withContext(Dispatchers.Default) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(renderPngBytes(mapSvg(input, mapOptions), 1100f)).toComposeImageBitmap()
            }.getOrNull()
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
            ) { export("garden", "$base - Garden report - $stamp.pdf", "PDF", "pdf") { writePdf(gardenReportXhtml(input), it) } }
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
            Text("Garden map", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = numbered, onCheckedChange = { numbered = it }); Text("Number plants")
                Spacer(Modifier.width(12.dp))
                Checkbox(checked = showSunZones, onCheckedChange = { showSunZones = it }); Text("Show sun zones")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { export("map-pdf", "$base - Map - $stamp.pdf", "PDF", "pdf") { writePdf(mapExportXhtml(input, mapOptions), it) } }, enabled = busy == null) { Text("Export PDF") }
            OutlinedButton(onClick = { export("map-png", "$base - Map - $stamp.png", "PNG image", "png") { writePng(mapSvg(input, mapOptions), it) } }, enabled = busy == null) { Text("Export PNG") }
            OutlinedButton(onClick = { export("map-svg", "$base - Map - $stamp.svg", "SVG image", "svg") { writeSvg(mapSvg(input, mapOptions), it) } }, enabled = busy == null) { Text("Export SVG") }
            if (busy != null) CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(12.dp))
        val image = preview
        if (image == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Image(
                image, contentDescription = "Preview of the garden map with legend",
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
