@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.util.Log
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.v2.DbxClientV2
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// ============================================================================
// DROPBOX CLOUD PHOTO STORAGE
// ============================================================================

const val DROPBOX_APP_KEY = BuildConfig.DROPBOX_APP_KEY

fun getDropboxAccessToken(context: Context): String? = migrateCredential(context, "dropbox_access_token")

fun getDropboxRefreshToken(context: Context): String? = migrateCredential(context, "dropbox_refresh_token")

fun saveDropboxTokens(context: Context, accessToken: String?, refreshToken: String?, savedAtMillis: Long) {
    credentialPrefs(context).edit()
        .putString("dropbox_access_token", accessToken)
        .putString("dropbox_refresh_token", refreshToken)
        .putLong("dropbox_token_saved_at", savedAtMillis)
        .apply()
}

fun clearDropboxTokens(context: Context) {
    saveDropboxTokens(context, null, null, 0L)
}

/** Dropbox short-lived tokens last ~4 hours; refresh proactively a bit before that. */
internal const val DROPBOX_TOKEN_REFRESH_AFTER_MS = 3L * 60 * 60 * 1000

const val SUPPORT_LINK_URL = "https://www.buymeacoffee.com/spokolabs"

suspend fun ensureDropboxTokenFresh(context: Context) = withContext(Dispatchers.IO) {
    val refreshToken = getDropboxRefreshToken(context) ?: return@withContext
    val savedAt = credentialPrefs(context).getLong("dropbox_token_saved_at", 0L)
    if (System.currentTimeMillis() - savedAt < DROPBOX_TOKEN_REFRESH_AFTER_MS) return@withContext

    try {
        val client = OkHttpClient()
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", DROPBOX_APP_KEY)
            .build()
        val request = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(formBody).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    val newAccessToken = json.optString("access_token").takeIf { it.isNotBlank() }
                    if (newAccessToken != null) {
                        saveDropboxTokens(context, newAccessToken, refreshToken, System.currentTimeMillis())
                    }
                }
            }
        }
    } catch (_: Exception) { /* keep the existing token; the next Dropbox call will surface any real problem */ }
}

/** Builds a Dropbox client, refreshing the access token first if it's due to expire soon. */
suspend fun getDropboxClient(context: Context): DbxClientV2? = withContext(Dispatchers.IO) {
    ensureDropboxTokenFresh(context)
    val token = getDropboxAccessToken(context) ?: return@withContext null
    val requestConfig = DbxRequestConfig.newBuilder("SageGarden/1.0").build()
    DbxClientV2(requestConfig, token)
}

fun startDropboxSignIn(context: Context) {
    val requestConfig = DbxRequestConfig.newBuilder("SageGarden/1.0").build()
    Auth.startOAuth2PKCE(
        context, DROPBOX_APP_KEY, requestConfig,
        listOf("files.content.write", "files.content.read", "sharing.write")
    )
}

object DropboxLinkState {
    var linking by mutableStateOf(false)
        private set
    var current by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var result by mutableStateOf<String?>(null)
        private set

    fun start() {
        linking = true
        current = 0
        total = 0
    }
    fun updateProgress(c: Int, t: Int) {
        current = c
        total = t
    }
    fun finish(message: String) {
        linking = false
        result = message
    }
}

object PendingNotificationState {
    var type by mutableStateOf<String?>(null)
}

object PendingPlantEditState {
    var plantId by mutableStateOf<String?>(null)
    // Which garden the tapped plant actually belongs to — set alongside plantId whenever the source
    // (currently only the widget) knows it. Without this, opening a shared garden's plant via a deep
    // link while a DIFFERENT (possibly colliding-id) garden is active could resolve the wrong plant —
    // see resolvePlantById's doc comment. Null for older/other entry points that don't supply it yet;
    // the consuming LaunchedEffect falls back to the pre-existing bare-id-only behavior in that case.
    var gardenId by mutableStateOf<String?>(null)
}

/** Set before navigating to Help from the Map tab's "no garden address set" guidance, so the Weather-aware reminders section (where the address field lives) opens already expanded. Reset once HelpScreen consumes it. */
object PendingHelpFocusState {
    var focusWeatherSection by mutableStateOf(false)
}

object SageFabResetState {
    var requested by mutableStateOf(false)
}

object DropboxAuthState {
    var token by mutableStateOf<String?>(null)
        private set

    fun refresh(context: Context) {
        token = getDropboxAccessToken(context)
    }

