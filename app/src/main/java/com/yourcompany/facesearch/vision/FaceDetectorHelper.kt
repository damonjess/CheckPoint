package com.yourcompany.facesearch.vision

import android.content.Context
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

sealed class FaceDetectionResult {
    data class Success(
        val croppedFace: Bitmap,
        val boundingBox: Rect,
        val quality: FaceQuality
    ) : FaceDetectionResult()

    data object NoFaceFound : FaceDetectionResult()
    data class MultipleFacesFound(val count: Int) : FaceDetectionResult()
    data class PoorQuality(val reason: String, val quality: FaceQuality) : FaceDetectionResult()
    data class Error(val exception: Exception) : FaceDetectionResult()
}

/**
 * Capture-quality measurements collected before an image is used for enrollment
 * or on-device verification. Values are local to the device and are not sent
 * anywhere by this class.
 */
data class FaceQuality(
    val faceCoverage: Float,
    val faceWidthPx: Int,
    val faceHeightPx: Int,
    val meanLuminance: Float,
    val sharpness: Float,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?
)

/**
 * On-device face detector for a single, consented self-photo. It rejects
 * ambiguous frames, checks capture quality, then produces a landmark-aligned
 * crop that retains the full jawline and forehead for consistent embeddings.
 */
class FaceDetectorHelper(private val context: Context) {

