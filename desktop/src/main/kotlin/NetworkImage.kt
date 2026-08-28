import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Loads and decodes a plant photo URL, when it's actually reachable from this machine. Only
 * plants using the Android app's Dropbox cloud photo storage have a URL here at all (a plain
 * "?raw=1" Dropbox link, viewable with no auth) — the default local photo storage mode stores an
 * Android content:// URI that means nothing off the phone, so this naturally (and silently) shows
 * the placeholder for those instead of erroring, since the fetch just fails on an unsupported URI.
 * No caching beyond a single process run — fine at personal-garden scale, not worth the complexity.
 */
private val imageHttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    // Dropbox's "?raw=1" links commonly 302 to their actual CDN host — HttpClient's default
    // redirect policy is NEVER, so without this the response was the redirect itself (a small
    // non-image body, non-2xx or occasionally 200 with HTML), silently decoded to nothing and
    // falling back to the placeholder icon with no visible error.
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private val imageCache = mutableMapOf<String, ImageBitmap?>()

/**
 * Decodes the small base64 JPEG a phone client embeds alongside a local-storage-mode photoUri (see
 * PlantEntity.photoThumbnailBase64 on the Android side) — this is what lets desktop show at least a
 * thumbnail for a plant whose real photoUri is an Android content:// URI that means nothing here.
 */
private fun decodeThumbnailBase64(base64: String): ImageBitmap? = try {
    SkiaImage.makeFromEncoded(java.util.Base64.getDecoder().decode(base64)).toComposeImageBitmap()
} catch (_: Exception) {
    null
}

/** Tries the real photo first, falling back to the embedded thumbnail if the URL isn't fetchable from this machine (e.g. an Android content:// URI). */
@Composable
fun rememberPlantPhoto(photoUri: String?, photoThumbnailBase64: String?): ImageBitmap? {
    val network = rememberNetworkImage(photoUri)
    if (network != null) return network
    return photoThumbnailBase64?.let { remember(it) { decodeThumbnailBase64(it) } }
}

@Composable
fun rememberNetworkImage(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf(imageCache[url]) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        if (imageCache.containsKey(url)) {
            bitmap = imageCache[url]
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
                val response = imageHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() in 200..299) {
                    SkiaImage.makeFromEncoded(response.body()).toComposeImageBitmap()
                } else null
            } catch (_: Exception) {
                null
            }
        }
        imageCache[url] = loaded
        bitmap = loaded
    }
    return bitmap
}

/** Full-size preview shown when a plant thumbnail is clicked, in both the Plants list and the edit screen. */
@Composable
fun PhotoPreviewDialog(photoUri: String, photoThumbnailBase64: String? = null, onDismiss: () -> Unit) {
    val bitmap = rememberPlantPhoto(photoUri, photoThumbnailBase64)
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.size(560.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}
