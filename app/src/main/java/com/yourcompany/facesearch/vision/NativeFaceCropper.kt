package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
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
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.10f)
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
     * Returns a natural portrait for optional external reverse-image search.
     * The application’s primary flow sends a normalized original photo instead.
     */
    suspend fun prepareFaceForSearch(original: Bitmap): Bitmap =
        cropAndAlignFace(original, fullJawline = true)?.let(::scaleToMaxDimension) ?: scaleToMaxDimension(original)

    suspend fun getTightFaceCrop(bitmap: Bitmap): Bitmap? {
        val source = bitmap.asSoftwareBitmap()
        val face = findLargestFace(source) ?: return null
        val box = face.boundingBox.clampTo(source.width, source.height)
        val paddingX = (box.width() * 0.08f).toInt()
        val paddingY = (box.height() * 0.08f).toInt()
        return cropAround(
            source = source,
            centerX = box.centerX(),
            centerY = box.centerY(),
            width = box.width() + 2 * paddingX,
            height = box.height() + 2 * paddingY
        )
    }

    /**
     * Align the image using eye landmarks while retaining the forehead, cheeks,
     * and full jawline. This produces a consistent input for MobileFaceNet.
     */
    suspend fun cropAndAlignFace(bitmap: Bitmap, fullJawline: Boolean = true): Bitmap? {
        val source = bitmap.asSoftwareBitmap()
        val face = findLargestFace(source) ?: return null
        val box = face.boundingBox.clampTo(source.width, source.height)
        val widthScale = 1.55f
        val heightScale = if (fullJawline) 1.90f else 1.70f
        val crop = cropAround(
            source = source,
            centerX = box.centerX(),
            centerY = (box.centerY() - box.height() * 0.04f).toInt(),
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

    private suspend fun findLargestFace(bitmap: Bitmap): Face? =
        detector.process(InputImage.fromBitmap(bitmap.asSoftwareBitmap(), 0)).await()
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

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
        if (longest <= MAX_OUTPUT_DIMENSION) return safe
        val scale = MAX_OUTPUT_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            safe,
            (safe.width * scale).toInt().coerceAtLeast(1),
            (safe.height * scale).toInt().coerceAtLeast(1),
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

    fun release() = detector.close()

    private companion object {
        const val MIN_FACE_PIXELS = 80
        const val MIN_COVERAGE = 0.05f
        const val MAX_YAW = 32f
        const val MAX_PITCH = 25f
        const val MAX_ROLL = 22f
        const val MAX_OUTPUT_DIMENSION = 1600
    }
}
