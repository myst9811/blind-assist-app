// app/src/main/java/com/blindassistant/app/MainActivity.kt

package com.example.blindassistant.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.blindassistant.app.databinding.ActivityMainBinding
import com.example.blindassistant.app.ml.ModelManager
import com.example.blindassistant.app.ml.ThreatAnalyzer
import com.example.blindassistant.app.services.*
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private lateinit var audioManager: AudioManager
    private lateinit var bleManager: BLEManager
    private lateinit var geminiService: GeminiService
    private lateinit var threatAnalyzer: ThreatAnalyzer
    private lateinit var cameraService: CameraService

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request permissions
        if (allPermissionsGranted()) {
            initializeApp()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Setup screen tap for voice commands
        binding.root.setOnClickListener {
            handleScreenTap()
        }
    }

    private fun initializeApp() {
        lifecycleScope.launch {
            try {
                binding.statusText.text = "Initializing..."

                // Initialize services
                modelManager = ModelManager(this@MainActivity)
                audioManager = AudioManager(this@MainActivity)
                bleManager = BLEManager(this@MainActivity)
                geminiService = GeminiService(this@MainActivity)

                // Initialize audio first
                audioManager.initialize {
                    audioManager.speak("Initializing Blind Assistant", Constants.PRIORITY_HIGH)
                }

                // Initialize models
                binding.statusText.text = "Loading AI models..."
                withContext(Dispatchers.IO) {
                    modelManager.initialize()
                    // Initialize Gemini
                    val apiKey = "AIzaSyDgRqWTgrl6kMLHRLA42LKHAnOoYUwROcE"  // Replace with actual key
                    geminiService.initialize(apiKey)
                }

                // Initialize threat analyzer
                threatAnalyzer = ThreatAnalyzer(audioManager, bleManager)

                // Initialize camera service
                cameraService = CameraService(
                    this@MainActivity,
                    modelManager,
                    threatAnalyzer
                )

                // Start camera
                binding.statusText.text = "Starting camera..."
                cameraService.startCamera(binding.cameraPreview, this@MainActivity)

                // Connect to glove
                binding.statusText.text = "Connecting to glove..."
                bleManager.scanAndConnect { error ->
                    Log.w(TAG, "Glove connection failed: $error")
                    audioManager.speak("Glove not connected, app will work without it", Constants.PRIORITY_MEDIUM)
                }

                // Setup button callback from glove
                bleManager.setButtonCallback { buttonId ->
                    handleGloveButton(buttonId)
                }

                // Observe state
                observeState()

                // Ready
                binding.statusText.text = "Ready"
                audioManager.speak(
                    "Camera ready. Double tap screen for voice commands.",
                    Constants.PRIORITY_HIGH
                )

            } catch (e: Exception) {
                Log.e(TAG, "Initialization error", e)
                binding.statusText.text = "Error: ${e.message}"
                audioManager.speak("Initialization failed", Constants.PRIORITY_CRITICAL)
            }
        }
    }

    private fun observeState() {
        // Observe glove connection
        lifecycleScope.launch {
            bleManager.isConnected.collectLatest { connected ->
                binding.gloveStatus.text = if (connected) {
                    "🤚 Glove: Connected"
                } else {
                    "🤚 Glove: Disconnected"
                }
            }
        }

        // Observe FPS
        lifecycleScope.launch {
            cameraService.fps.collectLatest { fps ->
                binding.fpsText.text = "FPS: $fps"
            }
        }

        // Observe detections (for visual feedback)
        lifecycleScope.launch {
            cameraService.detections.collectLatest { detections ->
                // Could draw bounding boxes here for sighted helpers
                Log.d(TAG, "Detected ${detections.size} objects")
            }
        }
    }

    private fun handleScreenTap() {
        // Toggle voice listening
        binding.voiceIndicator.visibility = View.VISIBLE
        audioManager.speak("Listening for command", Constants.PRIORITY_HIGH)

        // In production, implement speech recognition here
        // For now, just hide indicator after 3 seconds
        binding.root.postDelayed({
            binding.voiceIndicator.visibility = View.GONE
        }, 3000)
    }

    private fun handleGloveButton(buttonId: Byte) {
        when (buttonId.toInt()) {
            0x01 -> {
                // OCR button
                audioManager.speak("Reading text", Constants.PRIORITY_HIGH)
                captureAndProcessOCR()
            }
            0x02 -> {
                // Scene describe button
                audioManager.speak("Analyzing scene", Constants.PRIORITY_HIGH)
                captureAndDescribeScene()
            }
        }
    }

    private fun captureAndProcessOCR() {
        // Capture current frame and process with Gemini
        lifecycleScope.launch {
            try {
                // In production, capture actual frame from camera
                // For now, this is a placeholder
                audioManager.speak("OCR feature coming soon", Constants.PRIORITY_MEDIUM)
            } catch (e: Exception) {
                Log.e(TAG, "OCR error", e)
                audioManager.speak("Failed to read text", Constants.PRIORITY_HIGH)
            }
        }
    }

    private fun captureAndDescribeScene() {
        lifecycleScope.launch {
            try {
                audioManager.speak("Scene description coming soon", Constants.PRIORITY_MEDIUM)
            } catch (e: Exception) {
                Log.e(TAG, "Scene description error", e)
                audioManager.speak("Failed to describe scene", Constants.PRIORITY_HIGH)
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initializeApp()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraService.stopCamera()
        audioManager.cleanup()
        bleManager.disconnect()
        modelManager.cleanup()
    }
}