    init {
        // High-accuracy models have been restored to assets.
        // ML Kit's bundled detector will automatically load libface_detector_v2_jni.so
        // from jniLibs and look for models in the assets root.
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.01f) // More permissive: 1% of frame instead of 5%
            .build()
    )

    // Used only when the user is capturing their own search photo. It keeps
    // landmark detection but allows ML Kit to inspect a smaller clear face when
    // the strict primary detector returns no result.
    private val captureFallbackDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.04f)
            .build()
    )

    // Visual-search providers frequently return small thumbnails. The primary
    // detector's 10% minimum face size rejects many otherwise valid results
    // before the candidate-specific pixel and coverage checks can run. This
    // detector is intentionally used only for candidate eligibility; those
    // explicit checks below still require one visible, usable face.
    private val candidateDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.04f)
            .build()
    )

    // Last-resort detector for a user-selected image. It is only consulted
    // after the accurate detectors find nothing, and its result still passes
    // the single-face and fallback-quality rules below. This avoids a false
    // “no face detected” result for a clear but relatively small face.
    private val recoveryDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.02f)
            .build()
    )

    suspend fun detectAndCropFace(
        sourceBitmap: Bitmap,
        allowCaptureFallback: Boolean = false
    ): FaceDetectionResult {
        val minimumWidth = if (allowCaptureFallback) {
            MIN_CAPTURE_FALLBACK_IMAGE_EDGE
        } else {
            MIN_IMAGE_WIDTH
        }
        val minimumHeight = if (allowCaptureFallback) {
            MIN_CAPTURE_FALLBACK_IMAGE_EDGE
        } else {
            MIN_IMAGE_HEIGHT
        }
        if (sourceBitmap.width < minimumWidth || sourceBitmap.height < minimumHeight) {
            return FaceDetectionResult.PoorQuality(
                "Use a face image at least ${minimumWidth}×${minimumHeight} pixels.",
                emptyQuality()
            )
        }

        return try {
            val bitmap = sourceBitmap.asSoftwareBitmap()
            val primaryFaces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            val fallbackFaces = if (allowCaptureFallback && primaryFaces.isEmpty()) {
                captureFallbackDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
            } else {
                emptyList()
            }
            val recoveryFaces = if (allowCaptureFallback && primaryFaces.isEmpty() && fallbackFaces.isEmpty()) {
                recoveryDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
            } else {
                emptyList()
            }
            val faces = when {
                primaryFaces.isNotEmpty() -> primaryFaces
                fallbackFaces.isNotEmpty() -> fallbackFaces
                else -> recoveryFaces
            }
            val usedRelaxedDetector = primaryFaces.isEmpty() &&
                (fallbackFaces.isNotEmpty() || recoveryFaces.isNotEmpty())

            val selectedFace = selectSingleOrDominantFace(faces, bitmap)
            when {
                faces.isEmpty() -> FaceDetectionResult.NoFaceFound
                selectedFace == null -> FaceDetectionResult.MultipleFacesFound(faces.size)
                else -> {
                    val face = selectedFace
                    val quality = assessQuality(bitmap, face)
                    val strictRejection = qualityRejectionReason(quality)
                    val rejection = if (allowCaptureFallback && (usedRelaxedDetector || strictRejection != null)) {
                        captureFallbackRejectionReason(quality)
                    } else {
                        strictRejection
                    }
                    if (rejection != null) {
                        FaceDetectionResult.PoorQuality(rejection, quality)
                    } else {
                        FaceDetectionResult.Success(
                            croppedFace = cropAndAlign(bitmap, face),
                            boundingBox = face.boundingBox,
                            quality = quality
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            FaceDetectionResult.Error(exception)
        }
    }

    /**
     * A lightweight eligibility check for reverse-image candidates. Unlike capture
     * enrollment, this intentionally does not apply lighting, pose, or sharpness
     * rules; it only rejects product shots, silhouettes, group images, and other
     * thumbnails without one sufficiently visible face.
     */
    suspend fun hasSingleCandidateFace(sourceBitmap: Bitmap): Boolean {
        if (sourceBitmap.width < MIN_CANDIDATE_IMAGE_EDGE || sourceBitmap.height < MIN_CANDIDATE_IMAGE_EDGE) {
            return false
        }
        return try {
            val bitmap = sourceBitmap.asSoftwareBitmap()
            val primaryFaces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            val candidateFaces = if (primaryFaces.isEmpty()) {
                candidateDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
            } else {
                emptyList()
            }
            val recoveryFaces = if (primaryFaces.isEmpty() && candidateFaces.isEmpty()) {
                recoveryDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
            } else {
                emptyList()
            }
            val faces = when {
                primaryFaces.isNotEmpty() -> primaryFaces
                candidateFaces.isNotEmpty() -> candidateFaces
                else -> recoveryFaces
            }
            if (faces.isEmpty()) return false

            // Use the largest detected face as the primary candidate.
            // This allows group photos to be processed while focusing on the dominant subject.
            val dominant = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return false

            val box = dominant.boundingBox.clampTo(bitmap.width, bitmap.height)
            
            // Permissive check: just ensure the face has some minimum size in pixels.
            // We ignore coverage percentage for candidates to capture background people.
            box.width() >= 8 && box.height() >= 8
        } catch (_: Exception) {
            false
        }
    }

    /**
     * ML Kit can occasionally emit a very small false positive alongside a
     * clearly dominant real face. Keep the app safe for actual group photos,
     * but use the dominant face when it is at least 2.5× the next detection and
     * occupies a meaningful part of the selected image.
     */
    private fun selectSingleOrDominantFace(faces: List<Face>, bitmap: Bitmap): Face? {
        if (faces.isEmpty()) return null
        if (faces.size == 1) return faces.single()

        val ranked = faces.sortedByDescending { face ->
            val box = face.boundingBox.clampTo(bitmap.width, bitmap.height)
            box.width().toLong() * box.height().toLong()
        }
        val dominant = ranked.first()
        val dominantBox = dominant.boundingBox.clampTo(bitmap.width, bitmap.height)
        val dominantArea = dominantBox.width().toLong() * dominantBox.height().toLong()
        val nextBox = ranked[1].boundingBox.clampTo(bitmap.width, bitmap.height)
        val nextArea = (nextBox.width().toLong() * nextBox.height().toLong()).coerceAtLeast(1L)
        val dominantCoverage = dominantArea.toFloat() /
            (bitmap.width.toLong() * bitmap.height.toLong()).coerceAtLeast(1L)

        return if (
            dominantArea.toDouble() >= nextArea.toDouble() * DOMINANT_FACE_AREA_RATIO &&
            dominantBox.width() >= MIN_DOMINANT_FACE_PIXELS &&
            dominantBox.height() >= MIN_DOMINANT_FACE_PIXELS &&
            dominantCoverage >= MIN_DOMINANT_FACE_COVERAGE
        ) {
            dominant
        } else {
            null
        }
    }

    private fun assessQuality(bitmap: Bitmap, face: Face): FaceQuality {
        val box = face.boundingBox.clampTo(bitmap.width, bitmap.height)
        val faceRegion = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val faceCoverage = (box.width().toFloat() * box.height().toFloat()) /
            (bitmap.width.toFloat() * bitmap.height.toFloat())

        return FaceQuality(
            faceCoverage = faceCoverage,
            faceWidthPx = box.width(),
            faceHeightPx = box.height(),
            meanLuminance = meanLuminance(faceRegion),
            sharpness = laplacianVariance(faceRegion),
            yawDegrees = face.headEulerAngleY,
            pitchDegrees = face.headEulerAngleX,
            rollDegrees = face.headEulerAngleZ,
            leftEyeOpenProbability = face.leftEyeOpenProbability,
            rightEyeOpenProbability = face.rightEyeOpenProbability
        )
    }

    private fun qualityRejectionReason(quality: FaceQuality): String? = when {
        quality.faceWidthPx < MIN_FACE_PIXELS || quality.faceHeightPx < MIN_FACE_PIXELS ->
            "Move closer so your face is clear and fills more of the frame."
        quality.faceCoverage < MIN_FACE_COVERAGE ->
            "Move closer so your face occupies more of the photo."
        abs(quality.yawDegrees) > MAX_YAW_DEGREES || abs(quality.pitchDegrees) > MAX_PITCH_DEGREES ->
            "Face the camera more directly and try again."
        abs(quality.rollDegrees) > MAX_ROLL_DEGREES ->
            "Keep your head level and try again."
        quality.meanLuminance !in MIN_LUMINANCE..MAX_LUMINANCE ->
            "Use even, front-facing lighting and try again."
        quality.sharpness < MIN_SHARPNESS ->
            "The photo is too blurry. Hold still and refocus before taking another photo."
        quality.leftEyeOpenProbability != null && quality.leftEyeOpenProbability < MIN_EYE_OPEN_PROBABILITY ->
            "Keep both eyes open and look at the camera."
        quality.rightEyeOpenProbability != null && quality.rightEyeOpenProbability < MIN_EYE_OPEN_PROBABILITY ->
            "Keep both eyes open and look at the camera."
        else -> null
    }

    /**
     * Capture fallback remains conservative about image usability but does not
     * reject a single clear self-photo solely for moderate pose, eye, or roll.
     */
    private fun captureFallbackRejectionReason(quality: FaceQuality): String? = when {
        quality.faceWidthPx < MIN_CAPTURE_FALLBACK_FACE_PIXELS ||
            quality.faceHeightPx < MIN_CAPTURE_FALLBACK_FACE_PIXELS ->
            "Move a little closer so your face is easier to detect."
        quality.faceCoverage < MIN_CAPTURE_FALLBACK_FACE_COVERAGE ->
            "Use a closer photo with your face taking up more of the frame."
        quality.meanLuminance !in MIN_CAPTURE_FALLBACK_LUMINANCE..MAX_CAPTURE_FALLBACK_LUMINANCE ->
            "Try a brighter, evenly lit photo."
        quality.sharpness < MIN_CAPTURE_FALLBACK_SHARPNESS ->
            "The photo is too blurry. Hold still and try again."
        else -> null
    }

    private fun cropAndAlign(source: Bitmap, face: Face): Bitmap {
        val box = face.boundingBox.clampTo(source.width, source.height)
        // Tightened isolation: focus strictly on the head.
        // Reduced from 1.75f/2.15f to 1.25x/1.55x to ignore clothing and background.
        val width = min(source.width, max(box.width(), (box.width() * 1.25f).toInt()))
        val height = min(source.height, max(box.height(), (box.height() * 1.55f).toInt()))
        val centerX = box.centerX()
        // Shift slightly upward to center the face and exclude shoulders.
        val centerY = (box.centerY() - box.height() * 0.08f).toInt()
        val left = (centerX - width / 2).coerceIn(0, source.width - width)
        val top = (centerY - height / 2).coerceIn(0, source.height - height)
        val crop = Bitmap.createBitmap(source, left, top, width, height)

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
        val correctionDegrees = if (leftEye != null && rightEye != null) {
            val deltaX = rightEye.position.x - leftEye.position.x
            val deltaY = rightEye.position.y - leftEye.position.y
            Math.toDegrees(kotlin.math.atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
        } else {
            face.headEulerAngleZ
        }

        if (abs(correctionDegrees) < 0.5f) return crop
        val matrix = Matrix().apply {
            postRotate(-correctionDegrees, crop.width / 2f, crop.height / 2f)
        }
        return Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, matrix, true)
    }

    private fun meanLuminance(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        for (pixel in pixels) {
            sum += 0.299 * ((pixel shr 16) and 0xFF) +
                0.587 * ((pixel shr 8) and 0xFF) +
                0.114 * (pixel and 0xFF)
        }
        return (sum / pixels.size.coerceAtLeast(1)).toFloat()
    }

    /** A small, deterministic blur metric based on the variance of the Laplacian. */
    private fun laplacianVariance(source: Bitmap): Float {
        val width = min(source.width, SHARPNESS_SAMPLE_SIZE)
        val height = min(source.height, SHARPNESS_SAMPLE_SIZE)
        if (width < 3 || height < 3) return 0f
        val bitmap = if (source.width == width && source.height == height) source else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val values = ArrayList<Float>((width - 2) * (height - 2))
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val center = luminance(pixels[index])
                val laplacian = 4f * center -
                    luminance(pixels[index - 1]) - luminance(pixels[index + 1]) -
                    luminance(pixels[index - width]) - luminance(pixels[index + width])
                values += laplacian
            }
        }
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size
    }

    private fun luminance(pixel: Int): Float =
        0.299f * ((pixel shr 16) and 0xFF) +
            0.587f * ((pixel shr 8) and 0xFF) +
            0.114f * (pixel and 0xFF)

    private fun Rect.clampTo(width: Int, height: Int): Rect {
        val left = left.coerceIn(0, width - 1)
        val top = top.coerceIn(0, height - 1)
        val right = right.coerceIn(left + 1, width)
        val bottom = bottom.coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun Bitmap.asSoftwareBitmap(): Bitmap =
        if (config == Bitmap.Config.HARDWARE || config == null) copy(Bitmap.Config.ARGB_8888, true) else this

    private fun emptyQuality() = FaceQuality(0f, 0, 0, 0f, 0f, 0f, 0f, 0f, null, null)

    fun release() {
        detector.close()
        captureFallbackDetector.close()
        candidateDetector.close()
        recoveryDetector.close()
    }

    private companion object {
        const val MIN_IMAGE_WIDTH = 128
        const val MIN_IMAGE_HEIGHT = 128
        const val MIN_FACE_PIXELS = 16
        const val MIN_FACE_COVERAGE = 0.001f
        const val MIN_CAPTURE_FALLBACK_IMAGE_EDGE = 160
        const val MIN_CAPTURE_FALLBACK_FACE_PIXELS = 64
        const val MIN_CAPTURE_FALLBACK_FACE_COVERAGE = 0.03f
        const val MIN_CANDIDATE_IMAGE_EDGE = 32
        const val MIN_CANDIDATE_FACE_PIXELS = 8
        const val MIN_CANDIDATE_FACE_COVERAGE = 0.002f
        const val MIN_DOMINANT_FACE_PIXELS = 64
        const val MIN_DOMINANT_FACE_COVERAGE = 0.03f
        const val DOMINANT_FACE_AREA_RATIO = 1.5
        const val MAX_YAW_DEGREES = 45f
        const val MAX_PITCH_DEGREES = 35f
        const val MAX_ROLL_DEGREES = 30f
        const val MIN_LUMINANCE = 30f
        const val MAX_LUMINANCE = 240f
        const val MIN_SHARPNESS = 12f
        const val MIN_CAPTURE_FALLBACK_LUMINANCE = 20f
        const val MAX_CAPTURE_FALLBACK_LUMINANCE = 245f
        const val MIN_CAPTURE_FALLBACK_SHARPNESS = 8f
        const val MIN_EYE_OPEN_PROBABILITY = 0.20f
        const val SHARPNESS_SAMPLE_SIZE = 128
    }
}
