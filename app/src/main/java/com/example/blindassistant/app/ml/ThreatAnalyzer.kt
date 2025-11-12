// app/src/main/java/com/example/blindassistant/app/ml/ThreatAnalyzer.kt

package com.example.blindassistant.app.ml

import android.util.Log
import com.example.blindassistant.app.models.Detection
import com.example.blindassistant.app.models.ThreatLevel
import com.example.blindassistant.app.services.AudioManager
import com.example.blindassistant.app.services.BLEManager
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class ThreatAnalyzer(
    private val audioManager: AudioManager,
    private val bleManager: BLEManager
) {

    private val lastAlertTime = mutableMapOf<String, Long>()
    private val alertCooldown = 4000L
    private val criticalCooldown = 2000L
    private val collisionCooldown = 1000L  // Very short for collisions

    private var lastAudioTime = 0L
    private val audioMinInterval = 2500L

    private var lastHapticTime = 0L
    private val hapticMinInterval = 1000L

    private var lastCollisionTime = 0L

    companion object {
        private const val TAG = "ThreatAnalyzer"

        private const val CRITICAL_DISTANCE = 2.0f
        private const val HIGH_DISTANCE = 3.5f
        private const val MEDIUM_DISTANCE = 5.0f

        private const val MIN_AREA_THRESHOLD = 0.03f
    }

    fun analyzeThreat(detection: Detection) {
        val area = detection.bbox.width * detection.bbox.height
        if (area < MIN_AREA_THRESHOLD) return

        // PRIORITY 1: Check for imminent collision
        if (isImminentCollision(detection)) {
            handleCollision(detection)
            return  // Handle collision immediately
        }

        // PRIORITY 2: Regular threat analysis
        val threatLevel = classifyThreat(detection)
        if (threatLevel == ThreatLevel.NONE) return

        val cooldown = when (threatLevel) {
            ThreatLevel.CRITICAL -> criticalCooldown
            ThreatLevel.LOW -> alertCooldown * 2
            else -> alertCooldown
        }

        val key = "${detection.className}_$threatLevel"
        val lastTime = lastAlertTime[key] ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastTime < cooldown) return
        lastAlertTime[key] = now

        val distance = estimateDistance(detection)

        val shouldAlert = when (threatLevel) {
            ThreatLevel.CRITICAL -> distance < CRITICAL_DISTANCE
            ThreatLevel.HIGH -> distance < HIGH_DISTANCE
            ThreatLevel.MEDIUM -> distance < MEDIUM_DISTANCE
            ThreatLevel.LOW -> distance < 2.0f
            else -> false
        }

        if (!shouldAlert) return

        val direction = calculateDirection(detection)

        if (now - lastHapticTime > hapticMinInterval) {
            lastHapticTime = now
            CoroutineScope(Dispatchers.Main).launch {
                triggerHapticFeedback(threatLevel, direction)
            }
        }

        if (now - lastAudioTime > audioMinInterval) {
            lastAudioTime = now
            CoroutineScope(Dispatchers.Main).launch {
                triggerAudioFeedback(detection, threatLevel, distance, direction)
            }
        }
    }

    // NEW: Detect if user is walking straight into something
    private fun isImminentCollision(detection: Detection): Boolean {
        val area = detection.bbox.width * detection.bbox.height
        val centerX = detection.bbox.x + detection.bbox.width / 2
        val centerY = detection.bbox.y + detection.bbox.height / 2

        // Check if object is:
        // 1. Large (taking up significant screen space)
        // 2. In CENTER of frame (walking straight at it)
        // 3. In the right class (solid objects)

        val isLarge = area > Constants.COLLISION_AREA_THRESHOLD
        val isCentered = abs(centerX - 0.5f) < Constants.COLLISION_CENTER_THRESHOLD
        val isInUpperHalf = centerY < 0.6f  // Not looking down at floor
        val isCollisionObject = detection.className in Constants.COLLISION_THREATS

        return isLarge && isCentered && isInUpperHalf && isCollisionObject
    }

    private fun handleCollision(detection: Detection) {
        val now = System.currentTimeMillis()

        // Rate limit collision alerts (but very short cooldown)
        if (now - lastCollisionTime < collisionCooldown) return
        lastCollisionTime = now

        Log.w(TAG, "⚠️ COLLISION DETECTED: ${detection.className}")

        // EMERGENCY HAPTIC - strongest pattern
        CoroutineScope(Dispatchers.Main).launch {
            bleManager.sendHapticCommand(0x99.toByte(), Constants.DIRECTION_FRONT)  // Emergency code
        }

        // URGENT AUDIO
        CoroutineScope(Dispatchers.Main).launch {
            audioManager.speakImmediate("Stop! ${detection.className} ahead!")
        }
    }

    private fun classifyThreat(detection: Detection): ThreatLevel {
        val distance = estimateDistance(detection)

        return when {
            detection.className in Constants.CRITICAL_THREATS -> {
                when {
                    distance < 1.5f -> ThreatLevel.CRITICAL
                    distance < 3.0f -> ThreatLevel.HIGH
                    distance < 5.0f -> ThreatLevel.MEDIUM
                    else -> ThreatLevel.NONE
                }
            }

            detection.className in Constants.HIGH_THREATS -> {
                when {
                    distance < 2.0f -> ThreatLevel.HIGH
                    distance < 4.0f -> ThreatLevel.MEDIUM
                    distance < 6.0f -> ThreatLevel.LOW
                    else -> ThreatLevel.NONE
                }
            }

            detection.className in Constants.MEDIUM_THREATS -> {
                when {
                    distance < 1.5f -> ThreatLevel.MEDIUM
                    distance < 3.0f -> ThreatLevel.LOW
                    else -> ThreatLevel.NONE
                }
            }

            detection.className in Constants.POTHOLE_CLASSES -> {
                when {
                    distance < 3.0f -> ThreatLevel.HIGH
                    distance < 5.0f -> ThreatLevel.MEDIUM
                    else -> ThreatLevel.NONE
                }
            }

            else -> ThreatLevel.NONE
        }
    }

    private fun estimateDistance(detection: Detection): Float {
        val area = detection.bbox.width * detection.bbox.height

        return when {
            area > 0.35f -> 0.5f
            area > 0.20f -> 1.5f
            area > 0.12f -> 2.5f
            area > 0.06f -> 4.0f
            else -> 6.0f
        }
    }

    private fun calculateDirection(detection: Detection): Byte {
        val centerX = detection.bbox.x + detection.bbox.width / 2

        return when {
            centerX < 0.4f -> Constants.DIRECTION_LEFT
            centerX > 0.6f -> Constants.DIRECTION_RIGHT
            else -> Constants.DIRECTION_FRONT
        }
    }

    private fun triggerHapticFeedback(level: ThreatLevel, direction: Byte) {
        val command = when (level) {
            ThreatLevel.CRITICAL -> Constants.HAPTIC_CRITICAL
            ThreatLevel.HIGH -> Constants.HAPTIC_HIGH
            ThreatLevel.MEDIUM -> Constants.HAPTIC_MEDIUM
            ThreatLevel.LOW -> Constants.HAPTIC_LOW
            else -> return
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
            Constants.DIRECTION_LEFT.toInt() -> "left"
            Constants.DIRECTION_RIGHT.toInt() -> "right"
            else -> "ahead"
        }

        val distanceText = when {
            distance < 1.0f -> "very close"
            distance < 2.5f -> "close"
            distance < 4.0f -> "nearby"
            else -> ""
        }

        val message = when (level) {
            ThreatLevel.CRITICAL -> {
                "Warning! ${detection.className} $directionText, $distanceText"
            }
            ThreatLevel.HIGH -> {
                "${detection.className} $directionText, $distanceText"
            }
            ThreatLevel.MEDIUM -> {
                "${detection.className} $directionText"
            }
            ThreatLevel.LOW -> {
                "Obstacle $directionText"
            }
            else -> return
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