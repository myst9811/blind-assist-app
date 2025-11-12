// app/src/main/java/com/example/blindassistant/app/services/CameraService.kt

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
import com.example.blindassistant.app.utils.Constants
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
    private val threatAnalyzer: ThreatAnalyzer,
    private val audioManager: AudioManager
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

    // Vision router for QR/Text detection
    private var visionRouter: VisionRouter? = null
    private var currentFrame: Bitmap? = null
    private var currentMode = Constants.DetectionMode.NORMAL

    companion object {
        private const val TAG = "CameraService"
    }

    fun setVisionRouter(router: VisionRouter) {
        visionRouter = router
        Log.d(TAG, "VisionRouter set")
    }

    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

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
            val bitmap = imageProxy.toBitmap()

            // Always run object detection
            val detections = modelManager.detectObjects(bitmap)

            // Run pothole detection every 3rd frame
            potholeFrameCounter++
            if (potholeFrameCounter % 3 == 0) {
                val potholeDetections = modelManager.detectPotholes(bitmap)
                _detections.value = detections + potholeDetections
                potholeDetections.forEach { threatAnalyzer.analyzeThreat(it) }
            } else {
                _detections.value = detections
            }

            detections.forEach { threatAnalyzer.analyzeThreat(it) }

            // Check for QR/Text every 10th frame
            // FIX: Use local immutable copy to avoid smart cast issues
            val router = visionRouter
            if (frameCount % 10 == 0 && router != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    when (currentMode) {
                        Constants.DetectionMode.NORMAL -> {
                            // Check for QR codes first
                            if (router.analyzeForQRCode(bitmap)) {
                                currentFrame = bitmap
                                currentMode = Constants.DetectionMode.WAITING_QR
                                router.setMode(Constants.DetectionMode.WAITING_QR)
                                audioManager.speak(
                                    "QR code detected. Press button to scan.",
                                    Constants.PRIORITY_HIGH
                                )
                            }
                            // Then check for text
                            else if (router.analyzeForText(bitmap)) {
                                currentFrame = bitmap
                                currentMode = Constants.DetectionMode.WAITING_OCR
                                router.setMode(Constants.DetectionMode.WAITING_OCR)
                                audioManager.speak(
                                    "Text detected. Press button to read.",
                                    Constants.PRIORITY_HIGH
                                )
                            }
                        }
                        else -> {
                            // In waiting mode, don't check again
                        }
                    }
                }
            }

            updateFPS()

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    suspend fun handleButtonPress() {
        // FIX: Use local immutable copy
        val router = visionRouter
        val frame = currentFrame

        when (currentMode) {
            Constants.DetectionMode.WAITING_QR -> {
                if (frame != null && router != null) {
                    router.scanQRCode(frame)
                }
                currentMode = Constants.DetectionMode.NORMAL
                router?.setMode(Constants.DetectionMode.NORMAL)
            }

            Constants.DetectionMode.WAITING_OCR -> {
                if (frame != null && router != null) {
                    router.readText(frame)
                }
                currentMode = Constants.DetectionMode.NORMAL
                router?.setMode(Constants.DetectionMode.NORMAL)
            }

            else -> {
                Log.d(TAG, "Button press in normal mode - no action")
            }
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
        Log.d(TAG, "Camera stopped")
    }
}