    fun checkAndRefresh(context: Context) {
        checkDropboxAuthResult(context)
        refresh(context)
    }

    fun clear(context: Context) {
        clearDropboxTokens(context)
        token = null
    }
}

/** Call this once, e.g. in a LaunchedEffect on HelpScreen, to pick up a completed sign-in. */
fun checkDropboxAuthResult(context: Context) {
    val credential = Auth.getDbxCredential() ?: return
    saveDropboxTokens(context, credential.accessToken, credential.refreshToken, System.currentTimeMillis())
}

/** Uploads a local photo to the app's Dropbox folder and returns a direct-viewable link, or null on failure. */
suspend fun uploadPhotoToDropbox(context: Context, localUri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val fileName = "/garden_${System.currentTimeMillis()}.jpg"
            client.files().uploadBuilder(fileName).uploadAndFinish(bytes.inputStream())
            val sharedLink = client.sharing().createSharedLinkWithSettings(fileName)
            toDirectDropboxLink(sharedLink.url)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Fetches (or creates) a direct shared link for [filePath], which must already exist in Dropbox.
 * Dropbox's sharing API can lag briefly right after a file is first uploaded — a fallback lookup
 * immediately after a failed [DbxClientV2.sharing]`.createSharedLinkWithSettings` can itself come
 * back empty even though the file is genuinely there, which used to be reported as a total upload
 * failure despite the file having actually been saved (leaving that filename permanently used up,
 * so the next attempt uploaded a duplicate under an incremented name instead of reusing it). Retries
 * the lookup a few times with a short delay before giving up.
 */
internal suspend fun fetchOrCreateSharedLink(client: DbxClientV2, filePath: String): String? {
    // Retries the WHOLE create-then-lookup-fallback sequence, not just the fallback lookup — the
    // first version only retried the lookup after a single failed create attempt, on the theory
    // that the create call fails because a link already exists (just briefly not visible to the
    // lookup yet). Confirmed on-device 2026-08-29 that this isn't the whole story: an upload can
    // still be reported as failed despite genuinely succeeding, which means the CREATE call itself
    // can be the one failing transiently (network hiccup, transient server error) — in which case
    // no link was ever created, so retrying only the lookup finds nothing no matter how many times
    // it's tried. Retrying the create call too gives it a real second chance to succeed.
    val delaysMs = longArrayOf(500L, 1000L, 1500L, 2000L)
    repeat(5) { attempt ->
        val link = try {
            client.sharing().createSharedLinkWithSettings(filePath).url
        } catch (e: Exception) {
            val fallback = try {
                client.sharing().listSharedLinksBuilder().withPath(filePath).withDirectOnly(true).start()
                    .links.firstOrNull()?.url
            } catch (_: Exception) {
                null
            }
            if (fallback == null) {
                android.util.Log.w("DropboxLink", "fetchOrCreateSharedLink attempt ${attempt + 1}/5 failed for path='$filePath'", e)
            }
            fallback
        }
        if (link != null) return link
        if (attempt < delaysMs.size) kotlinx.coroutines.delay(delaysMs[attempt])
    }
    return null
}

/**
 * Figures out what filename [uploadPhotoToDropboxAsPlantId] will actually use for a given plant ID,
 * without uploading anything — the plant's *first* photo gets the bare ID ("P0056.jpg"); every
 * subsequent upload for the same ID (replacing a photo when editing) gets the next unused "_N"
 * suffix ("P0056_1.jpg", "P0056_2.jpg", ...). Shared by the upload function itself and by the
 * button text that previews the target name before the user commits to uploading. Returns null if
 * Dropbox isn't reachable (folder listing failed) — callers should fall back to showing the bare
 * plant ID in that case, same as before this preview existed.
 */
/**
 * Lists every filename directly inside [folderPath], paging through `listFolderContinue` so a
 * large photo folder doesn't silently miss files past the first page (which previously showed as
 * available to reuse when they weren't).
 */
internal suspend fun listDropboxFileNames(client: DbxClientV2, folderPath: String): Set<String> {
    val listing = client.files().listFolder(folderPath.ifBlank { "" })
    val existingNames = listing.entries.mapNotNull { (it as? com.dropbox.core.v2.files.FileMetadata)?.name }.toMutableSet()
    var cursor = listing.cursor
    var hasMore = listing.hasMore
    while (hasMore) {
        val more = client.files().listFolderContinue(cursor)
        existingNames += more.entries.mapNotNull { (it as? com.dropbox.core.v2.files.FileMetadata)?.name }
        cursor = more.cursor
        hasMore = more.hasMore
    }
    return existingNames
}

suspend fun previewDropboxUploadName(context: Context, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val existingNames = listDropboxFileNames(client, folderPath)
            val bareName = "$plantId.jpg"
            android.util.Log.d("DropboxUploadName", "folder='$folderPath' entries=${existingNames.size} bareNameExists=${bareName in existingNames}")
            if (bareName !in existingNames) {
                bareName
            } else {
                var suffix = 1
                while ("${plantId}_$suffix.jpg" in existingNames) suffix++
                "${plantId}_$suffix.jpg"
            }
        } catch (e: Exception) {
            android.util.Log.w("DropboxUploadName", "preview failed for $plantId", e)
            null
        }
    }
}

