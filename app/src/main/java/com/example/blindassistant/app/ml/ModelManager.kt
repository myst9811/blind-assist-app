package com.example.blindassistant.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.blindassistant.app.models.BoundingBox
import com.example.blindassistant.app.models.Detection
import com.example.blindassistant.app.utils.Constants
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

sealed class ModelLoadResult {
    object Success : ModelLoadResult()
    data class Error(val message: String, val cause: Throwable? = null) : ModelLoadResult()
}

class ModelManager(private val context: Context) {

    private var yoloModel: Module? = null
    private var potholeModel: Module? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "ModelManager"
        private const val YOLO_MODEL = "models/yolov8n.torchscript"
        private const val POTHOLE_MODEL = "models/best.torchscript"
        private const val INPUT_SIZE = 640
        private const val POTHOLE_INPUT_SIZE = 416
        private const val MAX_DETECTIONS_BEFORE_NMS = 200
    }

    suspend fun initialize(): ModelLoadResult {
        if (isInitialized) return ModelLoadResult.Success

        return try {
            Log.d(TAG, "Initializing models...")

            if (!assetExists(YOLO_MODEL)) {
                return ModelLoadResult.Error("YOLO model not found in assets: $YOLO_MODEL")
            }
            if (!assetExists(POTHOLE_MODEL)) {
                return ModelLoadResult.Error("Pothole model not found in assets: $POTHOLE_MODEL")
            }

            yoloModel = Module.load(assetFilePath(YOLO_MODEL))
            Log.d(TAG, "YOLOv8 loaded")

            potholeModel = Module.load(assetFilePath(POTHOLE_MODEL))
            Log.d(TAG, "Pothole detector loaded")

            isInitialized = true
            Log.d(TAG, "All models initialized")
            ModelLoadResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Model initialization failed", e)
            ModelLoadResult.Error("Failed to load models: ${e.message}", e)
        }
    }

    fun detectObjects(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || yoloModel == null) {
            Log.w(TAG, "Model not initialized")
            return emptyList()
        }

        return try {
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                resizedBitmap,
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(1f, 1f, 1f)
            )

            val outputTensor = yoloModel!!.forward(IValue.from(inputTensor)).toTensor()
            postprocessYOLO(outputTensor, Constants.COCO_CLASSES)

        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
            emptyList()
        }
    }

    fun detectPotholes(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || potholeModel == null) return emptyList()

        return try {
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, POTHOLE_INPUT_SIZE, POTHOLE_INPUT_SIZE, true)
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                resizedBitmap,
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(1f, 1f, 1f)
            )

            val outputTensor = potholeModel!!.forward(IValue.from(inputTensor)).toTensor()
            postprocessYOLO(outputTensor, Constants.POTHOLE_CLASSES)

        } catch (e: Exception) {
            Log.e(TAG, "Pothole detection error", e)
            emptyList()
        }
    }

    private fun postprocessYOLO(output: Tensor, classNames: Array<String>): List<Detection> {
        val outputData = output.dataAsFloatArray
        val shape = output.shape()

        val numBoxes = shape[2].toInt()
        val numValues = shape[1].toInt()

        val detections = mutableListOf<Detection>()

        for (i in 0 until numBoxes) {
            val boxData = FloatArray(numValues)
            for (j in 0 until numValues) {
                val idx = j * numBoxes + i
                boxData[j] = outputData[idx]
            }

            val xCenter = boxData[0] / INPUT_SIZE
            val yCenter = boxData[1] / INPUT_SIZE
            val width = boxData[2] / INPUT_SIZE
            val height = boxData[3] / INPUT_SIZE

            val classScores = boxData.sliceArray(4 until numValues)
            val maxScore = classScores.maxOrNull() ?: 0f
            val maxClassId = classScores.indices.maxByOrNull { classScores[it] } ?: 0

            if (maxScore > Constants.CONFIDENCE_THRESHOLD) {
                detections.add(
                    Detection(
                        classId = maxClassId,
                        className = classNames.getOrElse(maxClassId) { "unknown" },
                        confidence = maxScore,
                        bbox = BoundingBox(
                            x = xCenter - width / 2,
                            y = yCenter - height / 2,
                            width = width,
                            height = height
                        )
                    )
                )
            }
        }

        return nonMaxSuppression(detections.take(MAX_DETECTIONS_BEFORE_NMS))
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val sorted = ArrayDeque(detections.sortedByDescending { it.confidence })
        val keep = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeFirst()
            keep.add(best)
            sorted.removeAll { detection ->
                calculateIOU(best.bbox, detection.bbox) > Constants.IOU_THRESHOLD &&
                        best.classId == detection.classId
            }
        }

        return keep
    }

    private fun calculateIOU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x, box2.x)
        val y1 = maxOf(box1.y, box2.y)
        val x2 = minOf(box1.x + box1.width, box2.x + box2.width)
        val y2 = minOf(box1.y + box1.height, box2.y + box2.height)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = box1.width * box1.height
        val area2 = box2.width * box2.height
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }

    private fun assetExists(assetName: String): Boolean {
        return try {
            context.assets.open(assetName).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun assetFilePath(assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        outFile.parentFile?.mkdirs()

        if (!outFile.exists()) {
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(outFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }

        return outFile.absolutePath
    }

    fun cleanup() {
        yoloModel = null
        potholeModel = null
        isInitialized = false
    }
}
