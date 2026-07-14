package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class NativeFaceCropper {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    /**
     * QUALITY GATE: Validates if the face is suitable for a high-quality search.
     */
    suspend fun validateFaceQuality(bitmap: Bitmap): FaceQualityResult = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    continuation.resume(FaceQualityResult(false, "No face detected in probe."))
                    return@addOnSuccessListener
                }

                val face = faces[0]
                val box = face.boundingBox
                
                // 1. Minimum Size Check (Face should be at least 15% of the frame)
                val faceArea = box.width() * box.height()
                val bitmapArea = bitmap.width * bitmap.height
                val coverage = faceArea.toFloat() / bitmapArea
                
                if (coverage < 0.05f) {
                    continuation.resume(FaceQualityResult(false, "Face too small. Please move closer."))
                    return@addOnSuccessListener
                }

                // 2. Head Tilt Check
                if (Math.abs(face.headEulerAngleY) > 35) {
                    continuation.resume(FaceQualityResult(false, "Head turned too far. Face the camera directly."))
                    return@addOnSuccessListener
                }

                continuation.resume(FaceQualityResult(true, "Quality Pass"))
            }
            .addOnFailureListener {
                continuation.resume(FaceQualityResult(false, "Analysis engine failure."))
            }
    }

    data class FaceQualityResult(val isGood: Boolean, val message: String)

    suspend fun cropAndAlignFace(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val box = face.boundingBox

                    // 1. IMPROVED ALIGNMENT USING LANDMARKS
                    val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)
                    val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)
                    
                    val rotationMatrix = Matrix()
                    if (leftEye != null && rightEye != null) {
                        val deltaX = (rightEye.position.x - leftEye.position.x).toDouble()
                        val deltaY = (rightEye.position.y - leftEye.position.y).toDouble()
                        val angle = Math.toDegrees(Math.atan2(deltaY, deltaX)).toFloat()
                        rotationMatrix.postRotate(-angle, face.boundingBox.centerX().toFloat(), face.boundingBox.centerY().toFloat())
                    } else {
                        // Fallback to Euler angle if landmarks are missing
                        rotationMatrix.postRotate(-face.headEulerAngleZ, face.boundingBox.centerX().toFloat(), face.boundingBox.centerY().toFloat())
                    }

                    // 2. ADD DYNAMIC PADDING (More padding for better context if needed)
                    val paddingFactor = 0.25f
                    val paddingX = (box.width() * paddingFactor).toInt()
                    val paddingY = (box.height() * paddingFactor).toInt()

                    val left = (box.left - paddingX).coerceAtLeast(0)
                    val top = (box.top - paddingY).coerceAtLeast(0)
                    val width = (box.width() + (paddingX * 2)).coerceAtMost(bitmap.width - left)
                    val height = (box.height() + (paddingY * 2)).coerceAtMost(bitmap.height - top)

                    // 3. CROP AND APPLY ALIGNMENT
                    val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                    val aligned = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotationMatrix, true)

                    continuation.resume(aligned)
                } else {
                    continuation.resume(bitmap) 
                }
            }
            .addOnFailureListener {
                continuation.resume(bitmap)
            }
    }

    suspend fun cropContextual(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val box = face.boundingBox

                    // BYPASS STRATEGY: 350% wider crop, face off-center
                    val widthScale = 3.5f
                    val heightScale = 4.0f
                    
                    val pWidth = (box.width() * widthScale).toInt()
                    val pHeight = (box.height() * heightScale).toInt()

                    val left = (box.centerX() - (pWidth * 0.35f)).toInt().coerceAtLeast(0)
                    val top = (box.centerY() - (pHeight * 0.4f)).toInt().coerceAtLeast(0)
                    
                    val width = pWidth.coerceAtMost(bitmap.width - left)
                    val height = pHeight.coerceAtMost(bitmap.height - top)

                    val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                    
                    // Removed mirroring as it might confuse Yandex's specific profile matching
                    continuation.resume(cropped)
                } else {
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener { continuation.resume(bitmap) }
    }

    suspend fun cropSocial(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val box = face.boundingBox

                    // SOCIAL STRATEGY: Wider Contextual Crop (Natural look)
                    val targetWidth = (box.width() * 3.0f).toInt()
                    val targetHeight = (targetWidth * 1.1f).toInt()
                    
                    val left = (box.centerX() - (targetWidth / 2)).coerceIn(0, (bitmap.width - targetWidth).coerceAtLeast(0))
                    val top = (box.centerY() - (targetHeight * 0.40f).toInt()).coerceIn(0, (bitmap.height - targetHeight).coerceAtLeast(0))
                    
                    val finalWidth = targetWidth.coerceAtMost(bitmap.width - left)
                    val finalHeight = targetHeight.coerceAtMost(bitmap.height - top)

                    val cropped = Bitmap.createBitmap(bitmap, left, top, finalWidth, finalHeight)
                    
                    val enhanced = ImageEnhancer.enhance(cropped)
                    continuation.resume(enhanced)
                } else {
                    continuation.resume(ImageEnhancer.enhance(bitmap))
                }
            }
            .addOnFailureListener { continuation.resume(bitmap) }
    }

    /**
     * HYPER-PROBE: Creates a 3-way composite to bypass privacy filters.
     */
    suspend fun createHyperProbe(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val composite = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(composite)
                canvas.drawColor(Color.BLACK)

                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val box = face.boundingBox

                    // 1. TOP HALF: Wide Context (Clothes/Background)
                    val contextWidth = (box.width() * 4.0f).toInt().coerceAtMost(bitmap.width)
                    val contextHeight = (contextWidth * 0.5f).toInt()
                    val cLeft = (box.centerX() - (contextWidth / 2)).coerceIn(0, bitmap.width - contextWidth)
                    val cTop = (box.centerY() - (contextHeight / 2)).coerceIn(0, bitmap.height - contextHeight)
                    val contextCrop = Bitmap.createBitmap(bitmap, cLeft, cTop, contextWidth, contextHeight)
                    canvas.drawBitmap(Bitmap.createScaledBitmap(contextCrop, 1024, 512, true), 0f, 0f, null)

                    // 2. BOTTOM LEFT: High-Contrast Face
                    val faceCrop = Bitmap.createBitmap(bitmap, 
                        box.left.coerceAtLeast(0), 
                        box.top.coerceAtLeast(0), 
                        box.width().coerceAtMost(bitmap.width - box.left.coerceAtLeast(0)), 
                        box.height().coerceAtMost(bitmap.height - box.top.coerceAtLeast(0))
                    )
                    val enhancedFace = ImageEnhancer.enhance(faceCrop)
                    canvas.drawBitmap(Bitmap.createScaledBitmap(enhancedFace, 512, 512, true), 0f, 512f, null)

                    // 3. BOTTOM RIGHT: Digital Camouflage / Grayscale Bypass
                    val matrix = Matrix().apply { postScale(-1f, 1f) }
                    val mirroredFace = Bitmap.createBitmap(faceCrop, 0, 0, faceCrop.width, faceCrop.height, matrix, true)
                    
                    // Convert to Grayscale (Bypasses color-based privacy filters)
                    val grayFace = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    val grayCanvas = Canvas(grayFace)
                    val grayPaint = Paint()
                    val cm = ColorMatrix()
                    cm.setSaturation(0f)
                    grayPaint.colorFilter = ColorMatrixColorFilter(cm)
                    grayCanvas.drawBitmap(Bitmap.createScaledBitmap(mirroredFace, 512, 512, true), 0f, 0f, grayPaint)
                    
                    val camoFace = ImageEnhancer.applyCamouflage(grayFace)
                    canvas.drawBitmap(camoFace, 512f, 512f, null)

                    continuation.resume(composite)
                } else {
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener { continuation.resume(bitmap) }
    }
}
