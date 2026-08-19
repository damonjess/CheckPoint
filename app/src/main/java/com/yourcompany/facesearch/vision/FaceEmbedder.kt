package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer

/**
 * Wraps the bundled MobileFaceNet model using the modern LiteRT CompiledModel API.
 */
class FaceEmbedder(private val context: Context) {

    companion object {
        private const val MODEL_FILE = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
    }

    private val compiledModel: CompiledModel by lazy {
        // CompiledModel.create automatically handles hardware acceleration (GPU/NPU)
        // and uses the LiteRT runtime from Google Play Services if initialized.
        CompiledModel.create(context.assets, MODEL_FILE)
    }

    // Reuse buffers to avoid allocations during inference
    private val inputBuffers: List<TensorBuffer> by lazy { compiledModel.createInputBuffers(0) }
    private val outputBuffers: List<TensorBuffer> by lazy { compiledModel.createOutputBuffers(0) }

    fun getEmbedding(faceBitmap: Bitmap): FloatArray? {
        if (!isGoodQuality(faceBitmap)) return null

        try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val floatArray = bitmapToFloatArray(resized)
            
            // Load data into the pre-allocated native input buffer
            inputBuffers[0].writeFloat(floatArray)

            // Run inference
            compiledModel.run(inputBuffers, outputBuffers, 0)

            // Read results from the output buffer
            val rawOutput = outputBuffers[0].readFloat()
            
            return l2Normalize(rawOutput)
        } catch (e: Exception) {
            android.util.Log.e("FaceEmbedder", "Inference failed: ${e.message}")
            return null
        }
    }

    private fun isGoodQuality(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var brightnessSum = 0f
        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)
            brightnessSum += (0.299f * r + 0.587f * g + 0.114f * b)
        }
        val avgBrightness = brightnessSum / pixels.size
        return avgBrightness in 25f..240f
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val floatArray = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var index = 0
        for (pixel in pixels) {
            floatArray[index++] = ((pixel shr 16 and 0xFF) - 127.5f) / 128f
            floatArray[index++] = ((pixel shr 8 and 0xFF) - 127.5f) / 128f
            floatArray[index++] = ((pixel and 0xFF) - 127.5f) / 128f
        }
        return floatArray
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var normSq = 0f
        for (v in vector) normSq += v * v
        val norm = kotlin.math.sqrt(normSq).coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    fun close() {
        compiledModel.close()
    }
}