/**
 * Uploads a local photo to the configured Dropbox photo folder under the name [previewDropboxUploadName]
 * computes for [plantId], so the file is recognisable in Dropbox itself.
 */
suspend fun uploadPhotoToDropboxAsPlantId(context: Context, localUri: Uri, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val targetName = previewDropboxUploadName(context, plantId) ?: return@withContext null

            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val filePath = "$folderPath/$targetName".replace("//", "/")
            client.files().uploadBuilder(filePath).uploadAndFinish(bytes.inputStream())
            // The upload itself (above) can succeed while getting a shareable link fails/lags —
            // see fetchOrCreateSharedLink for why this is retried rather than reported as a hard
            // failure immediately.
            fetchOrCreateSharedLink(client, filePath)?.let { toDirectDropboxLink(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Extra Photos share a plant's ID as their Dropbox filename base but need their own counter,
 * distinct from the main plant photo's ([previewDropboxUploadName]) — the "_E" tag disambiguates
 * "P0001_E1.jpg" (an extra photo) from "P0001_1.jpg" (a replaced main photo) in Dropbox itself.
 */
suspend fun previewExtraPhotoDropboxUploadName(context: Context, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val existingNames = listDropboxFileNames(client, folderPath)
            var suffix = 1
            while ("${plantId}_E$suffix.jpg" in existingNames) suffix++
            "${plantId}_E$suffix.jpg"
        } catch (_: Exception) {
            null
        }
    }
}

/** Uploads a local extra photo to the configured Dropbox photo folder under the name
 * [previewExtraPhotoDropboxUploadName] computes for [plantId]. Mirrors [uploadPhotoToDropboxAsPlantId]. */
suspend fun uploadPhotoToDropboxAsExtraPhoto(context: Context, localUri: Uri, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val targetName = previewExtraPhotoDropboxUploadName(context, plantId) ?: return@withContext null

            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val filePath = "$folderPath/$targetName".replace("//", "/")
            client.files().uploadBuilder(filePath).uploadAndFinish(bytes.inputStream())
            fetchOrCreateSharedLink(client, filePath)?.let { toDirectDropboxLink(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** Growth timeline photos, tagged "_G" for the same reason Extra Photos are tagged "_E" —
 * see [previewExtraPhotoDropboxUploadName]. */
suspend fun previewGrowthPhotoDropboxUploadName(context: Context, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val existingNames = listDropboxFileNames(client, folderPath)
            var suffix = 1
            while ("${plantId}_G$suffix.jpg" in existingNames) suffix++
            "${plantId}_G$suffix.jpg"
        } catch (_: Exception) {
            null
        }
    }
}

/** Uploads a local growth-timeline photo to the configured Dropbox photo folder under the name
 * [previewGrowthPhotoDropboxUploadName] computes for [plantId]. Mirrors [uploadPhotoToDropboxAsPlantId]. */
suspend fun uploadPhotoToDropboxAsGrowthPhoto(context: Context, localUri: Uri, plantId: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val targetName = previewGrowthPhotoDropboxUploadName(context, plantId) ?: return@withContext null

            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val filePath = "$folderPath/$targetName".replace("//", "/")
            client.files().uploadBuilder(filePath).uploadAndFinish(bytes.inputStream())
            fetchOrCreateSharedLink(client, filePath)?.let { toDirectDropboxLink(it) }
        } catch (_: Exception) {
            null
        }
    }
}

fun sanitizeForDropboxFilename(raw: String): String =
    raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "zone" }

