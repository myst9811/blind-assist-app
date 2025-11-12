// app/src/main/java/com/blindassistant/app/services/AudioManager.kt

package com.example.blindassistant.app.services
import android.os.Bundle
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.PriorityQueue

//import java.util.concurrent.PriorityQueue

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
                priority != other.priority -> other.priority - priority  // Higher priority first
                else -> (timestamp - other.timestamp).toInt()  // Earlier first
            }
        }
    }

    private val queue = PriorityQueue<AudioQueueItem>()
    private val lastSpoken = mutableMapOf<String, Long>()
    private val deduplicationWindow = 3000L  // 3 seconds

    companion object {
        private const val TAG = "AudioManager"
    }

    fun initialize(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.apply {
                    language = Locale.US
                    setSpeechRate(0.9f)  // Slightly slower for clarity
                    setPitch(1.0f)
                }

                setUtteranceListener()
                isInitialized = true
                Log.d(TAG, "✅ TextToSpeech initialized")
                onReady()
            } else {
                Log.e(TAG, "❌ TextToSpeech initialization failed")
            }
        }
    }

    private fun setUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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

    fun speak(text: String, priority: Int = Constants.PRIORITY_MEDIUM) {
        if (!isInitialized || text.isBlank()) return

        // Deduplicate similar messages
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

        // Stop everything and speak immediately
        tts?.stop()
        queue.clear()
        _isSpeaking.value = false

        speak(text, Constants.PRIORITY_CRITICAL)
    }

    private fun processQueue() {
        if (_isSpeaking.value || queue.isEmpty()) return

        val item = queue.poll()
        item?.let {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, it.timestamp.toString())
            }

            tts?.speak(it.text, TextToSpeech.QUEUE_FLUSH, params, it.timestamp.toString())
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