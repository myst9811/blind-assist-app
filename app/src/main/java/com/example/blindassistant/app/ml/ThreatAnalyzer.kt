// app/src/main/java/com/blindassistant/app/ml/ThreatAnalyzer.kt

package com.example.blindassistant.app.ml

import com.example.blindassistant.app.models.Detection
import com.example.blindassistant.app.models.ThreatLevel
import com.example.blindassistant.app.services.AudioManager
import com.example.blindassistant.app.services.BLEManager
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ThreatAnalyzer(
    private val audioManager: AudioManager,
    private val bleManager: BLEManager
) {

    private val lastAlertTime = mutableMapOf<String, Long>()
    private val alertCooldown = 3000L  // 3 seconds

    companion object {
        private const val TAG = "ThreatAnalyzer"
    }

    fun analyzeThreat(detection: Detection) {
        val threatLevel = classifyThreat(detection)
        if (threatLevel == ThreatLevel.NONE) return

        // Deduplicate alerts
        val key = "${detection.className}_$threatLevel"
        val lastTime = lastAlertTime[key] ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastTime < alertCooldown) return
        lastAlertTime[key] = now

        // Calculate distance and direction
        val distance = estimateDistance(detection)
        val direction = calculateDirection(detection)

        // Trigger haptic feedback
        CoroutineScope(Dispatchers.Main).launch {
            triggerHapticFeedback(threatLevel, direction)
        }

        // Trigger audio feedback
        CoroutineScope(Dispatchers.Main).launch {
            triggerAudioFeedback(detection, threatLevel, distance, direction)
        }
    }

    private fun classifyThreat(detection: Detection): ThreatLevel {
        val distance = estimateDistance(detection)

        return when {
            detection.className in Constants.CRITICAL_THREATS -> ThreatLevel.CRITICAL
            detection.className in Constants.HIGH_THREATS -> {
                if (distance < 2.0f) ThreatLevel.HIGH else ThreatLevel.MEDIUM
            }
            detection.className in Constants.MEDIUM_THREATS -> {
                if (distance < 1.0f) ThreatLevel.MEDIUM else ThreatLevel.LOW
            }
            else -> ThreatLevel.LOW
        }
    }

    private fun estimateDistance(detection: Detection): Float {
        // Simple heuristic: larger bbox = closer
        val area = detection.bbox.width * detection.bbox.height

        return when {
            area > 0.3f -> 0.5f   // Very close
            area > 0.15f -> 1.5f  // Close
            area > 0.05f -> 3.0f  // Medium
            else -> 5.0f          // Far
        }
    }

    private fun calculateDirection(detection: Detection): Byte {
        val centerX = detection.bbox.x + detection.bbox.width / 2

        return when {
            centerX < 0.33f -> 1  // Left
            centerX > 0.66f -> 2  // Right
            else -> 0             // Front
        }
    }

    private fun triggerHapticFeedback(level: ThreatLevel, direction: Byte) {
        val command = when (level) {
            ThreatLevel.CRITICAL -> Constants.HAPTIC_CRITICAL
            ThreatLevel.HIGH -> Constants.HAPTIC_HIGH
            ThreatLevel.MEDIUM -> Constants.HAPTIC_MEDIUM
            ThreatLevel.LOW -> Constants.HAPTIC_LOW
            ThreatLevel.NONE -> return
        }

        bleManager.sendHapticCommand(command, direction)
    }

    private fun triggerAudioFeedback(
        detection: Detection,
        level: ThreatLevel,
        distance: Float,
        direction: Byte
    ) {
        val directionText = when (direction.toInt()) {
            1 -> "on your left"
            2 -> "on your right"
            else -> "ahead"
        }

        val message = when (level) {
            ThreatLevel.CRITICAL -> {
                "Warning! ${detection.className} $directionText, ${distance.toInt()} meters"
            }
            ThreatLevel.HIGH -> {
                "Caution, ${detection.className} $directionText, ${distance.toInt()} meters"
            }
            ThreatLevel.MEDIUM -> {
                "${detection.className} detected $directionText"
            }
            ThreatLevel.LOW -> {
                "Obstacle $directionText"
            }
            ThreatLevel.NONE -> return
        }

        val priority = when (level) {
            ThreatLevel.CRITICAL -> Constants.PRIORITY_CRITICAL
            ThreatLevel.HIGH -> Constants.PRIORITY_HIGH
            ThreatLevel.MEDIUM -> Constants.PRIORITY_MEDIUM
            else -> Constants.PRIORITY_LOW
        }

        audioManager.speak(message, priority)
    }
}