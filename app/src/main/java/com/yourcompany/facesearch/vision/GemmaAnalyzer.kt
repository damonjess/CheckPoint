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
    
    private fun setupInference() {
        if (llmInference != null) return
        
        try {
            // Check if model exists in assets
            val assets = context.assets.list("") ?: emptyArray()
            if (!assets.contains(MODEL_NAME)) {
                android.util.Log.e("GemmaAnalyzer", "Model $MODEL_NAME not found in assets!")
                return
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath("/asset/$MODEL_NAME") // Standard MediaPipe asset path prefix
                .setMaxTokens(512)
                .setTemperature(0.7f)
                .setRandomSeed(42)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            android.util.Log.e("GemmaAnalyzer", "Error setting up inference", e)
        }
    }

    suspend fun analyzeSearchLeads(targetHint: String?, leads: List<String>): String = withContext(Dispatchers.IO) {
        setupInference()
        val inference = llmInference ?: return@withContext "Gemma analysis unavailable (Model not found in assets or initialization failed)."

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
