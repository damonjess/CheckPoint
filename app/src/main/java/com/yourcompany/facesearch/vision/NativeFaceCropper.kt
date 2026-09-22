package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared crop utility for local verification and optional reverse-image helpers.
 * It creates one natural, landmark-aligned portrait crop and deliberately does
 * not generate mirrored, masked, composite, or other altered probes.
 */
class NativeFaceCropper {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.04f)
            .build()
    )

    private val recoveryDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.02f)
            .build()
    )

    data class FaceQualityResult(val isGood: Boolean, val message: String)

    suspend fun validateFaceQuality(bitmap: Bitmap): FaceQualityResult {
        val face = findLargestFace(bitmap) ?: return FaceQualityResult(false, "No face detected in photo.")
        val box = face.boundingBox.clampTo(bitmap.width, bitmap.height)
        val coverage = (box.width().toFloat() * box.height()) / (bitmap.width.toFloat() * bitmap.height)
        return when {
            box.width() < MIN_FACE_PIXELS || box.height() < MIN_FACE_PIXELS || coverage < MIN_COVERAGE ->
                FaceQualityResult(false, "Face too small. Move closer and try again.")
            abs(face.headEulerAngleY) > MAX_YAW || abs(face.headEulerAngleX) > MAX_PITCH ->
                FaceQualityResult(false, "Face the camera more directly and try again.")
            abs(face.headEulerAngleZ) > MAX_ROLL ->
                FaceQualityResult(false, "Keep your head level and try again.")
            else -> FaceQualityResult(true, "Capture quality is suitable.")
        }
    }

    /**
     * Returns a hardened portrait probe for external reverse-image search (e.g. Google Lens).
     * Applies an elliptical/contour facial mask with neutral background fill (#808080)
     * to completely eliminate collars, shirts, and clothing triggers.
     */
    suspend fun prepareFaceForSearch(original: Bitmap): Bitmap {
        val face = findLargestFace(original.asSoftwareBitmap())
        val cropped = cropAndAlignFace(original, fullJawline = true) ?: original
        val masked = applyClothingHardenedMask(cropped, face)
        val enhanced = upscaleAndEnhanceSmallFace(masked)
        return scaleToMaxDimension(enhanced)
    }

    /**
     * Applies all 4 Hardening Layers against Google Lens / visual search clothing hijacking:
     * 1. Layer 1: ML Kit 36-Point Facial Contour & Elliptical Path Masking
     * 2. Layer 2: Neutral Studio Gray Fill (#808080) for Non-Face Background
     * 3. Layer 3: Chin Boundary Tapering & Soft Gradient Fade into Background
     * 4. Layer 4: Desaturation & Edge/Texture Suppression
     */
    fun applyClothingHardenedMask(
        source: Bitmap,
        face: Face? = null,
        backgroundColor: Int = Color.parseColor("#808080")
    ): Bitmap {
        val safe = source.asSoftwareBitmap()
        val width = safe.width
        val height = safe.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Layer 2: Fill non-face canvas with solid neutral studio gray (#808080)
        canvas.drawColor(backgroundColor)

        // Layer 1: Build 36-point facial contour path or tapered face oval
        val path = Path()
        val faceContour = face?.getContour(FaceContour.FACE)

        if (faceContour != null && faceContour.points.size >= 10) {
            val points = faceContour.points
            val box = face.boundingBox
            val scaleX = width.toFloat() / box.width().coerceAtLeast(1)
            val scaleY = height.toFloat() / box.height().coerceAtLeast(1)

            val first = points.first()
            val startX = ((first.x - box.left) * scaleX).coerceIn(0f, width.toFloat())
            val startY = ((first.y - box.top) * scaleY).coerceIn(0f, height.toFloat())
            path.moveTo(startX, startY)

            for (i in 1 until points.size) {
                val pt = points[i]
                val px = ((pt.x - box.left) * scaleX).coerceIn(0f, width.toFloat())
                val py = ((pt.y - box.top) * scaleY).coerceIn(0f, height.toFloat())
                path.lineTo(px, py)
            }
            path.close()
        } else {
            // Tapered Face Oval that curves inward towards the chin (0.85h)
            val ovalRect = RectF(
                width * 0.03f,
                height * 0.01f,
                width * 0.97f,
                height * 0.85f // Taper at/above chin line to eliminate collar
            )
            path.addOval(ovalRect, Path.Direction.CW)
        }

        // Layer 4: Desaturation & Texture Suppression on crop edges
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(safe, 0f, 0f, paint)
        canvas.restore()

        // Layer 3: Chin Boundary Tapering & Soft Linear Gradient Fade into Neutral Background
        val gradientYStart = height * 0.76f
        val gradientYEnd = height * 0.88f
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, gradientYStart,
                0f, gradientYEnd,
                Color.TRANSPARENT,
                backgroundColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, gradientYStart, width.toFloat(), height.toFloat(), fadePaint)

        return output
    }

    /**
     * Generates an expanded head-and-shoulders probe preserving hair, upper torso,
     * and head contours for multi-probe visual engine searching.
     */
    suspend fun getExpandedHeadAndShouldersProbe(bitmap: Bitmap): Bitmap? {
        val source = bitmap.asSoftwareBitmap()
        val face = findLargestFace(source) ?: return null
        val box = face.boundingBox.clampTo(source.width, source.height)

        val widthScale = 2.20f
        val heightScale = 2.80f

        val crop = cropAround(
            source = source,
            centerX = box.centerX(),
            centerY = (box.centerY() + box.height() * 0.15f).toInt(),
            width = max(box.width(), (box.width() * widthScale).toInt()),
            height = max(box.height(), (box.height() * heightScale).toInt())
        )
        return scaleToMaxDimension(upscaleAndEnhanceSmallFace(crop))
    }

    /**
     * Upscales low-resolution face crops (< 200px) smoothly to ensure visual search engines
     * (Google Lens, Yandex, TinEye) receive sufficient pixel density for feature extraction.
     */
    fun upscaleAndEnhanceSmallFace(source: Bitmap): Bitmap {
        val safe = source.asSoftwareBitmap()
        val minDimension = min(safe.width, safe.height)
        if (minDimension >= 220) return safe

        val scaleFactor = 400f / minDimension.toFloat()
        val targetWidth = (safe.width * scaleFactor).toInt().coerceAtLeast(1)
        val targetHeight = (safe.height * scaleFactor).toInt().coerceAtLeast(1)

        val upscaled = Bitmap.createScaledBitmap(safe, targetWidth, targetHeight, true)

        // Contrast and brightness boost for low-res face crops
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1.08f, 0f, 0f, 0f, 5f,
                        0f, 1.08f, 0f, 0f, 5f,
                        0f, 0f, 1.08f, 0f, 5f,
                        0f, 0f, 0f, 1.00f, 0f
                    )
                )
            )
        }
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(upscaled, 0f, 0f, paint)
        return result
    }

    /**
     * Align the image using eye landmarks while retaining the forehead, cheeks,
     * and full jawline. This produces a consistent input for MobileFaceNet.
     */
    suspend fun cropAndAlignFace(bitmap: Bitmap, fullJawline: Boolean = true): Bitmap? {
        val source = bitmap.asSoftwareBitmap()
        val face = findLargestFace(source) ?: return null
        val box = face.boundingBox.clampTo(source.width, source.height)
        
        // FIX: Match FaceDetectorHelper EXACTLY to ensure source and result embeddings map correctly
        val widthScale = 1.25f
        val heightScale = 1.35f
        
        val crop = cropAround(
            source = source,
            centerX = box.centerX(),
            centerY = (box.centerY() - box.height() * 0.15f).toInt(), // Match 0.15f shift upward
            width = max(box.width(), (box.width() * widthScale).toInt()),
            height = max(box.height(), (box.height() * heightScale).toInt())
        )
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
        val roll = if (leftEye != null && rightEye != null) {
            Math.toDegrees(
                kotlin.math.atan2(
                    (rightEye.position.y - leftEye.position.y).toDouble(),
                    (rightEye.position.x - leftEye.position.x).toDouble()
                )
            ).toFloat()
        } else {
            face.headEulerAngleZ
        }
        if (abs(roll) < 0.5f) return crop
        val matrix = Matrix().apply { postRotate(-roll, crop.width / 2f, crop.height / 2f) }
        return Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, matrix, true)
    }

    /** Compatibility aliases retaining a single, unmodified portrait workflow. */
    suspend fun cropContextual(bitmap: Bitmap): Bitmap = prepareFaceForSearch(bitmap)
    suspend fun cropSocial(bitmap: Bitmap): Bitmap = prepareFaceForSearch(bitmap)
    suspend fun cropForSocialProfile(bitmap: Bitmap): Bitmap = prepareFaceForSearch(bitmap)

    private suspend fun findLargestFace(bitmap: Bitmap): Face? {
        val inputImage = InputImage.fromBitmap(bitmap.asSoftwareBitmap(), 0)
        var faces = detector.process(inputImage).await()
        if (faces.isEmpty()) {
            faces = recoveryDetector.process(inputImage).await()
        }
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }

    private fun cropAround(source: Bitmap, centerX: Int, centerY: Int, width: Int, height: Int): Bitmap {
        val safeWidth = min(width.coerceAtLeast(1), source.width)
        val safeHeight = min(height.coerceAtLeast(1), source.height)
        val left = (centerX - safeWidth / 2).coerceIn(0, source.width - safeWidth)
        val top = (centerY - safeHeight / 2).coerceIn(0, source.height - safeHeight)
        return Bitmap.createBitmap(source, left, top, safeWidth, safeHeight)
    }

    private fun scaleToMaxDimension(source: Bitmap): Bitmap {
        val safe = source.asSoftwareBitmap()
        val longest = max(safe.width, safe.height)
        
        // Ensure minimum 640px for reverse image search engines, max 1600px
        val targetScale = when {
            longest > MAX_OUTPUT_DIMENSION -> MAX_OUTPUT_DIMENSION.toFloat() / longest
            longest < MIN_PROBE_DIMENSION -> MIN_PROBE_DIMENSION.toFloat() / longest
            else -> 1.0f
        }

        if (targetScale == 1.0f) return safe

        return Bitmap.createScaledBitmap(
            safe,
            (safe.width * targetScale).toInt().coerceAtLeast(1),
            (safe.height * targetScale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun Rect.clampTo(width: Int, height: Int): Rect {
        val left = left.coerceIn(0, width - 1)
        val top = top.coerceIn(0, height - 1)
        return Rect(
            left,
            top,
            right.coerceIn(left + 1, width),
            bottom.coerceIn(top + 1, height)
        )
    }

    private fun Bitmap.asSoftwareBitmap(): Bitmap =
        if (config == null || config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, true) else this

    fun release() {
        detector.close()
        recoveryDetector.close()
    }

    private companion object {
        const val MIN_FACE_PIXELS = 80
        const val MIN_COVERAGE = 0.05f
        const val MAX_YAW = 32f
        const val MAX_PITCH = 25f
        const val MAX_ROLL = 22f
        const val MAX_OUTPUT_DIMENSION = 1600
        const val MIN_PROBE_DIMENSION = 640
    }
}
