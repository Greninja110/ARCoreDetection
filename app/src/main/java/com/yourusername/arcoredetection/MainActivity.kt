package com.yourusername.arcoredetection

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ui.PlayerView
import io.github.sceneview.ar.ArSceneView
import com.yourusername.arcoredetection.databinding.ActivityMainBinding
import com.yourusername.arcoredetection.models.ARModels
import com.yourusername.arcoredetection.models.DetectionResult
import com.yourusername.arcoredetection.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    // View binding
    private lateinit var binding: ActivityMainBinding

    // Camera Manager
    private lateinit var cameraManager: CameraManager

    // RTSP Client
    private lateinit var rtspClient: RtspClient

    // AR Renderer
    private lateinit var arRenderer: ARRenderer

    // Permission Helper
    private lateinit var permissionHelper: PermissionHelper

    // Connection dialog
    private var connectionDialog: AlertDialog? = null

    // Streaming service
    private var streamingService: StreamingService? = null
    private var serviceBound = false

    // Server URL - Should be configurable
    private var serverUrl = "http://192.168.1.100:5000"
    private var rtspUrl = "rtsp://192.168.1.100:8554/live"

    // AR Mode active
    private var arModeActive = false

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("Connected to StreamingService")
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            serviceBound = true

            // Set up observers for service state
            lifecycleScope.launch {
                streamingService?.isStreaming?.collect { isStreaming ->
                    binding.tvStreaming.text = if (isStreaming) "Streaming: Yes" else "Streaming: No"
                }
            }

            lifecycleScope.launch {
                streamingService?.networkInfo?.collect { info ->
                    info?.let {
                        // Update UI with network info if needed
                        Timber.d("Network info: ping=${it.ping}ms, upload=${it.uploadSpeed}Mbps")
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.d("Disconnected from StreamingService")
            streamingService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("MainActivity created")

        // Initialize binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize camera manager
        cameraManager = CameraManager(this)

        // Initialize permission helper
        permissionHelper = PermissionHelper(this)
        permissionHelper.initialize()

        // Check permissions
        if (!permissionHelper.hasRequiredPermissions()) {
            Timber.d("Requesting permissions")
            permissionHelper.requestPermissions { granted ->
                if (granted) {
                    initializeApp()
                } else {
                    Timber.e("Permissions not granted, finishing activity")
                    Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            Timber.d("Permissions already granted")
            initializeApp()
        }

        // Bind to streaming service
        Intent(this, StreamingService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    /**
     * Initialize the app components after permissions are granted
     */
    private fun initializeApp() {
        Timber.d("Initializing app components")

        // Initialize camera manager
        cameraManager.initialize(
            this,
            onInitialized = {
                // Start camera with preview
                startCameraPreview()
            },
            onError = { e ->
                Timber.e(e, "Camera initialization error")
                Toast.makeText(this, "Camera initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )

        // Initialize RTSP client
        rtspClient = RtspClient(this)

        // Initialize AR Renderer with the ArSceneView from the layout
        arRenderer = ARRenderer(this, binding.arSceneView)

        // Set up UI elements
        setupUI()

        // Show connection dialog to configure the server
        showConnectionDialog()
    }

    /**
     * Start camera preview
     */
    private fun startCameraPreview() {
        cameraManager.startCamera(
            this,
            enableVideoCapture = true,
            onError = { e ->
                Timber.e(e, "Failed to start camera")
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )

        // Connect preview surface
        val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.playerView)
        cameraManager.connectPreview(previewView.surfaceProvider)

        Timber.d("Camera preview started")
    }

    /**
     * Set up UI elements and listeners
     */
    private fun setupUI() {
        // Set up server connection button
        binding.btnConnect.setOnClickListener {
            showConnectionDialog()
        }

        // Set up toggle AR mode button
        binding.btnToggleAr.setOnClickListener {
            toggleARMode()
        }

        // Set up AR model spinner
        val modelList = ARModels.availableModels
        val modelNames = modelList.map { it.name }.toTypedArray()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelNames)
        binding.spinnerModels.adapter = adapter

        binding.spinnerModels.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = ARModels.availableModels[position]
                Timber.d("Selected model: ${selectedModel.name}")
                arRenderer.selectModel(selectedModel.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                arRenderer.clearSelectedModel()
            }
        }

        // Set up place button
        binding.btnPlace.setOnClickListener {
            // No need to do anything here, the tap listener in ARRenderer will handle it
            Toast.makeText(this, "Tap on a surface to place the model", Toast.LENGTH_SHORT).show()
        }

        // Set up clear button
        binding.btnClear.setOnClickListener {
            arRenderer.clearModels()
            Toast.makeText(this, "Cleared all models", Toast.LENGTH_SHORT).show()
        }

        // Hide AR controls initially
        showARControls(false)

        // Enable AR toggle button once camera is initialized
        binding.btnToggleAr.isEnabled = true
    }

    /**
     * Show dialog to configure server connection
     */
    private fun showConnectionDialog() {
        // Create a dialog with text fields for server URL and RTSP URL
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connect to Server")

        val dialogView = layoutInflater.inflate(R.layout.dialog_server_config, null)
        val serverUrlField = dialogView.findViewById<android.widget.EditText>(R.id.editServerUrl)
        val rtspUrlField = dialogView.findViewById<android.widget.EditText>(R.id.editRtspUrl)

        // Set current values
        serverUrlField.setText(serverUrl)
        rtspUrlField.setText(rtspUrl)

        builder.setView(dialogView)

        builder.setPositiveButton("Connect") { _, _ ->
            // Update URLs
            serverUrl = serverUrlField.text.toString()
            rtspUrl = rtspUrlField.text.toString()

            Timber.d("Connecting to server: $serverUrl, RTSP: $rtspUrl")

            // Start streaming service if bound
            val serverIp = extractServerIp(rtspUrl)
            val serverPort = extractServerPort(rtspUrl)

            streamingService?.startStreaming("720p", serverIp, serverPort) ?: run {
                // Start the service if not bound
                Intent(this, StreamingService::class.java).also { intent ->
                    intent.action = StreamingService.ACTION_START_STREAMING
                    intent.putExtra(StreamingService.EXTRA_QUALITY, "720p")
                    intent.putExtra(StreamingService.EXTRA_SERVER_IP, serverIp)
                    intent.putExtra(StreamingService.EXTRA_SERVER_PORT, serverPort)
                    startService(intent)
                }
            }

            // Configure client
            rtspClient.configure(serverUrl, rtspUrl)

            // Update UI
            binding.btnConnect.text = "Connected"
            binding.tvStatus.text = "Status: Connected"
            binding.btnToggleAr.isEnabled = true
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        connectionDialog = builder.create()
        connectionDialog?.show()
    }

    /**
     * Extract server IP from RTSP URL
     */
    private fun extractServerIp(rtspUrl: String): String {
        // Simple extraction, assumes format rtsp://ip:port/path
        val regex = "rtsp://(.*?)(?::\\d+)?/".toRegex()
        val matchResult = regex.find(rtspUrl)
        return matchResult?.groupValues?.get(1) ?: "192.168.1.100"
    }

    /**
     * Extract server port from RTSP URL
     */
    private fun extractServerPort(rtspUrl: String): Int {
        // Simple extraction, assumes format rtsp://ip:port/path
        val regex = "rtsp://.*?:(\\d+)/".toRegex()
        val matchResult = regex.find(rtspUrl)
        return matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 8554
    }

    /**
     * Toggle AR mode on/off
     */
    private fun toggleARMode() {
        arModeActive = !arModeActive

        if (arModeActive) {
            // Switch to AR mode
            Timber.d("Enabling AR mode")
            binding.btnToggleAr.text = "Disable AR"

            // Show AR scene view
            binding.arSceneView.visibility = View.VISIBLE

            // Fade out player view slightly to show AR overlay
            findViewById<PlayerView>(R.id.playerView).alpha = 0.7f

            // Show AR controls
            showARControls(true)

            // Initialize AR renderer
            lifecycleScope.launch(Dispatchers.Main) {
                arRenderer.initialize(lifecycleScope)

                // Set up error listener
                arRenderer.setOnARErrorListener { error ->
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Switch to normal mode
            Timber.d("Disabling AR mode")
            binding.btnToggleAr.text = "Enable AR"

            // Hide AR scene view
            binding.arSceneView.visibility = View.GONE

            // Restore player view opacity
            findViewById<PlayerView>(R.id.playerView).alpha = 1.0f

            // Hide AR controls
            showARControls(false)

            // Clean up AR renderer
            arRenderer.cleanup()
        }
    }

    /**
     * Show or hide AR controls
     */
    private fun showARControls(show: Boolean) {
        binding.arControlsGroup.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Set up observers for LiveData from RTSP client
     */
    private fun setupObservers() {
        // Observe connection state
        rtspClient.isConnected.observe(this) { connected ->
            binding.tvStatus.text = if (connected) "Status: Connected" else "Status: Disconnected"
        }

        // Observe streaming state
        rtspClient.isStreaming.observe(this) { streaming ->
            binding.tvStreaming.text = if (streaming) "Streaming: Yes" else "Streaming: No"
        }

        // Observe detection results
        rtspClient.detectionResults.observe(this) { result ->
            updateDetectionResults(result)
        }

        // Observe FPS
        rtspClient.fps.observe(this) { fps ->
            binding.tvFps.text = "FPS: $fps"
        }

        // Observe errors
        rtspClient.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                Timber.e("RTSP error: $it")
            }
        }
    }

    /**
     * Update UI with detection results
     */
    private fun updateDetectionResults(result: DetectionResult) {
        Timber.d("Received ${result.objects.size} detection results")

        // Update detection count
        binding.tvObjectCount.text = "Objects: ${result.objects.size}"

        // If AR mode is active, overlay detections
        if (arModeActive && arRenderer.isActive()) {
            arRenderer.overlayDetections(result.objects)
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume()")

        // Set up observers
        setupObservers()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause()")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("onDestroy()")

        // Unbind from service
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        // Clean up resources
        rtspClient.cleanup()
        arRenderer.cleanup()
        cameraManager.shutdown()

        // Dismiss any open dialogs
        connectionDialog?.dismiss()
    }
}