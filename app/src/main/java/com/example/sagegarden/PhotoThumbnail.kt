package com.example.sagegarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream

private const val THUMBNAIL_MAX_DIMENSION = 150
private const val THUMBNAIL_JPEG_QUALITY = 60

/**
 * Downscales and JPEG-compresses whatever [uri] points to, returning a base64 string small enough
 * to embed directly in the Firestore sync payload (see photoThumbnailBase64 on PlantEntity) — this
 * is the fallback shown when a garden member can't resolve another device's local photoUri (local
 * photo-storage mode has no cross-device link, unlike Dropbox mode's plain HTTPS URL). Only worth
 * calling for a local content:// photoUri; an http(s) Dropbox link already works everywhere.
 */
fun generatePhotoThumbnailBase64(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return null
            val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxOf(original.width, original.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else original
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        null
    }
}

/** Decodes a photoThumbnailBase64 value back into a bitmap for display; null on any decode failure. */
fun decodePhotoThumbnail(base64: String): Bitmap? = try {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (_: Exception) {
    null
}

/**
 * Renders [photoUri] like a plain AsyncImage, but falls back to the embedded [photoThumbnailBase64]
 * when the real URI fails to resolve — the case for another garden member's local (content://)
 * photo, which only means something on their own device. Renders nothing if both are unavailable,
 * matching the no-placeholder behaviour every call site already had before this fallback existed.
 */
@Composable
fun PlantPhoto(
    photoUri: String?,
    photoThumbnailBase64: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (photoUri == null) return
    var failed by remember(photoUri) { mutableStateOf(false) }
    if (!failed) {
        AsyncImage(
            model = Uri.parse(photoUri), contentDescription = null,
            modifier = modifier, contentScale = contentScale,
            onError = { failed = true }
        )
    } else {
        val bitmap = remember(photoThumbnailBase64) { photoThumbnailBase64?.let(::decodePhotoThumbnail)?.asImageBitmap() }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)
        }
    }
}
