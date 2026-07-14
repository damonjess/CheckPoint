package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterFactory
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.channels.FileChannel

class FacePreprocessor(context: Context) {
    private var interpreter: InterpreterApi

    init {
        val fileDescriptor = context.assets.openFd("yolov8n_float32.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        
        val options = InterpreterApi.Options()
            .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
        interpreter = InterpreterFactory().create(modelBuffer, options)
    }

    fun cropFace(originalBitmap: Bitmap, confidenceThreshold: Float = 0.45f): Bitmap? {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()

        val tensorImage = TensorImage.fromBitmap(originalBitmap)
        val processedImage = imageProcessor.process(tensorImage)

        val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } } 

        interpreter.run(processedImage.buffer, outputBuffer)

        val bestBox = parseBestFaceBoundingBox(outputBuffer, originalBitmap.width, originalBitmap.height, confidenceThreshold)
            ?: return null

        return Bitmap.createBitmap(
            originalBitmap,
            bestBox.left.toInt().coerceAtLeast(0),
            bestBox.top.toInt().coerceAtLeast(0),
            bestBox.width().toInt().coerceAtMost(originalBitmap.width - bestBox.left.toInt().coerceAtLeast(0)),
            bestBox.height().toInt().coerceAtMost(originalBitmap.height - bestBox.top.toInt().coerceAtLeast(0))
        )
    }

    private fun parseBestFaceBoundingBox(
        output: Array<Array<FloatArray>>, 
        imgWidth: Int, 
        imgHeight: Int,
        confidenceThreshold: Float
    ): RectF? {
        var maxConfidence = 0.0f
        var bestBox: RectF? = null

        // Iterate through all 8400 candidate detections
        for (i in 0 until 8400) {
            // Index 4 is the confidence score for "person" in COCO models
            val confidence = output[0][4][i] 
            
            if (confidence > maxConfidence && confidence > confidenceThreshold) {
                maxConfidence = confidence
                
                // Convert normalized YOLO center coordinates to absolute pixel coordinates
                val cx = output[0][0][i] * imgWidth
                val cy = output[0][1][i] * imgHeight
                val w = output[0][2][i] * imgWidth
                val h = output[0][3][i] * imgHeight

                bestBox = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
            }
        }
        return bestBox
    }

    fun close() {
        interpreter.close()
    }
}
