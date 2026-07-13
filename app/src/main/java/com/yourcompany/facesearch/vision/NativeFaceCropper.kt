package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class NativeFaceCropper {
    // Configure the detector for fast, on-device detection
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )

    suspend fun cropToFace(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        // Prepare the image for ML Kit
        val image = InputImage.fromBitmap(bitmap, 0)
        
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val box = faces[0].boundingBox
                    
                    // Add a tiny 10% padding so we don't cut off your hair/ears too closely
                    val paddingX = (box.width() * 0.10f).toInt()
                    val paddingY = (box.height() * 0.10f).toInt()

                    val left = (box.left - paddingX).coerceAtLeast(0)
                    val top = (box.top - paddingY).coerceAtLeast(0)
                    val width = (box.width() + (paddingX * 2)).coerceAtMost(bitmap.width - left)
                    val height = (box.height() + (paddingY * 2)).coerceAtMost(bitmap.height - top)

                    // Crop the bitmap directly to the face bounding box
                    val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                    continuation.resume(cropped)
                } else {
                    // Fallback to the original image if no face is detected
                    continuation.resume(bitmap) 
                }
            }
            .addOnFailureListener {
                // In case of an error, return the original image so the app doesn't crash
                continuation.resume(bitmap)
            }
    }
}
