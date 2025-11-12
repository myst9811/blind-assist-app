// app/src/main/java/com/blindassistant/app/ml/ModelManager.kt

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

class ModelManager(private val context: Context) {

    private var yoloModel: Module? = null
    private var potholeModel: Module? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "ModelManager"
        private const val YOLO_MODEL = "models/yolov8n.torchscript"
        private const val POTHOLE_MODEL = "models/pothole_detector.torchscript"
        private const val INPUT_SIZE = 640
        private const val POTHOLE_INPUT_SIZE = 416
    }

    suspend fun initialize() {
        if (isInitialized) return

        try {
            Log.d(TAG, "Initializing models...")

            // Load YOLOv8
            yoloModel = Module.load(assetFilePath(YOLO_MODEL))
            Log.d(TAG, "✅ YOLOv8 loaded")

            // Load pothole detector
            potholeModel = Module.load(assetFilePath(POTHOLE_MODEL))
            Log.d(TAG, "✅ Pothole detector loaded")

            isInitialized = true
            Log.d(TAG, "🎉 All models initialized")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Model initialization failed", e)
            throw e
        }
    }

    fun detectObjects(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || yoloModel == null) {
            Log.w(TAG, "Model not initialized")
            return emptyList()
        }

        return try {
            // Preprocess
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                resizedBitmap,
                floatArrayOf(0f, 0f, 0f),  // No normalization for YOLOv8
                floatArrayOf(1f, 1f, 1f)
            )

            // Inference
            val outputTensor = yoloModel!!.forward(IValue.from(inputTensor)).toTensor()

            // Post-process
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
        val shape = output.shape()  // [1, 84, 8400]

        val numBoxes = shape[2].toInt()
        val numValues = shape[1].toInt()

        val detections = mutableListOf<Detection>()

        for (i in 0 until numBoxes) {
            // Extract box values
            val boxData = FloatArray(numValues)
            for (j in 0 until numValues) {
                val idx = j * numBoxes + i
                boxData[j] = outputData[idx]
            }

            // Bbox coords
            val xCenter = boxData[0] / INPUT_SIZE
            val yCenter = boxData[1] / INPUT_SIZE
            val width = boxData[2] / INPUT_SIZE
            val height = boxData[3] / INPUT_SIZE

            // Class scores
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

        return nonMaxSuppression(detections)
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)

            sorted.removeAll { detection ->
                val iou = calculateIOU(best.bbox, detection.bbox)
                iou > Constants.IOU_THRESHOLD && best.classId == detection.classId
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

//    private fun assetFilePath(assetName: String): String {
//        val file = File(context.filesDir, assetName)
//        if (file.exists()) return file.absolutePath
//
//        context.assets.open(assetName).use { inputStream ->
//            FileOutputStream(file).use { outputStream ->
//                inputStream.copyTo(outputStream)
//            }
//        }
//        return file.absolutePath
//    }

    private fun assetFilePath(assetName: String): String {
        val outFile = File(context.filesDir, assetName)

        // Create parent directories if needed
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