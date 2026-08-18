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
class FaceDetectorHelper(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.10f)
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

    suspend fun detectAndCropFace(
        sourceBitmap: Bitmap,
        allowCaptureFallback: Boolean = false
    ): FaceDetectionResult {
        if (sourceBitmap.width < MIN_IMAGE_WIDTH || sourceBitmap.height < MIN_IMAGE_HEIGHT) {
            return FaceDetectionResult.PoorQuality(
                "Use a photo at least ${MIN_IMAGE_WIDTH}×${MIN_IMAGE_HEIGHT} pixels.",
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
            val faces = if (primaryFaces.isNotEmpty()) primaryFaces else fallbackFaces
            val usedFallbackDetector = primaryFaces.isEmpty() && fallbackFaces.isNotEmpty()

            when {
                faces.isEmpty() -> FaceDetectionResult.NoFaceFound
                faces.size > 1 -> FaceDetectionResult.MultipleFacesFound(faces.size)
                else -> {
                    val face = faces.single()
                    val quality = assessQuality(bitmap, face)
                    val strictRejection = qualityRejectionReason(quality)
                    val rejection = if (allowCaptureFallback && (usedFallbackDetector || strictRejection != null)) {
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
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            if (faces.size != 1) return false

            val box = faces.single().boundingBox.clampTo(bitmap.width, bitmap.height)
            val coverage = (box.width().toFloat() * box.height().toFloat()) /
                (bitmap.width.toFloat() * bitmap.height.toFloat())
            box.width() >= MIN_CANDIDATE_FACE_PIXELS &&
                box.height() >= MIN_CANDIDATE_FACE_PIXELS &&
                coverage >= MIN_CANDIDATE_FACE_COVERAGE
        } catch (_: Exception) {
            false
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
        val width = min(source.width, max(box.width(), (box.width() * 1.55f).toInt()))
        val height = min(source.height, max(box.height(), (box.height() * 1.85f).toInt()))
        val centerX = box.centerX()
        // Shift slightly upward to retain forehead and the full jawline.
        val centerY = (box.centerY() - box.height() * 0.04f).toInt()
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
    }

    private companion object {
        const val MIN_IMAGE_WIDTH = 480
        const val MIN_IMAGE_HEIGHT = 360
        const val MIN_FACE_PIXELS = 80
        const val MIN_FACE_COVERAGE = 0.05f
        const val MIN_CAPTURE_FALLBACK_FACE_PIXELS = 64
        const val MIN_CAPTURE_FALLBACK_FACE_COVERAGE = 0.03f
        const val MIN_CANDIDATE_IMAGE_EDGE = 96
        const val MIN_CANDIDATE_FACE_PIXELS = 32
        const val MIN_CANDIDATE_FACE_COVERAGE = 0.02f
        const val MAX_YAW_DEGREES = 32f
        const val MAX_PITCH_DEGREES = 25f
        const val MAX_ROLL_DEGREES = 22f
        const val MIN_LUMINANCE = 40f
        const val MAX_LUMINANCE = 230f
        const val MIN_SHARPNESS = 25f
        const val MIN_CAPTURE_FALLBACK_LUMINANCE = 25f
        const val MAX_CAPTURE_FALLBACK_LUMINANCE = 240f
        const val MIN_CAPTURE_FALLBACK_SHARPNESS = 10f
        const val MIN_EYE_OPEN_PROBABILITY = 0.30f
        const val SHARPNESS_SAMPLE_SIZE = 128
    }
}
