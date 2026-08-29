package com.example.sagegarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

private const val DROPBOX_UPLOAD_SCALE = 0.5f
private const val DROPBOX_UPLOAD_JPEG_QUALITY = 85

/**
 * Downscales [uri] to half its original width/height and re-encodes as JPEG, for photos being
 * uploaded to Dropbox — halving both dimensions cuts the pixel count (and roughly the file size)
 * to a quarter. Corrects for EXIF orientation first, since BitmapFactory ignores it and would
 * otherwise upload a portrait photo sideways (the orientation tag is normally what makes it display
 * upright, and re-encoding here bakes the rotation in rather than carrying the tag forward). Falls
 * back to the original, unresized bytes if decoding fails for any reason, so an unsupported/corrupt
 * image still uploads rather than being silently dropped.
 */
fun resizeImageForDropboxUpload(context: Context, uri: Uri): ByteArray? {
    val originalBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return try {
        val orientation = ExifInterface(originalBytes.inputStream())
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val decoded = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return originalBytes
        val upright = applyExifRotation(decoded, orientation)
        val targetWidth = (upright.width * DROPBOX_UPLOAD_SCALE).toInt().coerceAtLeast(1)
        val targetHeight = (upright.height * DROPBOX_UPLOAD_SCALE).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(upright, targetWidth, targetHeight, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, DROPBOX_UPLOAD_JPEG_QUALITY, out)
        out.toByteArray()
    } catch (_: Exception) {
        originalBytes
    }
}

fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
