// app/src/main/java/com/blindassistant/app/services/CameraService.kt

package com.example.blindassistant.app.services

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.blindassistant.app.ml.ModelManager
import com.example.blindassistant.app.ml.ThreatAnalyzer
import com.example.blindassistant.app.models.Detection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService(
    private val context: Context,
    private val modelManager: ModelManager,
    private val threatAnalyzer: ThreatAnalyzer
) {

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps

    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()
    private var potholeFrameCounter = 0

    companion object {
        private const val TAG = "CameraService"
    }

    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Image analysis
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        processFrame(imageProxy)
                    })
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "✅ Camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Convert to bitmap
            val bitmap = imageProxy.toBitmap()

            // Run object detection
            val detections = modelManager.detectObjects(bitmap)

            // Run pothole detection every 3rd frame
            potholeFrameCounter++
            if (potholeFrameCounter % 3 == 0) {
                val potholeDetections = modelManager.detectPotholes(bitmap)
                _detections.value = detections + potholeDetections

                // Analyze potholes
                potholeDetections.forEach { threatAnalyzer.analyzeThreat(it) }
            } else {
                _detections.value = detections
            }

            // Analyze threats
            detections.forEach { threatAnalyzer.analyzeThreat(it) }

            // Update FPS
            updateFPS()

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun updateFPS() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsUpdate

        if (elapsed >= 1000) {
            _fps.value = frameCount
            frameCount = 0
            lastFpsUpdate = now
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}