package com.yourcompany.facesearch.vision

import android.content.Context
import android.os.Environment
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Analyzes OSINT leads using the Gemma LLM via MediaPipe.
 */
class GemmaAnalyzer(private val context: Context) {

    companion object {
        private const val MODEL_NAME = "gemma.task"
        private const val MIN_MODEL_SIZE = 800_000_000L // Reduced to support GPU/High-compression models
    }

    private var llmInference: LlmInference? = null
    
    private val _initializationError = MutableStateFlow<String?>(null)
    val initializationError = _initializationError.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    /**
     * Attempts to find and initialize the Gemma model.
     * Looks in: Internal files, External files, and Downloads.
     */
    fun setupInference() {
        if (llmInference != null) {
            _isReady.value = true
            return
        }
        
        _initializationError.value = null
        
        try {
            // 1. Locate the model
            val targetFile = findModelFile()

            if (targetFile == null) {
                _initializationError.value = "Model file $MODEL_NAME not found. Place it in /sdcard/Download/ or app data."
                return
            }

            if (targetFile.length() < MIN_MODEL_SIZE) {
                _initializationError.value = "Model file too small. Full model required."
                return
            }

            android.util.Log.d("GemmaAnalyzer", "Initializing Gemma from: ${targetFile.absolutePath} (${targetFile.length()} bytes)")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(targetFile.absolutePath)
                .setMaxTokens(512)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            _isReady.value = true
            _initializationError.value = null
            android.util.Log.d("GemmaAnalyzer", "Gemma successfully initialized.")
            
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Unknown error"
            _initializationError.value = "Gemma Init Error: $errorMsg"
            android.util.Log.e("GemmaAnalyzer", "Init Error", e)
        }
    }

    private fun findModelFile(): File? {
        val internal = File(context.filesDir, MODEL_NAME)
        val external = File(context.getExternalFilesDir(null), MODEL_NAME)
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val download = File(downloadDir, MODEL_NAME)

        return when {
            internal.exists() && internal.length() >= MIN_MODEL_SIZE -> internal
            external.exists() && external.length() >= MIN_MODEL_SIZE -> external
            download.exists() && download.length() >= MIN_MODEL_SIZE -> download
            else -> null
        }
    }

    suspend fun analyzeSearchLeads(targetHint: String?, leads: List<String>): String = withContext(Dispatchers.IO) {
        setupInference()
        val inference = llmInference ?: return@withContext "Gemma analysis unavailable: ${_initializationError.value ?: "Unknown error"}."

        val prompt = buildPrompt(targetHint, leads)

        try {
            inference.generateResponse(prompt)
        } catch (e: Exception) {
            "Analysis failed: ${e.localizedMessage}"
        }
    }

    private fun buildPrompt(targetHint: String?, leads: List<String>): String {
        return buildString {
            append("ACT AS A SENIOR OSINT INVESTIGATOR. Analyze the following target data.\n")
            if (!targetHint.isNullOrBlank()) append("TARGET HINT: '$targetHint'\n")
            append("\nINPUT LEADS:\n")
            leads.take(8).forEachIndexed { i, lead -> append("[${i + 1}] $lead\n") }
            append("\nTASK: Correlate these leads and identify the most probable identity. ")
            append("Provide a 3-sentence professional summary.")
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
        _isReady.value = false
    }
}
