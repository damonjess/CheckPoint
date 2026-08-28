package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Orchestrates image preparation for search probes.
 * Preserves the original image and creates high-quality derivatives
 * optimized for visual matching providers.
 */
class SearchProbeManager(private val context: Context) {

    data class ProbeResult(
        val original: Bitmap,
        val searchDerivative: Bitmap,
        val mimeType: String = "image/jpeg"
    )

    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Failure(val reason: String) : ValidationResult()
    }

    /**
     * Prepares search probes from a raw captured or selected Bitmap.
     * Applies EXIF rotation and generates a high-quality derivative.
     */
    fun prepareProbes(source: Bitmap, uri: Uri? = null): ProbeResult {
        // 1. Ensure we are working with a software bitmap
        val softwareBitmap = source.asSoftwareBitmap()

        // 2. Apply EXIF rotation if a URI is provided (gallery pick)
        val rotated = if (uri != null) {
            rotateImageIfRequired(softwareBitmap, uri)
        } else {
            softwareBitmap
        }

        // 3. Create a high-quality search derivative
        // Target at least 1024 px on the long edge
        val searchDerivative = createSearchDerivative(rotated, targetLongEdge = 1024)

        return ProbeResult(
            original = rotated,
            searchDerivative = searchDerivative
        )
    }

    /**
     * Validates an image before processing.
     * Rejects zero-dimension, tiny images, and unreadable types.
     */
    fun validateImage(bitmap: Bitmap): ValidationResult {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return ValidationResult.Failure("Invalid image dimensions (zero).")
        }
        
        if (bitmap.width < 32 || bitmap.height < 32) {
            return ValidationResult.Failure("Image is too small for visual matching.")
        }

        // Additional MIME check could be done if we had the raw bytes, 
        // but for a Bitmap, we assume it's already decoded correctly.
        return ValidationResult.Success
    }

    private fun createSearchDerivative(source: Bitmap, targetLongEdge: Int): Bitmap {
        val width = source.width
        val height = source.height
        val longestEdge = maxOf(width, height)

        if (longestEdge <= targetLongEdge) return source

        val scale = targetLongEdge.toFloat() / longestEdge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun rotateImageIfRequired(img: Bitmap, selectedImage: Uri): Bitmap {
        val input = context.contentResolver.openInputStream(selectedImage) ?: return img
        val ei = try {
            ExifInterface(input)
        } catch (e: Exception) {
            return img
        } finally {
            input.close()
        }

        val orientation = ei.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270)
            else -> img
        }
    }

    private fun rotateImage(img: Bitmap, degree: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        val rotatedImg = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
        if (rotatedImg != img) {
            img.recycle()
        }
        return rotatedImg
    }

    private fun Bitmap.asSoftwareBitmap(): Bitmap =
        if (config == Bitmap.Config.HARDWARE || config == null) {
            copy(Bitmap.Config.ARGB_8888, true)
        } else {
            this
        }
}
