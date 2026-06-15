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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

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

    private val queue = PriorityBlockingQueue<AudioQueueItem>()
    private val lastSpoken = ConcurrentHashMap<String, Long>()
    private val deduplicationWindow = 3000L

    companion object {
        private const val TAG = "AudioManager"
    }

    fun initialize(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { textToSpeech ->
                    textToSpeech.language = Locale.US

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
                        Log.d(TAG, "Using voice: ${preferredVoice.name}")
                    }

                    textToSpeech.setSpeechRate(0.85f)
                    textToSpeech.setPitch(1.0f)

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
                Log.d(TAG, "TextToSpeech initialized")
                onReady()
            } else {
                Log.e(TAG, "TextToSpeech initialization failed")
            }
        }
    }

    @Synchronized
    fun speak(text: String, priority: Int = Constants.PRIORITY_MEDIUM) {
        if (!isInitialized || text.isBlank()) return

        val key = text.take(50)
        val now = System.currentTimeMillis()
        val lastTime = lastSpoken[key] ?: 0L

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

    @Synchronized
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
