package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Wraps the bundled MobileFaceNet TFLite model.
 *
 * Input:  a 112x112 RGB face crop (already tightly cropped around the face)
 * Output: a 192-dimensional embedding vector. Two embeddings from the same
 * person's face end up close together (high cosine similarity); different
 * people end up far apart. Confirmed via the model's own metadata:
 * input [1,112,112,3] float32, output [1,192] float32.
 */
class FaceEmbedder(context: Context) {

    companion object {
        private const val MODEL_FILE = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
    }

    private val interpreter: Interpreter = Interpreter(loadModelFile(context))

    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = bitmapToByteBuffer(resized)
        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }

        interpreter.run(inputBuffer, output)

        return l2Normalize(output[0])
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            // Normalize to [-1, 1], matching how MobileFaceNet was trained.
            buffer.putFloat(((pixel shr 16 and 0xFF) - 127.5f) / 128f) // R
            buffer.putFloat(((pixel shr 8 and 0xFF) - 127.5f) / 128f)  // G
            buffer.putFloat(((pixel and 0xFF) - 127.5f) / 128f)        // B
        }

        buffer.rewind()
        return buffer
    }

    /** Cosine similarity is more stable when both vectors are unit length. */
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var normSq = 0f
        for (v in vector) normSq += v * v
        val norm = kotlin.math.sqrt(normSq).coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun loadModelFile(context: Context): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun close() {
        interpreter.close()
    }
}