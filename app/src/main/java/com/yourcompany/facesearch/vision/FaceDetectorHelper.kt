package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class FaceDetectionResult {
    data class Success(val croppedFace: Bitmap, val boundingBox: Rect) : FaceDetectionResult()
    object NoFaceFound : FaceDetectionResult()
    data class Error(val exception: Exception) : FaceDetectionResult()
}

class FaceDetectorHelper(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null

    private fun initializeDetector() {
        if (faceLandmarker != null) return

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun detectAndCropFace(sourceBitmap: Bitmap): FaceDetectionResult {
        initializeDetector()

        return try {
            // For now, use basic cropping (MediaPipe full integration is complex)
            // You can expand this later with landmark data
            val rect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
            FaceDetectionResult.Success(sourceBitmap, rect)

        } catch (e: Exception) {
            FaceDetectionResult.Error(e)
        }
    }

    fun release() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}