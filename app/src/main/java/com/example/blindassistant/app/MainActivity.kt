// app/src/main/java/com/example/blindassistant/app/MainActivity.kt

package com.example.blindassistant.app

import android.Manifest
import android.content.pm.PackageManager
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
import com.example.blindassistant.app.ml.ModelLoadResult
import com.example.blindassistant.app.ml.ModelManager
import com.example.blindassistant.app.ml.ThreatAnalyzer
import com.example.blindassistant.app.services.*
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private lateinit var audioManager: AudioManager
    private lateinit var bleManager: BLEManager
    private lateinit var geminiService: GeminiService
    private lateinit var threatAnalyzer: ThreatAnalyzer
    private lateinit var cameraService: CameraService
    private lateinit var visionRouter: VisionRouter

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
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            initializeApp()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

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
                when (val result = modelManager.initialize()) {
                    is ModelLoadResult.Success -> Log.d(TAG, "Models loaded")
                    is ModelLoadResult.Error -> {
                        binding.statusText.text = "Model error: ${result.message}"
                        audioManager.speak(
                            "Warning: object detection unavailable. ${result.message}",
                            Constants.PRIORITY_CRITICAL
                        )
                    }
                }

                // Initialize Gemini - REPLACE WITH YOUR ACTUAL API KEY
                val apiKey = BuildConfig.GEMINI_API_KEY
                geminiService.initialize(apiKey)

                // Initialize threat analyzer
                threatAnalyzer = ThreatAnalyzer(audioManager, bleManager, lifecycleScope)

                // Initialize vision router (NEW)
                visionRouter = VisionRouter(
                    this@MainActivity,
                    geminiService,
                    audioManager
                )

                // Initialize camera service
                cameraService = CameraService(
                    this@MainActivity,
                    modelManager,
                    threatAnalyzer,
                    audioManager,
                    lifecycleScope
                )

                // Connect vision router to camera (NEW)
                cameraService.setVisionRouter(visionRouter)

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
        lifecycleScope.launch {
            bleManager.isConnected.collectLatest { connected ->
                val statusText = if (connected) "Glove: Connected" else "Glove: Disconnected"
                binding.gloveStatus.text = statusText
                binding.gloveStatus.contentDescription = statusText
                binding.root.announceForAccessibility(statusText)
            }
        }

        lifecycleScope.launch {
            cameraService.fps.collectLatest { fps ->
                binding.fpsText.text = "FPS: $fps"
            }
        }

        lifecycleScope.launch {
            cameraService.detections.collectLatest { detections ->
                Log.d(TAG, "Detected ${detections.size} objects")
            }
        }
    }

    private fun handleScreenTap() {
        binding.voiceIndicator.visibility = View.VISIBLE
        audioManager.speak("Listening for command", Constants.PRIORITY_HIGH)

        binding.root.postDelayed({
            binding.voiceIndicator.visibility = View.GONE
        }, 3000)
    }

    private fun handleGloveButton(buttonId: Byte) {
        when (buttonId) {
            Constants.BUTTON_EVENT -> {
                lifecycleScope.launch {
                    // Handle QR/OCR mode (NEW FEATURE)
                    cameraService.handleButtonPress()

                    // Haptic confirmation
                    bleManager.sendHapticCommand(Constants.HAPTIC_CONFIRM)
                }
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