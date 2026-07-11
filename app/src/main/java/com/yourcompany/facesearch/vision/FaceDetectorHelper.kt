package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of a face detection attempt.
 */
sealed class FaceDetectionResult {
    data class Success(val croppedFace: Bitmap, val boundingBox: Rect) : FaceDetectionResult()
    object NoFaceFound : FaceDetectionResult()
    data class Error(val exception: Exception) : FaceDetectionResult()
}

/**
 * Wraps ML Kit's Face Detection API to find a face in a Bitmap and
 * return a cropped Bitmap of just the face region.
 */
class FaceDetectorHelper {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f) // ignore tiny/background faces
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Detects the largest face in [sourceBitmap] and returns a cropped bitmap of it.
     *
     * @param sourceBitmap the input image, e.g. from CameraX or gallery picker
     * @param rotationDegrees rotation of the image in degrees (0, 90, 180, 270).
     *   Pass 0 if the bitmap is already upright (e.g. loaded from gallery).
     */
    suspend fun detectAndCropFace(
        sourceBitmap: Bitmap,
        rotationDegrees: Int = 0
    ): FaceDetectionResult {
        return try {
            val inputImage = InputImage.fromBitmap(sourceBitmap, rotationDegrees)
            val faces = runDetection(inputImage)

            if (faces.isEmpty()) {
                return FaceDetectionResult.NoFaceFound
            }

            // Pick the largest face by bounding box area — most likely
            // the primary subject if multiple faces are present.
            val largestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return FaceDetectionResult.NoFaceFound

            val safeRect = clampRectToBitmap(largestFace.boundingBox, sourceBitmap)
                ?: return FaceDetectionResult.Error(
                    IllegalStateException("Face bounding box was invalid after clamping")
                )

            val cropped = Bitmap.createBitmap(
                sourceBitmap,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height()
            )

            FaceDetectionResult.Success(cropped, safeRect)
        } catch (e: Exception) {
            FaceDetectionResult.Error(e)
        }
    }

    /**
     * Runs ML Kit's async detector and bridges the callback API into a coroutine.
     */
    private suspend fun runDetection(image: InputImage): List<Face> =
        suspendCancellableCoroutine { continuation ->
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (continuation.isActive) continuation.resume(faces)
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

            continuation.invokeOnCancellation {
                // ML Kit detector doesn't expose a direct cancel per-call;
                // client.close() in release() handles cleanup instead.
            }
        }

    /**
     * ML Kit's bounding box can slightly exceed the source image dimensions
     * (e.g. for faces near the edge of frame). Clamp it so createBitmap doesn't crash.
     */
    private fun clampRectToBitmap(rect: Rect, bitmap: Bitmap): Rect? {
        val left = rect.left.coerceIn(0, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height)
        val right = rect.right.coerceIn(0, bitmap.width)
        val bottom = rect.bottom.coerceIn(0, bitmap.height)

        return if (right > left && bottom > top) {
            Rect(left, top, right, bottom)
        } else {
            null
        }
    }

    /**
     * Call when the detector is no longer needed (e.g. ViewModel's onCleared())
     * to release native resources.
     */
    fun release() {
        detector.close()
    }
}
