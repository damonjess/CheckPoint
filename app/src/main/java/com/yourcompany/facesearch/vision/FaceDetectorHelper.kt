package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

sealed class FaceDetectionResult {
    data class Success(val croppedFace: Bitmap, val boundingBox: Rect) : FaceDetectionResult()
    object NoFaceFound : FaceDetectionResult()
    data class Error(val exception: Exception) : FaceDetectionResult()
}

/**
 * Real face detection + cropping using ML Kit's on-device face detector.
 * Crops tightly around the detected face with a small margin so both
 * enrollment and search compare like-for-like face regions.
 */
class FaceDetectorHelper(private val context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    suspend fun detectAndCropFace(sourceBitmap: Bitmap): FaceDetectionResult {
        return try {
            val image = InputImage.fromBitmap(sourceBitmap, 0)
            val faces = detector.process(image).await()

            if (faces.isEmpty()) {
                FaceDetectionResult.NoFaceFound
            } else {
                // If more than one face is in frame, use the largest -- it's
                // almost always the person standing closest to the camera.
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                val cropped = cropWithMargin(sourceBitmap, face)
                FaceDetectionResult.Success(cropped, face.boundingBox)
            }
        } catch (e: Exception) {
            FaceDetectionResult.Error(e)
        }
    }

    private fun cropWithMargin(source: Bitmap, face: Face, marginFraction: Float = 0.25f): Bitmap {
        val box = face.boundingBox
        val marginX = (box.width() * marginFraction).toInt()
        val marginY = (box.height() * marginFraction).toInt()

        val left = (box.left - marginX).coerceIn(0, source.width - 1)
        val top = (box.top - marginY).coerceIn(0, source.height - 1)
        val right = (box.right + marginX).coerceIn(left + 1, source.width)
        val bottom = (box.bottom + marginY).coerceIn(top + 1, source.height)

        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun release() {
        detector.close()
    }
}