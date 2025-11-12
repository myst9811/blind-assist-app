// app/src/main/java/com/blindassistant/app/models/Detection.kt

package com.example.blindassistant.app.models

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val bbox: BoundingBox
)

data class BoundingBox(
    val x: Float,      // normalized 0-1
    val y: Float,      // normalized 0-1
    val width: Float,  // normalized 0-1
    val height: Float  // normalized 0-1
)

enum class ThreatLevel {
    CRITICAL,  // Immediate danger (car, cliff)
    HIGH,      // Approaching hazard (person, stairs)
    MEDIUM,    // Static obstacle (chair, wall)
    LOW,       // Minor obstacle
    NONE
}