/**
 * Progress (zone) photos have no plant ID to derive a Dropbox filename from, unlike
 * [previewDropboxUploadName] — so they use "progress_<zone>_<N>.jpg" instead, always suffixed
 * starting at _1 (unlike the plant-photo convention's bare name for the first upload, since
 * "progress_frontyard.jpg" alone wouldn't obviously read as belonging to this app/feature).
 * Deliberately left with a plain numeric suffix (no letter tag) — unlike Extra/Growth photos,
 * these don't share a naming pool with anything else, so there's nothing to disambiguate from.
 */
private fun progressPhotoBaseName(location: String) = "progress_${sanitizeForDropboxFilename(location)}"

/** Where [location]'s progress photos upload: the zone's own Dropbox folder (the one its picker last
 * browsed, e.g. ".../Progress Photos/Back Garden"), or the main photo folder for a zone that has never
 * had one picked. */
fun progressPhotoUploadFolder(context: Context, location: String): String =
    getProgressPhotoDropboxFolder(context, location)?.takeIf { it.isNotBlank() } ?: getDropboxPhotoFolderPath(context) ?: ""

/**
 * The highest N already used by a "progress_<zone>_<N>.*" file in the folder the zone uploads to, so
 * numbering carries on from what's really there instead of filling an old gap or restarting at 1.
 * Only that folder counts: scanning the main photo folder too made a zone start at _2 whenever an
 * earlier build had uploaded a stray _1 there.
 */
private fun highestProgressPhotoSuffix(location: String, existingNames: Set<String>): Int {
    val pattern = Regex("^${Regex.escape(progressPhotoBaseName(location))}_(\\d+)\\.[a-z0-9]+$", RegexOption.IGNORE_CASE)
    return existingNames.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
}

private suspend fun progressPhotoFolderNames(context: Context, client: DbxClientV2, location: String): Set<String> =
    try { listDropboxFileNames(client, progressPhotoUploadFolder(context, location)) } catch (_: Exception) { emptySet() }

/** The N of a "progress_<zone>_<N>.jpg" name, or null. */
fun progressPhotoSuffixOf(name: String): Int? = name.removeSuffix(".jpg").substringAfterLast('_').toIntOrNull()

/**
 * The next [count] upload names for [location]'s progress photos, in order — one per not-yet-uploaded
 * photo, so several pending photos each preview their own name (_3, _4, …) rather than all showing
 * the same next free one. Skips [reservedSuffixes] (names already promised to queued uploads) so a
 * re-preview mid-queue can't hand out a queued photo's number again. Null when Dropbox isn't reachable.
 */
