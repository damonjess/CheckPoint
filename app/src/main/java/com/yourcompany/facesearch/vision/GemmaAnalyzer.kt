package com.yourcompany.facesearch.vision

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GemmaAnalyzer(private val context: Context) {

    companion object {
        private const val MODEL_NAME = "gemma.task"
    }

    private var llmInference: LlmInference? = null
    private var initializationError: String? = null
    
    private fun setupInference() {
        if (llmInference != null || initializationError != null) return
        
        try {
            val modelFile = java.io.File(context.filesDir, MODEL_NAME)
            
            // 0. Check for manual push location to avoid asset deployment issues
            val externalFile = java.io.File("/sdcard/Download/gemma.task")
            val targetFile = if (externalFile.exists() && externalFile.length() > 500000000) {
                android.util.Log.d("GemmaAnalyzer", "Found manually pushed model at ${externalFile.absolutePath}")
                externalFile
            } else {
                modelFile
            }

            // 1. Robust model acquisition (Copy from assets if not manually pushed)
            if (targetFile == modelFile && (!modelFile.exists() || modelFile.length() < 500000000)) {
                try {
                    context.assets.open(MODEL_NAME).use { input ->
                        val size = input.available().toLong()
                        if (size < 500000000) {
                            initializationError = "Model file in assets is too small ($size bytes). Likely a Git LFS pointer. Run 'git lfs pull'."
                            return
                        }
                        
                        java.io.FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: java.io.FileNotFoundException) {
                    if (!targetFile.exists()) {
                        initializationError = "Model file $MODEL_NAME not found in assets or /sdcard/Download/."
                        return
                    }
                }
            }

            // 2. Final safety check before native initialization
            if (!targetFile.exists() || targetFile.length() < 500000000) {
                initializationError = "Model file invalid or too small (Size: ${targetFile.length()} bytes)."
                return
            }

            android.util.Log.d("GemmaAnalyzer", "Initializing Gemma with model: ${targetFile.absolutePath} (${targetFile.length()} bytes)")

            // 3. Native initialization with safety
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(targetFile.absolutePath)
                .setMaxTokens(512)
                .setTemperature(0.7f)
                .setRandomSeed(42)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            initializationError = "Gemma Init Error: ${e.message}"
            android.util.Log.e("GemmaAnalyzer", "Native crash prevented: ${e.message}")
        } catch (e: Error) {
            // Catching java.lang.Error to prevent native linkage/memory errors from killing the app
            initializationError = "Gemma Critical Error: ${e.message}"
            android.util.Log.e("GemmaAnalyzer", "Critical failure in MediaPipe: ${e.message}")
        }
    }

    suspend fun analyzeSearchLeads(targetHint: String?, leads: List<String>): String = withContext(Dispatchers.IO) {
        setupInference()
        val inference = llmInference ?: return@withContext "Gemma analysis unavailable: ${initializationError ?: "Initialization failed"}."

        val prompt = StringBuilder()
        prompt.append("You are an OSINT expert. Analyze these search results for a person matching the hint: '$targetHint'.\n")
        prompt.append("Leads found:\n")
        leads.take(5).forEachIndexed { index, lead ->
            prompt.append("${index + 1}. $lead\n")
        }
        prompt.append("\nSummarize the most likely identity and social presence in 3 sentences. Be concise.")

        try {
            inference.generateResponse(prompt.toString())
        } catch (e: Exception) {
            "Analysis failed: ${e.message}"
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}
