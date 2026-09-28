@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.ui.res.stringResource

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*

import androidx.compose.material3.MaterialTheme

import android.util.Log
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dropbox.core.v2.files.WriteMode
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// DROPBOX FOLDER ENTRY
// ============================================================================

data class DropboxFolderEntry(val name: String, val path: String)

/** Lists subfolders at a given path. Empty string "" = root. */
suspend fun listDropboxFolders(context: Context, path: String): Result<List<DropboxFolderEntry>> {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext Result.failure(Exception("Not connected to Dropbox"))
            val result = client.files().listFolder(path)
            val folders = result.entries
                .filterIsInstance<com.dropbox.core.v2.files.FolderMetadata>()
                .map { DropboxFolderEntry(it.name, it.pathLower ?: "") }
                .sortedBy { it.name.lowercase() }
            Result.success(folders)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Counts image files at a given path — used by "Test connection". */
suspend fun countDropboxImages(context: Context, path: String): Result<Int> {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext Result.failure(Exception("Not connected to Dropbox"))
            val result = client.files().listFolder(path)
            val imageCount = result.entries.count { entry ->
                entry is com.dropbox.core.v2.files.FileMetadata &&
                        entry.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
            }
            Result.success(imageCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ============================================================================
// PHOTO PICKER DIALOG
// ============================================================================
sealed class DropboxEntry {
    data class Folder(val name: String, val path: String) : DropboxEntry()
    data class Image(val name: String, val path: String, val clientModified: Long?) : DropboxEntry()
    data class File(val name: String, val path: String) : DropboxEntry()
}

internal val DROPBOX_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

/** [fileExtensions] controls which non-folder entries show at all (case-insensitive, no dot) — an
 * image-type extension always becomes [DropboxEntry.Image] regardless of what's requested (matching
 * every existing image-picker call site's behavior unchanged), anything else requested becomes a
 * generic [DropboxEntry.File] (used by the CSV picker). */
suspend fun listDropboxEntries(
    context: Context, path: String, fileExtensions: Set<String> = DROPBOX_IMAGE_EXTENSIONS
): Result<List<DropboxEntry>> = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext Result.failure(Exception("Not connected to Dropbox"))
        val entries = client.files().listFolder(path).entries.mapNotNull { entry ->
            when (entry) {
                is com.dropbox.core.v2.files.FolderMetadata -> DropboxEntry.Folder(entry.name, entry.pathLower ?: "")
                is com.dropbox.core.v2.files.FileMetadata -> {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in DROPBOX_IMAGE_EXTENSIONS && ext in fileExtensions ->
                            DropboxEntry.Image(entry.name, entry.pathLower ?: "", entry.clientModified.time)
                        ext in fileExtensions -> DropboxEntry.File(entry.name, entry.pathLower ?: "")
                        else -> null
                    }
                }
                else -> null
            }
        }.sortedWith(compareBy({ it !is DropboxEntry.Folder }, {
            when (it) { is DropboxEntry.Folder -> it.name.lowercase(); is DropboxEntry.Image -> it.name.lowercase(); is DropboxEntry.File -> it.name.lowercase() }
        }))
        Result.success(entries)
    } catch (e: Exception) { Result.failure(e) }
}

suspend fun getDropboxDirectLink(context: Context, filePath: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: run {
            android.util.Log.w("DropboxLink", "getDropboxDirectLink: not connected to Dropbox")
            return@withContext null
        }
        // Uses the same retrying create-or-lookup as a freshly uploaded photo (fetchOrCreateSharedLink)
        // — an existing file being picked shouldn't usually hit the same "link not visible yet" lag a
        // brand-new upload can, but there's no reason this path should be less resilient than that one.
        val link = fetchOrCreateSharedLink(client, filePath)
        if (link == null) android.util.Log.w("DropboxLink", "getDropboxDirectLink: no link resolved for path='$filePath'")
        link?.let { toDirectDropboxLink(it) }
    } catch (e: Exception) {
        android.util.Log.w("DropboxLink", "getDropboxDirectLink threw for path='$filePath'", e)
        null
    }
}

