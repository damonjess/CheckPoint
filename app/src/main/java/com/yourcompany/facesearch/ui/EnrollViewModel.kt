package com.yourcompany.facesearch.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.data.EnrolledFaceStore
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import com.yourcompany.facesearch.vision.FaceEmbedder
import kotlinx.coroutines.launch

class EnrollViewModel(application: Application) : AndroidViewModel(application) {

    private val faceDetectorHelper = FaceDetectorHelper(application)
    private val faceEmbedder = FaceEmbedder(application)

    var pendingBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun onPhotoCaptured(bitmap: Bitmap) {
        pendingBitmap = bitmap
        errorMessage = null
    }

    fun discardPending() {
        pendingBitmap = null
        errorMessage = null
    }

    fun saveEnrollment(context: Context, name: String, onSaved: () -> Unit) {
        val bitmap = pendingBitmap ?: return
        if (name.isBlank()) {
            errorMessage = "Enter a name first."
            return
        }

        viewModelScope.launch {
            isSaving = true
            errorMessage = null

            when (val detection = faceDetectorHelper.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.Success -> {
                    val embedding = faceEmbedder.getEmbedding(detection.croppedFace)
                    EnrolledFaceStore.addFace(context, name.trim(), embedding)
                    pendingBitmap = null
                    onSaved()
                }
                is FaceDetectionResult.NoFaceFound -> {
                    errorMessage = "No face detected in that photo. Try again with better lighting."
                }
                is FaceDetectionResult.Error -> {
                    errorMessage = "Something went wrong. Try again."
                }
            }

            isSaving = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        faceDetectorHelper.release()
        faceEmbedder.close()
    }
}