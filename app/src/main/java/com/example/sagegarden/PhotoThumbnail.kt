package com.example.sagegarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream

// Kept modest deliberately: this thumbnail is embedded as a field inside the garden's single
// Firestore sync document (every plant in the garden shares that one document, capped at 1MiB
// total by Firestore) — a much larger/higher-quality thumbnail per plant risks that cap for
// gardens with many plants in local (non-Dropbox) photo-storage mode, which would break sync
// for every plant in the garden, not just make thumbnails prettier.
private const val THUMBNAIL_MAX_DIMENSION = 200
private const val THUMBNAIL_JPEG_QUALITY = 68

/**
 * Downscales and JPEG-compresses whatever [uri] points to, returning a base64 string small enough
 * to embed directly in the Firestore sync payload (see photoThumbnailBase64 on PlantEntity) — this
 * is the fallback shown when a garden member can't resolve another device's local photoUri (local
 * photo-storage mode has no cross-device link, unlike Dropbox mode's plain HTTPS URL). Only worth
 * calling for a local content:// photoUri; an http(s) Dropbox link already works everywhere.
 * Corrects for EXIF orientation first (BitmapFactory ignores it), same reasoning as
 * [resizeImageForDropboxUpload] — otherwise a portrait photo bakes in as sideways for anyone only
 * ever seeing this fallback thumbnail.
 */
fun generatePhotoThumbnailBase64(context: Context, uri: Uri): String? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val orientation = ExifInterface(bytes.inputStream())
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val upright = applyExifRotation(original, orientation)
        val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxOf(upright.width, upright.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * scale).toInt().coerceAtLeast(1),
                (upright.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else upright
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w("PhotoThumbnail", "thumbnail generation failed for $uri", e)
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
