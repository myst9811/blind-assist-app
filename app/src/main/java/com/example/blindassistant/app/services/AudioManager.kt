// app/src/main/java/com/example/blindassistant/app/services/AudioManager.kt

package com.example.blindassistant.app.services

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.PriorityQueue

class AudioManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private data class AudioQueueItem(
        val text: String,
        val priority: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : Comparable<AudioQueueItem> {
        override fun compareTo(other: AudioQueueItem): Int {
            return when {
                priority != other.priority -> other.priority - priority
                else -> (timestamp - other.timestamp).toInt()
            }
        }
    }

    private val queue = PriorityQueue<AudioQueueItem>()
    private val lastSpoken = mutableMapOf<String, Long>()
    private val deduplicationWindow = 3000L

    companion object {
        private const val TAG = "AudioManager"
    }

    fun initialize(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { textToSpeech ->
                    // Set language
                    textToSpeech.language = Locale.US

                    // Try to find the best voice
                    val availableVoices = textToSpeech.voices
                    val preferredVoice = availableVoices?.firstOrNull { voice ->
                        (voice.name.contains("female", ignoreCase = true) ||
                                voice.name.contains("en-us-x-sfg", ignoreCase = true) ||
                                voice.name.contains("samantha", ignoreCase = true)) &&
                                voice.quality == Voice.QUALITY_VERY_HIGH
                    } ?: availableVoices?.firstOrNull { voice ->
                        voice.quality == Voice.QUALITY_VERY_HIGH
                    }

                    if (preferredVoice != null) {
                        textToSpeech.voice = preferredVoice
                        Log.d(TAG, "✅ Using voice: ${preferredVoice.name}")
                    } else {
                        Log.d(TAG, "Using default voice")
                    }

                    // Set speech parameters
                    textToSpeech.setSpeechRate(0.85f)
                    textToSpeech.setPitch(1.0f)

                    // Set utterance listener
                    textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            processQueue()
                        }

                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            processQueue()
                        }
                    })
                }

                isInitialized = true
                Log.d(TAG, "✅ TextToSpeech initialized")
                onReady()
            } else {
                Log.e(TAG, "❌ TextToSpeech initialization failed")
            }
        }
    }

    fun speak(text: String, priority: Int = Constants.PRIORITY_MEDIUM) {
        if (!isInitialized || text.isBlank()) return

        val key = text.take(50)
        val lastTime = lastSpoken[key] ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastTime < deduplicationWindow) {
            Log.d(TAG, "Skipping duplicate: $text")
            return
        }

        lastSpoken[key] = now
        queue.offer(AudioQueueItem(text, priority))

        if (!_isSpeaking.value) {
            processQueue()
        }
    }

    fun speakImmediate(text: String) {
        if (!isInitialized) return

        tts?.stop()
        queue.clear()
        _isSpeaking.value = false

        speak(text, Constants.PRIORITY_CRITICAL)
    }

    private fun processQueue() {
        if (_isSpeaking.value || queue.isEmpty()) return

        val item = queue.poll()
        item?.let {
            val params = Bundle()
            tts?.speak(
                it.text,
                TextToSpeech.QUEUE_FLUSH,
                params,
                it.timestamp.toString()
            )
            Log.d(TAG, "Speaking (priority ${it.priority}): ${it.text}")
        }
    }

    fun stop() {
        tts?.stop()
        queue.clear()
        _isSpeaking.value = false
    }

    fun cleanup() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}