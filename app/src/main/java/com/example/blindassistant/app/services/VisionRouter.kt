package com.example.blindassistant.app.services

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VisionRouter(
    private val context: Context,
    private val geminiService: GeminiService,
    private val audioManager: AudioManager
) {

    private val mlKitTextService = MLKitTextService()
    private var currentMode = Constants.DetectionMode.NORMAL
    private var lastQRPrompt = 0L
    private var lastTextPrompt = 0L

    companion object {
        private const val TAG = "VisionRouter"
        private val GEMINI_FAILURE_STRINGS = setOf(
            "Failed to read text",
            "Gemini not initialized",
            "NO_TEXT_FOUND"
        )
    }

    suspend fun analyzeForQRCode(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val quickCheck = geminiService.quickCheckForQR(bitmap)
            if (quickCheck && canPromptQR()) {
                Log.d(TAG, "QR code detected")
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "QR detection error", e)
            false
        }
    }

    suspend fun analyzeForText(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val quickCheck = geminiService.quickCheckForText(bitmap)
            if (quickCheck && canPromptText()) {
                Log.d(TAG, "Text detected")
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Text detection error", e)
            false
        }
    }

    suspend fun scanQRCode(bitmap: Bitmap) {
        audioManager.speak("Scanning QR code", Constants.PRIORITY_HIGH)
        val result = geminiService.scanQRCode(bitmap)
        if (result != "NO_CODE_FOUND") {
            audioManager.speak(result, Constants.PRIORITY_HIGH)
        } else {
            audioManager.speak("No QR code found", Constants.PRIORITY_MEDIUM)
        }
    }

    suspend fun readText(bitmap: Bitmap) {
        audioManager.speak("Reading text", Constants.PRIORITY_HIGH)

        val geminiResult = try {
            geminiService.extractText(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Gemini OCR failed, falling back to ML Kit", e)
            null
        }

        if (geminiResult != null && geminiResult !in GEMINI_FAILURE_STRINGS) {
            audioManager.speak(geminiResult, Constants.PRIORITY_HIGH)
            return
        }

        Log.d(TAG, "Gemini unavailable, using ML Kit OCR")
        val mlKitResult = try {
            mlKitTextService.extractText(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR also failed", e)
            "NO_TEXT_FOUND"
        }

        if (mlKitResult != "NO_TEXT_FOUND") {
            audioManager.speak(mlKitResult, Constants.PRIORITY_HIGH)
        } else {
            audioManager.speak("No text found", Constants.PRIORITY_MEDIUM)
        }
    }

    fun setMode(mode: Constants.DetectionMode) {
        currentMode = mode
        Log.d(TAG, "Mode changed to: $mode")
    }

    fun getMode(): Constants.DetectionMode = currentMode

    private fun canPromptQR(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastQRPrompt > Constants.QR_DETECTION_COOLDOWN) {
            lastQRPrompt = now
            true
        } else false
    }

    private fun canPromptText(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastTextPrompt > Constants.TEXT_DETECTION_COOLDOWN) {
            lastTextPrompt = now
            true
        } else false
    }
}
