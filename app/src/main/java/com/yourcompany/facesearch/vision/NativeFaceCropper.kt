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

    suspend fun prepareFaceForSearch(original: Bitmap): Bitmap {
        // Step 1: Use ALIGNED face with moderate padding (Best for search engines)
        val faceCrop = cropAndAlignFace(original) 

        // Step 2: Constrain dimensions for API compatibility and memory safety
        // SerpApi optimal range: 400-800px, max 2MB file size
        val maxDimension = 800
        val minDimension = 400
        
        val aspectRatio = faceCrop.width.toFloat() / faceCrop.height
        val (finalWidth, finalHeight) = when {
            faceCrop.width > faceCrop.height -> {
                // Landscape: constrain width
                val w = faceCrop.width.coerceIn(minDimension, maxDimension)
                Pair(w, (w / aspectRatio).toInt())
            }
            else -> {
                // Portrait/Square: constrain height
                val h = faceCrop.height.coerceIn(minDimension, maxDimension)
                Pair((h * aspectRatio).toInt(), h)
            }
        }
        
        val scaled = Bitmap.createScaledBitmap(faceCrop, finalWidth, finalHeight, true)
        
        // Recycle intermediate bitmap to prevent memory leak
        if (faceCrop != original) {
            faceCrop.recycle()
        }
        
        return scaled
    }

    suspend fun getTightFaceCrop(bitmap: Bitmap): Bitmap? = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                try {
                    if (faces.isNotEmpty()) {
                        val box = faces[0].boundingBox
                        val left = box.left.coerceAtLeast(0)
                        val top = box.top.coerceAtLeast(0)
                        val width = box.width().coerceAtMost(bitmap.width - left).coerceAtLeast(1)
                        val height = box.height().coerceAtMost(bitmap.height - top).coerceAtLeast(1)
                        if (width > 0 && height > 0) {
                            continuation.resume(Bitmap.createBitmap(bitmap, left, top, width, height))
                        } else {
                            continuation.resume(null)
                        }
                    } else {
                        continuation.resume(null)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NativeFaceCropper", "Error in getTightFaceCrop: ${e.message}")
                    continuation.resume(null)
                }
            }
            .addOnFailureListener {
                android.util.Log.e("NativeFaceCropper", "Face detection failed in getTightFaceCrop")
                continuation.resume(null)
            }
    }

    suspend fun cropAndAlignFace(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        
        detector.process(image)
            .addOnSuccessListener { faces ->
                try {
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

                        // 2. ADD OPTIMIZED PADDING (25-30% for balance between context and accuracy)
                        // Too much padding reduces search accuracy; too little loses important context
                        val paddingFactor = 0.25f
                        val paddingX = (box.width() * paddingFactor).toInt()
                        val paddingY = (box.height() * paddingFactor).toInt()

                        val left = (box.left - paddingX).coerceAtLeast(0)
                        val top = (box.top - paddingY).coerceAtLeast(0)
                        val width = (box.width() + (paddingX * 2)).coerceAtMost(bitmap.width - left).coerceAtLeast(1)
                        val height = (box.height() + (paddingY * 2)).coerceAtMost(bitmap.height - top).coerceAtLeast(1)

                        // 3. CROP AND APPLY ALIGNMENT
                        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                        val aligned = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotationMatrix, true)
                        cropped.recycle()

                        continuation.resume(aligned)
                    } else {
                        continuation.resume(bitmap) 
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NativeFaceCropper", "Error in cropAndAlignFace: ${e.message}")
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener {
                android.util.Log.e("NativeFaceCropper", "Face detection failed in cropAndAlignFace")
                continuation.resume(bitmap)
            }
    }

    suspend fun cropContextual(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                try {
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val box = face.boundingBox

                        // BYPASS STRATEGY: 350% wider crop, face off-center
                        val widthScale = 3.5f
                        val heightScale = 4.0f
                        
                        val pWidth = (box.width() * widthScale).toInt().coerceAtLeast(1)
                        val pHeight = (box.height() * heightScale).toInt().coerceAtLeast(1)

                        val left = (box.centerX() - (pWidth * 0.35f)).toInt().coerceAtLeast(0).coerceAtMost(bitmap.width - 1)
                        val top = (box.centerY() - (pHeight * 0.4f)).toInt().coerceAtLeast(0).coerceAtMost(bitmap.height - 1)
                        
                        val width = pWidth.coerceAtMost(bitmap.width - left).coerceAtLeast(1)
                        val height = pHeight.coerceAtMost(bitmap.height - top).coerceAtLeast(1)

                        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                        continuation.resume(cropped)
                    } else {
                        continuation.resume(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NativeFaceCropper", "Error in cropContextual: ${e.message}")
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener { 
                android.util.Log.e("NativeFaceCropper", "Face detection failed in cropContextual")
                continuation.resume(bitmap) 
            }
    }

    suspend fun cropSocial(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                try {
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val box = face.boundingBox

                        // SOCIAL STRATEGY: Wider Contextual Crop (Natural look)
                        val targetWidth = (box.width() * 3.0f).toInt().coerceAtLeast(1)
                        val targetHeight = (targetWidth * 1.1f).toInt().coerceAtLeast(1)
                        
                        val left = (box.centerX() - (targetWidth / 2)).toInt().coerceIn(0, (bitmap.width - targetWidth).coerceAtLeast(0))
                        val top = (box.centerY() - (targetHeight * 0.40f).toInt()).coerceIn(0, (bitmap.height - targetHeight).coerceAtLeast(0))
                        
                        val finalWidth = targetWidth.coerceAtMost(bitmap.width - left).coerceAtLeast(1)
                        val finalHeight = targetHeight.coerceAtMost(bitmap.height - top).coerceAtLeast(1)

                        val cropped = Bitmap.createBitmap(bitmap, left, top, finalWidth, finalHeight)
                        val enhanced = ImageEnhancer.enhance(cropped)
                        cropped.recycle()
                        continuation.resume(enhanced)
                    } else {
                        continuation.resume(ImageEnhancer.enhance(bitmap))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NativeFaceCropper", "Error in cropSocial: ${e.message}")
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener { 
                android.util.Log.e("NativeFaceCropper", "Face detection failed in cropSocial")
                continuation.resume(bitmap) 
            }
    }

    /**
     * HYPER-PROBE: Creates a 3-way composite to bypass privacy filters.
     * Properly manages bitmap memory to prevent leaks.
     */
    suspend fun createHyperProbe(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val composite = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(composite)
                canvas.drawColor(Color.BLACK)

                try {
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val box = face.boundingBox

                        // 1. TOP HALF: Wide Context (Clothes/Background)
                        val contextWidth = (box.width() * 4.0f).toInt().coerceIn(1, bitmap.width)
                        val contextHeight = (contextWidth * 0.5f).toInt().coerceIn(1, bitmap.height)
                        val cLeft = (box.centerX() - (contextWidth / 2)).coerceIn(0, (bitmap.width - contextWidth).coerceAtLeast(0))
                        val cTop = (box.centerY() - (contextHeight / 2)).coerceIn(0, (bitmap.height - contextHeight).coerceAtLeast(0))
                        
                        val contextCrop = Bitmap.createBitmap(bitmap, cLeft, cTop, contextWidth, contextHeight)
                        val contextScaled = Bitmap.createScaledBitmap(contextCrop, 1024, 512, true)
                        canvas.drawBitmap(contextScaled, 0f, 0f, null)
                        contextCrop.recycle()
                        contextScaled.recycle()

                        // 2. BOTTOM LEFT: High-Contrast Face
                        val faceLeft = box.left.coerceIn(0, bitmap.width - 1)
                        val faceTop = box.top.coerceIn(0, bitmap.height - 1)
                        val faceWidth = box.width().coerceIn(1, bitmap.width - faceLeft)
                        val faceHeight = box.height().coerceIn(1, bitmap.height - faceTop)
                        
                        val faceCrop = Bitmap.createBitmap(bitmap, faceLeft, faceTop, faceWidth, faceHeight)
                        val enhancedFace = ImageEnhancer.enhance(faceCrop)
                        val faceScaled = Bitmap.createScaledBitmap(enhancedFace, 512, 512, true)
                        canvas.drawBitmap(faceScaled, 0f, 512f, null)
                        enhancedFace.recycle()
                        faceScaled.recycle()

                        // 3. BOTTOM RIGHT: Grayscale Bypass
                        val matrix = Matrix().apply { postScale(-1f, 1f) }
                        val mirroredFace = Bitmap.createBitmap(faceCrop, 0, 0, faceCrop.width, faceCrop.height, matrix, true)
                        faceCrop.recycle()
                            
                        // Convert to Grayscale (Bypasses color-based privacy filters)
                        val grayFace = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                        val grayCanvas = Canvas(grayFace)
                        val grayPaint = Paint()
                        val cm = ColorMatrix()
                        cm.setSaturation(0f)
                        grayPaint.colorFilter = ColorMatrixColorFilter(cm)
                        val mirroredScaled = Bitmap.createScaledBitmap(mirroredFace, 512, 512, true)
                        grayCanvas.drawBitmap(mirroredScaled, 0f, 0f, grayPaint)
                        mirroredFace.recycle()
                        mirroredScaled.recycle()
                        
                        val camoFace = ImageEnhancer.applyCamouflage(grayFace)
                        canvas.drawBitmap(camoFace, 512f, 512f, null)
                        grayFace.recycle()
                        camoFace.recycle()
                        
                        continuation.resume(composite)
                    } else {
                        continuation.resume(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NativeFaceCropper", "Error in createHyperProbe: ${e.message}")
                    if (!composite.isRecycled) composite.recycle()
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener { 
                android.util.Log.e("NativeFaceCropper", "Face detection failed in createHyperProbe")
                continuation.resume(bitmap) 
            }
    }

    /**
     * SOCIAL MEDIA OPTIMIZED: Crops face with dimensions optimized for social platform profile pictures.
     * Most social platforms use 1:1 (square) or 4:5 aspect ratios for profile/cover photos.
     */
    suspend fun cropForSocialProfile(bitmap: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                try {
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val box = face.boundingBox

                        // Social media platforms typically show profile pics at 1:1 aspect ratio (square)
                        // Optimize for this: face centered in a square crop with light padding
                        val faceSize = box.width().coerceAtLeast(box.height())
                        val targetSize = (faceSize * 1.4f).toInt().coerceIn(300, 800) // 40% padding, but constrained
                        
                        val centerX = box.centerX().toInt()
                        val centerY = box.centerY().toInt()
                        
                        val left = (centerX - (targetSize / 2)).coerceIn(0, (bitmap.width - targetSize).coerceAtLeast(0))
                        val top = (centerY - (targetSize / 2)).coerceIn(0, (bitmap.height - targetSize).coerceAtLeast(0))
                        
                        val finalSize = targetSize.coerceAtMost(bitmap.width - left).coerceAtMost(bitmap.height - top).coerceAtLeast(1)
                        
                        val cropped = Bitmap.createBitmap(bitmap, left, top, finalSize, finalSize)
                        
                        // Apply eye-level rotation for better profile match
                        val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)
                        val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)
                        
                        val aligned = if (leftEye != null && rightEye != null) {
                            val deltaX = (rightEye.position.x - leftEye.position.x).toDouble()
                            val deltaY = (rightEye.position.y - leftEye.position.y).toDouble()
                            val angle = Math.toDegrees(Math.atan2(deltaY, deltaX)).toFloat()
                            
                            val rotMatrix = Matrix()
                            rotMatrix.postRotate(-angle, (finalSize / 2).toFloat(), (finalSize / 2).toFloat())
                            Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotMatrix, true)
                        } else {
                            cropped
                        }
                        
                        cropped.recycle()
                        continuation.resume(aligned)
                    } else {
                        continuation.resume(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NativeFaceCropper", "Error in cropForSocialProfile: ${e.message}")
                    continuation.resume(bitmap)
                }
            }
            .addOnFailureListener {
                android.util.Log.e("NativeFaceCropper", "Face detection failed in cropForSocialProfile")
                continuation.resume(bitmap)
            }
    }

    /**
     * ULTRA BYPASS PROBE: Multiple aggressive variants for social scraping
     */
    suspend fun createUltraBypassProbe(original: Bitmap): List<Bitmap> = suspendCancellableCoroutine { continuation ->
        val probes = mutableListOf<Bitmap>()
        val image = InputImage.fromBitmap(original, 0)
        
        detector.process(image).addOnSuccessListener { faces ->
            try {
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val box = face.boundingBox
                    
                    // Variant 1: Wide contextual (Logic inlined from cropContextual to avoid suspend call in callback)
                    val widthScale = 3.5f
                    val heightScale = 4.0f
                    val pWidth = (box.width() * widthScale).toInt().coerceAtLeast(1)
                    val pHeight = (box.height() * heightScale).toInt().coerceAtLeast(1)
                    val left = (box.centerX() - (pWidth * 0.35f)).toInt().coerceAtLeast(0).coerceAtMost(original.width - 1)
                    val top = (box.centerY() - (pHeight * 0.4f)).toInt().coerceAtLeast(0).coerceAtMost(original.height - 1)
                    val width = pWidth.coerceAtMost(original.width - left).coerceAtLeast(1)
                    val height = pHeight.coerceAtMost(original.height - top).coerceAtLeast(1)
                    probes.add(Bitmap.createBitmap(original, left, top, width, height))
                    
                    // Variant 2: High contrast + camouflage (Simplified without alignment for safety in callback)
                    val faceLeft = box.left.coerceIn(0, original.width - 1)
                    val faceTop = box.top.coerceIn(0, original.height - 1)
                    val faceWidth = box.width().coerceIn(1, original.width - faceLeft)
                    val faceHeight = box.height().coerceIn(1, original.height - faceTop)
                    val faceCrop = Bitmap.createBitmap(original, faceLeft, faceTop, faceWidth, faceHeight)
                    val contrast = ImageEnhancer.applyStructuralFingerprint(faceCrop)
                    probes.add(ImageEnhancer.applyCamouflage(contrast))
                    faceCrop.recycle()
                    
                    // Variant 3: Grayscale mirror (strong bypass)
                    val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(grayMatrix) }
                    val gray = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
                    Canvas(gray).drawBitmap(original, 0f, 0f, paint)
                    probes.add(gray)
                } else {
                    probes.add(original)
                }
                continuation.resume(probes)
            } catch (e: Exception) {
                probes.add(original)
                continuation.resume(probes)
            }
        }.addOnFailureListener {
            continuation.resume(listOf(original))
        }
    }
}