@Composable
fun DropboxImagePickerDialog(
    context: Context, onDismiss: () -> Unit, onImageSelected: (String, Long?) -> Unit,
    initialPath: String = "", onPathChanged: ((String) -> Unit)? = null
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var currentLabel by remember { mutableStateOf(initialPath.trim('/').substringAfterLast("/").ifBlank { "Dropbox (root)" }) }
    var entries by remember { mutableStateOf<List<DropboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Reconstructs breadcrumbs for an initialPath so "‹ Back" still works from a remembered
    // starting folder — segment names are just the path's own segments (Dropbox doesn't give us
    // the real display name without an extra lookup per level, but the path segment reads fine).
    val pathStack = remember {
        val stack = mutableStateListOf<Pair<String, String>>()
        val segments = initialPath.trim('/').split("/").filter { it.isNotBlank() }
        if (segments.isNotEmpty()) {
            stack.add("" to "Dropbox (root)")
            var acc = ""
            for (i in 0 until segments.size - 1) {
                acc = "$acc/${segments[i]}"
                stack.add(acc to segments[i])
            }
        }
        stack
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        onPathChanged?.invoke(currentPath)
        loading = true; error = null
        listDropboxEntries(context, currentPath).onSuccess { entries = it }.onFailure { error = it.message }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = {
                            val prev = pathStack.removeAt(pathStack.size - 1)
                            currentPath = prev.first; currentLabel = prev.second
                        }) { Text(stringResource(R.string.care_back)) }
                    }
                }
                Text(currentLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading || resolving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        error != null -> Text(stringResource(R.string.dropbox_error, error.toString()), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        entries.isEmpty() -> Text(stringResource(R.string.dropbox_nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        else -> LazyColumn {
                            items(entries) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        when (entry) {
                                            is DropboxEntry.Folder -> {
                                                pathStack.add(currentPath to currentLabel)
                                                currentPath = entry.path; currentLabel = entry.name
                                            }
                                            is DropboxEntry.Image -> scope.launch {
                                                resolving = true; error = null
                                                val link = getDropboxDirectLink(context, entry.path)
                                                resolving = false
                                                if (link != null) {
                                                    onImageSelected(link, entry.clientModified); onDismiss()
                                                } else {
                                                    error = "Couldn't get a link for that photo — check its sharing settings in Dropbox, or try a different file."
                                                }
                                            }
                                            is DropboxEntry.File -> {}
                                        }
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(if (entry is DropboxEntry.Folder) Icons.Outlined.Folder else Icons.Outlined.Image, contentDescription = null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        when (entry) { is DropboxEntry.Folder -> entry.name; is DropboxEntry.Image -> entry.name; is DropboxEntry.File -> entry.name },
                                        fontSize = 14.sp, modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.care_cancel)) }
            }
        }
    }
}

/** Downloads a Dropbox text file (e.g. a CSV) and returns its content, or null on any failure. */
suspend fun downloadDropboxTextFile(context: Context, path: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext null
        val out = java.io.ByteArrayOutputStream()
        client.files().download(path).download(out)
        out.toString("UTF-8")
    } catch (_: Exception) {
        null
    }
}

/** Uploads (overwriting any existing file of the same name) a plain-text file to a Dropbox folder — used for CSV export. */
suspend fun uploadTextFileToDropbox(context: Context, folderPath: String, fileName: String, content: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val client = getDropboxClient(context) ?: return@withContext false
        val filePath = "$folderPath/$fileName".replace("//", "/")
        client.files().uploadBuilder(filePath).withMode(WriteMode.OVERWRITE).uploadAndFinish(content.toByteArray().inputStream())
        true
    } catch (_: Exception) {
        false
    }
}

// ============================================================================
// DROPBOX CSV PICKER DIALOG
// ============================================================================

