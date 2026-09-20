package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.sqrt

/**
 * Wraps the bundled MobileFaceNet model using the modern LiteRT CompiledModel API.
 * Includes an In-Memory LRU Cache to eliminate redundant model inference passes.
 */
class FaceEmbedder(private val context: Context) {

    companion object {
        private const val MODEL_FILE = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
        private const val CACHE_SIZE = 100
        private const val TAG = "FaceEmbedder"
    }

    // Cache up to 100 computed face embeddings in memory (< 1 ms lookup)
    private val embeddingCache = LruCache<String, FloatArray>(CACHE_SIZE)

    private val compiledModel: CompiledModel by lazy {
        // CompiledModel automatically utilizes hardware acceleration (GPU/NPU)
        // using the LiteRT runtime.
        CompiledModel.create(context.assets, MODEL_FILE)
    }

    // Reuse buffers to avoid allocations during inference
    private val inputBuffers: List<TensorBuffer> by lazy { compiledModel.createInputBuffers(0) }
    private val outputBuffers: List<TensorBuffer> by lazy { compiledModel.createOutputBuffers(0) }

    /**
     * Retrieves a cached embedding or runs TFLite/LiteRT model inference if missing,
     * saving heavy CPU/GPU processing cycles on repeat scans.
     */
    fun getEmbedding(imageKey: String, faceBitmap: Bitmap): FloatArray? {
        embeddingCache.get(imageKey)?.let { cachedVector ->
            return cachedVector
        }

        val computedVector = executeModelInference(faceBitmap) ?: return null
        embeddingCache.put(imageKey, computedVector)
        return computedVector
    }

    /**
     * Retrieves a cached embedding using an automatically generated key or computes a new one.
     */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray? {
        val autoKey = generateBitmapKey(faceBitmap)
        return getEmbedding(autoKey, faceBitmap)
    }

    /**
     * Internal model execution pass.
     */
    private fun executeModelInference(faceBitmap: Bitmap): FloatArray? {
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
            Log.e(TAG, "Inference failed: ${e.message}")
            return null
        }
    }

    private fun generateBitmapKey(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        val p1 = if (w > 0 && h > 0) bitmap.getPixel(0, 0) else 0
        val p2 = if (w > 0 && h > 0) bitmap.getPixel(w / 2, h / 2) else 0
        val p3 = if (w > 0 && h > 0) bitmap.getPixel(w - 1, h - 1) else 0
        return "${w}x${h}_${p1}_${p2}_${p3}"
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
        val norm = sqrt(normSq).coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /**
     * Clean up native resources and clear memory cache when destroyed.
     */
    fun close() {
        embeddingCache.evictAll()
        try {
            compiledModel.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model: ${e.message}")
        }
    }
}
