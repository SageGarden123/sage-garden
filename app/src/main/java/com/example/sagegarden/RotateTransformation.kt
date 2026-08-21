package com.example.sagegarden

import android.graphics.Bitmap
import android.graphics.Matrix
import coil.size.Size
import coil.transform.Transformation

class RotateTransformation(private val degrees: Float) : Transformation {
    override val cacheKey: String = "rotate_$degrees"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (degrees % 360f == 0f) return input
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }
}