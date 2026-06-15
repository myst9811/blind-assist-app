package com.example.blindassistant.app.services

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MLKitTextService {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                continuation.resume(if (text.isBlank()) "NO_TEXT_FOUND" else text)
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
    }

    fun close() {
        recognizer.close()
    }
}