suspend fun previewProgressPhotoDropboxUploadNames(
    context: Context, location: String, count: Int, reservedSuffixes: Set<Int> = emptySet()
): List<String>? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            var n = highestProgressPhotoSuffix(location, progressPhotoFolderNames(context, client, location))
            List(count) {
                do { n++ } while (n in reservedSuffixes)
                "${progressPhotoBaseName(location)}_$n.jpg"
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Uploads a local progress photo as [preferredName] (the name its row previewed) unless a file of that
 * exact name already exists — then the next number past the highest — into [progressPhotoUploadFolder]
 * so a zone's photos stay together. Keeping the preferred name even when it's below the current highest
 * is what lets a queued _1 still land as _1 after a later-tapped _2 finished first. Mirrors
 * [uploadPhotoToDropboxAsPlantId]. */
suspend fun uploadPhotoToDropboxAsProgressPhoto(context: Context, localUri: Uri, location: String, preferredName: String? = null): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = progressPhotoUploadFolder(context, location)
            val existing = progressPhotoFolderNames(context, client, location)
            val taken = existing.mapTo(mutableSetOf()) { it.lowercase() }
            val targetName = if (preferredName != null && preferredName.lowercase() !in taken) preferredName
                else "${progressPhotoBaseName(location)}_${highestProgressPhotoSuffix(location, existing) + 1}.jpg"

            val bytes = resizeImageForDropboxUpload(context, localUri) ?: return@withContext null
            val filePath = "$folderPath/$targetName".replace("//", "/")
            client.files().uploadBuilder(filePath).uploadAndFinish(bytes.inputStream())
            fetchOrCreateSharedLink(client, filePath)?.let { toDirectDropboxLink(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The classic `?raw=1` query-param swap on www.dropbox.com reliably serves inline image bytes for
 * Dropbox's older `/s/<hash>/...` share-link format, but is known to be unreliable for the newer
 * `/scl/fi/<id>/...?rlkey=...` format Dropbox's UI now generates — that format needs the request
 * served from the dl.dropboxusercontent.com CDN domain instead, not just a query tweak on
 * www.dropbox.com. Swapping the host (rather than stripping/rebuilding query params) preserves
 * rlkey and everything else a scl-format link needs to stay valid.
 */
fun toDirectDropboxLink(url: String): String {
    val onDirectHost = url
        .replace("://www.dropbox.com", "://dl.dropboxusercontent.com")
        .replace("://dropbox.com", "://dl.dropboxusercontent.com")
    return when {
        onDirectHost.contains("?dl=0") -> onDirectHost.replace("?dl=0", "?raw=1")
        onDirectHost.contains("&dl=0") -> onDirectHost.replace("&dl=0", "&raw=1")
        onDirectHost.contains("dl=0") -> onDirectHost.replace("dl=0", "raw=1")
        onDirectHost.contains("?") -> "$onDirectHost&raw=1"
        else -> "$onDirectHost?raw=1"
    }
}

fun getLocalPhotoFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("local_photo_folder_uri", null)?.let { Uri.parse(it) }
}
fun setLocalPhotoFolderUri(context: Context, uri: Uri?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("local_photo_folder_uri", uri?.toString()).apply()
}

/** Matches files whose name (minus extension) equals a Plant ID. Skips photos already linked. */
suspend fun autoLinkLocalPhotos(
    context: Context,
    folderUri: Uri,
    plants: List<PlantEntity>,
    onPlantUpdated: suspend (PlantEntity) -> Unit
): Int = withContext(Dispatchers.IO) {
    val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext 0
    val plantsById = plants.associateBy { it.id.lowercase() }
    var linkedCount = 0

    folder.listFiles().forEach { file ->
        val displayName = file.name ?: return@forEach
        if (!file.type.orEmpty().startsWith("image/")) return@forEach

        val baseName = displayName.substringBeforeLast(".")
        val plant = plantsById[baseName.lowercase()] ?: return@forEach

        val alreadyLinked = plant.photoUris.any { existing ->
            Uri.parse(existing).lastPathSegment?.substringAfterLast("/") == displayName
        }
        if (alreadyLinked) return@forEach

        try {
            context.contentResolver.takePersistableUriPermission(
                file.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* some providers don't support this — ignore */ }

        val updated = plant.copy(
            photoUris = plant.photoUris + file.uri.toString(),
            photoUri = plant.photoUri ?: file.uri.toString()
        )
        onPlantUpdated(updated)
        linkedCount++
    }
    linkedCount
}

/**
 * Where irrigation_log.csv is read/written — deliberately separate from the photo storage folder
 * above. Both used to silently default to the photo folder/path, which meant a user who'd never
 * explicitly thought about it would find their watering history mixed in with (or invisibly
 * shadowed by) wherever their photos happened to be configured to go, with no way to tell where it
 * actually ended up short of hunting through their photo folder. Null falls back to "(root)" for
 * Dropbox and blocks the local save with a clear "choose a folder first" message rather than a
 * silent no-op — see saveIrrigationCsvLocal/Dropbox.
 */
fun getIrrigationLogFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("irrigation_log_folder_uri", null)?.let { Uri.parse(it) }
}
fun setIrrigationLogFolderUri(context: Context, uri: Uri?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("irrigation_log_folder_uri", uri?.toString()).apply()
}
fun getIrrigationLogDropboxFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("irrigation_log_dropbox_folder_path", null)
}
fun setIrrigationLogDropboxFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("irrigation_log_dropbox_folder_path", path).apply()
}

/** A zone's progress-photo Dropbox folder — where its uploads go and where its photo picker opens —
 * keyed per zone since different zones' photos often live in different folders. Only set by the
 * zone page's "Change" button. (It used to silently follow whatever folder the zone's photo picker
 * last browsed; values saved that way are kept as each zone's starting folder.) */
fun getProgressPhotoDropboxFolder(context: Context, location: String): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("progress_photo_dropbox_folder.$location", null)
}
/** [displayPath] is the same path with Dropbox's original capitalisation ([path] is lowercase), for showing to the user. */
fun setProgressPhotoDropboxFolder(context: Context, location: String, path: String, displayPath: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putString("progress_photo_dropbox_folder.$location", path)
        .putString("progress_photo_dropbox_folder_display.$location", displayPath)
        .apply()
}
/** Readable form of [getProgressPhotoDropboxFolder]; falls back to the lowercase path for a folder saved before display paths were kept. */
fun getProgressPhotoDropboxFolderDisplay(context: Context, location: String): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("progress_photo_dropbox_folder_display.$location", null)
        ?: getProgressPhotoDropboxFolder(context, location)?.trim('/')
}

