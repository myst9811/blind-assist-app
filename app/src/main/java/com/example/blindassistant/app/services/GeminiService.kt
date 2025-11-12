// app/src/main/java/com/blindassistant/app/services/GeminiService.kt

package com.example.blindassistant.app.services

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GeminiService(private val context: Context) {

    private lateinit var model: GenerativeModel
    private var isInitialized = false

    companion object {
        private const val TAG = "GeminiService"
        private const val MODEL_NAME = "gemini-2.0-flash-exp"
    }

    fun initialize(apiKey: String) {
        model = GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = apiKey
        )
        isInitialized = true
        Log.d(TAG, "✅ Gemini initialized")
    }

    suspend fun extractText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext "Gemini not initialized"

        try {
            val prompt = """
                Extract ALL text from this image.
                Include every word, number, and symbol you see.
                Maintain the original layout and structure.
                If you see no text, respond with "NO_TEXT_FOUND".
            """.trimIndent()

            val inputContent = content {
                image(bitmap)
                text(prompt)
            }

            val response = model.generateContent(inputContent)
            response.text ?: "No text detected"

        } catch (e: Exception) {
            Log.e(TAG, "OCR error", e)
            "Failed to read text"
        }
    }

    suspend fun scanQRCode(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext "Gemini not initialized"

        try {
            val prompt = """
                Scan this image for QR codes or barcodes.
                Decode and return the content.
                If no codes found, respond with "NO_CODE_FOUND".
            """.trimIndent()

            val inputContent = content {
                image(bitmap)
                text(prompt)
            }

            val response = model.generateContent(inputContent)
            response.text ?: "No code detected"

        } catch (e: Exception) {
            Log.e(TAG, "QR scan error", e)
            "Failed to scan code"
        }
    }

    suspend fun describeScene(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext "Gemini not initialized"

        try {
            val prompt = """
                Describe this scene for a blind person.
                Focus on:
                - Spatial layout and safety (obstacles, hazards, pathways)
                - Objects from nearest to farthest
                - Any people and their approximate positions
                - Potential threats (stairs, edges, moving objects)
                Keep it concise but informative (2-3 sentences max).
                Use clock positions for directions (12 o'clock = straight ahead).
            """.trimIndent()

            val inputContent = content {
                image(bitmap)
                text(prompt)
            }

            val response = model.generateContent(inputContent)
            response.text ?: "Could not analyze scene"

        } catch (e: Exception) {
            Log.e(TAG, "Scene description error", e)
            "Failed to describe scene"
        }
    }
}