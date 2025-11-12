// app/src/main/java/com/example/blindassistant/app/services/VisionRouter.kt

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

    private var currentMode = Constants.DetectionMode.NORMAL
    private var lastQRPrompt = 0L
    private var lastTextPrompt = 0L

    companion object {
        private const val TAG = "VisionRouter"
    }

    suspend fun analyzeForQRCode(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val quickCheck = geminiService.quickCheckForQR(bitmap)

            if (quickCheck && canPromptQR()) {
                Log.d(TAG, "📱 QR code detected!")
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
                Log.d(TAG, "📄 Text detected!")
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

        val text = geminiService.extractText(bitmap)

        if (text != "NO_TEXT_FOUND") {
            audioManager.speak(text, Constants.PRIORITY_HIGH)
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