fun getDropboxPhotoFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dropbox_photo_folder_path", null)
}
fun setDropboxPhotoFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dropbox_photo_folder_path", path).apply()
}
/** Null means "not set yet — falls back to the photo folder", so existing users keep their current behaviour. */
fun getDropboxBackupFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dropbox_backup_folder_path", null)
}
fun setDropboxBackupFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dropbox_backup_folder_path", path).apply()
}
/** Null means "not set yet — falls back to the backup folder, then the photo folder". */
fun getDropboxCsvFolderPath(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("dropbox_csv_folder_path", null)
}
fun setDropboxCsvFolderPath(context: Context, path: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("dropbox_csv_folder_path", path).apply()
}
/** The device's own default garden keeps "sage_garden_plantdata.csv" ("plantdata" makes clear this
 * is only the plant CSV, not a full backup); any other garden gets a name-derived filename too,
 * same reasoning as BackupHelper.defaultBackupFileNameForGarden — otherwise two gardens exporting
 * CSV to the same Dropbox folder would silently overwrite each other's export. */
fun csvExportFileNameForGarden(context: Context, gardenId: String): String {
    if (gardenId.isBlank() || gardenId == getOrCreateInstallId(context)) return "sage_garden_plantdata.csv"
    val name = GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == gardenId }?.name ?: "garden"
    return "sage_garden_plantdata_${sanitizeForDropboxFilename(name)}.csv"
}
fun getLocalBackupFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("local_backup_folder_uri", null)?.let { Uri.parse(it) }
}
fun setLocalBackupFolderUri(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("local_backup_folder_uri", uri.toString()).apply()
}

data class DropboxLinkResult(val linkedCount: Int, val matchedCount: Int, val errorMessage: String? = null)

fun getLastDropboxLinkResult(context: Context): String? {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("last_dropbox_link_result", null)
}
fun setLastDropboxLinkResult(context: Context, result: String?) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("last_dropbox_link_result", result).apply()
}

suspend fun autoLinkDropboxPhotos(
    context: Context,
    folderPath: String,
    plants: List<PlantEntity>,
    onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    onPlantUpdated: suspend (PlantEntity) -> Unit
): DropboxLinkResult = withContext(Dispatchers.IO) {
    val client = getDropboxClient(context) ?: return@withContext DropboxLinkResult(0, 0, "Dropbox isn't connected")
    val plantsById = plants.associateBy { it.id.trim().lowercase() }

    try {
        val allImageFiles = mutableListOf<com.dropbox.core.v2.files.FileMetadata>()
        var listResult = client.files().listFolder(folderPath)
        while (true) {
            allImageFiles.addAll(
                listResult.entries.filterIsInstance<com.dropbox.core.v2.files.FileMetadata>()
                    .filter { it.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") } }
            )
            if (!listResult.hasMore) break
            listResult = client.files().listFolderContinue(listResult.cursor)
        }

        val matched = allImageFiles.mapNotNull { file ->
            val baseName = file.name.substringBeforeLast(".").trim()
            plantsById[baseName.lowercase()]?.let { plant -> file to plant }
        }
        val totalMatched = matched.size
        var linkedCount = 0
        onProgress(0, totalMatched)

        matched.forEach { (file, plant) ->
            val name = file.name
            if (plant.photoUris.any { it.contains(name) }) {
                linkedCount++
                onProgress(linkedCount, totalMatched)
                return@forEach
            }
            val filePath = "$folderPath/$name".replace("//", "/")
            val link = try {
                toDirectDropboxLink(client.sharing().createSharedLinkWithSettings(filePath).url)
            } catch (_: Exception) {
                // withDirectOnly(true): see uploadPhotoToDropboxAsPlantId — without it this can pick
                // up an inherited link from an already-shared parent folder, which 404s as an image.
                client.sharing().listSharedLinksBuilder().withPath(filePath).withDirectOnly(true).start()
                    .links.firstOrNull()?.url?.let { toDirectDropboxLink(it) }
            }
            if (link != null) {
                onPlantUpdated(plant.copy(photoUris = plant.photoUris + link, photoUri = plant.photoUri ?: link))
                linkedCount++
            }
            onProgress(linkedCount, totalMatched)
        }
        DropboxLinkResult(linkedCount, totalMatched)
    } catch (e: Exception) {
        DropboxLinkResult(0, 0, e.message ?: "Unknown error")
    }
}