/** Browses Dropbox for a .csv file (folders navigable same as the image/folder pickers) and downloads
 * its text content on selection — used by "Choose CSV from Dropbox", analogous to
 * DropboxImagePickerDialog but returning file content instead of a shareable link, since CSV import
 * needs the actual bytes, not a URL. */
@Composable
fun DropboxCsvPickerDialog(context: Context, onDismiss: () -> Unit, onFileSelected: (String) -> Unit) {
    var currentPath by remember { mutableStateOf("") }
    var currentLabel by remember { mutableStateOf("Dropbox (root)") }
    var entries by remember { mutableStateOf<List<DropboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pathStack = remember { mutableStateListOf<Pair<String, String>>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        loading = true; error = null
        listDropboxEntries(context, currentPath, fileExtensions = setOf("csv")).onSuccess { entries = it }.onFailure { error = it.message }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = {
                            val prev = pathStack.removeAt(pathStack.size - 1)
                            currentPath = prev.first; currentLabel = prev.second
                        }) { Text(stringResource(R.string.care_back)) }
                    }
                }
                Text(currentLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading || resolving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        error != null -> Text(stringResource(R.string.dropbox_error, error.toString()), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        entries.isEmpty() -> Text(stringResource(R.string.dropbox_no_csv_files_here), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        else -> LazyColumn {
                            items(entries) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        when (entry) {
                                            is DropboxEntry.Folder -> {
                                                pathStack.add(currentPath to currentLabel)
                                                currentPath = entry.path; currentLabel = entry.name
                                            }
                                            is DropboxEntry.File -> scope.launch {
                                                resolving = true; error = null
                                                val content = downloadDropboxTextFile(context, entry.path)
                                                resolving = false
                                                if (content != null) {
                                                    onFileSelected(content); onDismiss()
                                                } else {
                                                    error = "Couldn't download that file — try again."
                                                }
                                            }
                                            is DropboxEntry.Image -> {}
                                        }
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(if (entry is DropboxEntry.Folder) Icons.Outlined.Folder else Icons.Outlined.Description, contentDescription = null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        when (entry) { is DropboxEntry.Folder -> entry.name; is DropboxEntry.Image -> entry.name; is DropboxEntry.File -> entry.name },
                                        fontSize = 14.sp, modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.care_cancel)) }
            }
        }
    }
}

// ============================================================================
// DROPBOX FOLDER PICKER DIALOG
// ============================================================================

@Composable
fun DropboxFolderPickerDialog(
    context: Context,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    var currentLabel by remember { mutableStateOf("Dropbox (root)") }
    var folders by remember { mutableStateOf<List<DropboxFolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val pathStack = remember { mutableStateListOf<Pair<String, String>>() } // path, label

    suspend fun load(path: String) {
        loading = true
        error = null
        val result = listDropboxFolders(context, path)
        loading = false
        result.onSuccess { folders = it }
        result.onFailure { error = it.message ?: "Couldn't load this folder" }
    }

    LaunchedEffect(currentPath) { load(currentPath) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = {
                            val previous = pathStack.removeAt(pathStack.size - 1)
                            currentPath = previous.first
                            currentLabel = previous.second
                        }) { Text(stringResource(R.string.care_back)) }
                    }
                    Spacer(Modifier.weight(1f))
                }
                Text(currentLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        error != null -> Text(stringResource(R.string.dropbox_error, error.toString()), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        folders.isEmpty() -> Text(stringResource(R.string.dropbox_no_subfolders_here), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        else -> LazyColumn {
                            items(folders) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pathStack.add(currentPath to currentLabel)
                                            currentPath = folder.path
                                            currentLabel = folder.name
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Folder, contentDescription = null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(folder.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.care_cancel)) }
                    Button(
                        onClick = { onFolderSelected(currentPath); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.dropbox_use_this_folder)) }
                }
            }
        }
    }
}
