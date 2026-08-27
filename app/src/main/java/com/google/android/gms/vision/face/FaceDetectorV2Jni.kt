package com.google.android.gms.vision.face

import android.content.res.AssetManager
import java.nio.ByteBuffer

/**
 * Reconstructed JNI wrapper for the high-accuracy bundled Face Detector V2
 * found in the older APK. This class allows the app to use the native
 * libface_detector_v2_jni.so library directly.
 */
class FaceDetectorV2Jni {
    
    init {
        System.loadLibrary("face_detector_v2_jni")
    }

    external fun initDetectorJni(
        assetManager: AssetManager,
        modelDir: String,
        options: ByteBuffer
    ): Long

    external fun detectFacesImageByteBufferJni(
        detectorPtr: Long,
        imageBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rotation: Int,
        options: ByteBuffer
    ): ByteArray?

    external fun detectFacesImageByteArrayJni(
        detectorPtr: Long,
        imageBytes: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        options: ByteBuffer
    ): ByteArray?

    external fun closeDetectorJni(detectorPtr: Long)
}
