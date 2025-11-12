// app/src/main/java/com/example/blindassistant/app/utils/Constants.kt

package com.example.blindassistant.app.utils

object Constants {
    // COCO class names (80 classes)
    val COCO_CLASSES = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
        "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
        "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
        "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    // Pothole model classes
    val POTHOLE_CLASSES = arrayOf(
        "crack", "pothole", "bump", "manhole"
    )

    // Threat classification
    val CRITICAL_THREATS = setOf("car", "truck", "bus", "motorcycle", "train")
    val HIGH_THREATS = setOf("person", "bicycle", "dog")
    val MEDIUM_THREATS = setOf("chair", "bench", "potted plant")

    val COLLISION_THREATS = setOf(
        "person", "chair", "couch", "bench", "table", "bed",
        "door", "refrigerator", "tv", "dining table", "toilet",
        "bicycle", "motorcycle", "car", "truck", "bus"
    )

    // For generic obstacles (when walking towards something big)
    const val COLLISION_AREA_THRESHOLD = 0.25f  // 25% of screen = walking into it
    const val COLLISION_CENTER_THRESHOLD = 0.3f  // Must be in center 40% of frame


    // Detection thresholds
    const val CONFIDENCE_THRESHOLD = 0.5f
    const val IOU_THRESHOLD = 0.45f

    // Audio priorities
    const val PRIORITY_CRITICAL = 100
    const val PRIORITY_HIGH = 80
    const val PRIORITY_MEDIUM = 60
    const val PRIORITY_LOW = 40

    // BLE UUIDs - MUST MATCH ESP32
    const val BLE_SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
    const val BLE_RX_CHAR_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
    const val BLE_TX_CHAR_UUID = "beb5483f-36e1-4688-b7f5-ea07361b26a8"
    const val BLE_DEVICE_NAME = "BlindAssist_Glove"

    // Haptic commands - MUST MATCH ESP32
    const val HAPTIC_CRITICAL: Byte = 0x01
    const val HAPTIC_HIGH: Byte = 0x02
    const val HAPTIC_MEDIUM: Byte = 0x03
    const val HAPTIC_LOW: Byte = 0x04
    const val HAPTIC_CONFIRM: Byte = 0x10

    // Direction codes
    const val DIRECTION_FRONT: Byte = 0x00
    const val DIRECTION_LEFT: Byte = 0x01
    const val DIRECTION_RIGHT: Byte = 0x02

    // Button event from glove
    const val BUTTON_EVENT: Byte = 0x01

    enum class DetectionMode {
        NORMAL,          // Continuous object detection
        WAITING_QR,      // Waiting for QR scan confirmation
        WAITING_OCR,     // Waiting for text read confirmation
        SCANNING_QR,     // Currently scanning QR
        READING_TEXT     // Currently reading text
    }

    // Confidence thresholds for auto-detection
    const val QR_DETECTION_COOLDOWN = 10000L  // 10 seconds between QR prompts
    const val TEXT_DETECTION_COOLDOWN = 10000L // 10 seconds between text prompts
}