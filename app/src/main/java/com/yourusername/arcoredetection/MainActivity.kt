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
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.yourusername.arcoredetection.databinding.ActivityMainBinding
import com.yourusername.arcoredetection.network.RtspClient
import com.yourusername.arcoredetection.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
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

    // Server configuration
    private var serverIp = "192.168.1.100" // Your laptop IP address - should be configurable
    private var rtspPort = 8554
    private var dataPort = 8555
    private var streamPath = "live"

    // AR Mode active
    private var arModeActive = false

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("Connected to StreamingService")
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            serviceBound = true

            // Update UI with service state
            updateServiceStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.d("Disconnected from StreamingService")
            streamingService = null
            serviceBound = false

            // Update UI
            binding.tvStatus.text = "Status: Disconnected"
            binding.tvStreaming.text = "Streaming: No"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("MainActivity created")

        // Initialize binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        cameraManager = CameraManager(this)

        // Initialize camera
        initializeCamera()

        // Create RTSP client
        rtspClient = RtspClient(
            serverIp = serverIp,
            rtspPort = rtspPort,
            dataPort = dataPort,
            streamPath = streamPath
        )

        // Initialize AR Renderer with RTSP client
        arRenderer = ARRenderer(this, binding.arSceneView, rtspClient)

        // Set up UI elements
        setupUI()

        // Show connection dialog
        showConnectionDialog()
    }

    /**
     * Initialize the camera system
     */
    private fun initializeCamera() {
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
        val previewView = findViewById<PreviewView>(R.id.playerView)
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

        // Hide AR controls initially
        binding.arControlsGroup.visibility = View.GONE

        // Enable AR toggle button once camera is initialized
        binding.btnToggleAr.isEnabled = true

        // Setup status display
        binding.tvStatus.text = "Status: Disconnected"
        binding.tvStreaming.text = "Streaming: No"
        binding.tvFps.text = "FPS: 0"
        binding.tvObjectCount.text = "Objects: 0"
    }

    /**
     * Update UI with service status
     */
    private fun updateServiceStatus() {
        lifecycleScope.launch(Dispatchers.Main) {
            val isStreaming = streamingService?.isStreaming?.value ?: false
            val status = if (serviceBound) "Connected" else "Disconnected"

            binding.tvStatus.text = "Status: $status"
            binding.tvStreaming.text = "Streaming: ${if (isStreaming) "Yes" else "No"}"
        }
    }

    /**
     * Show dialog to configure server connection
     */
    private fun showConnectionDialog() {
        // Create a dialog with text fields for server configuration
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connect to Laptop Server")

        val dialogView = layoutInflater.inflate(R.layout.dialog_server_config, null)
        val serverIpField = dialogView.findViewById<android.widget.EditText>(R.id.editServerUrl)
        val rtspUrlField = dialogView.findViewById<android.widget.EditText>(R.id.editRtspUrl)

        // Set current values
        serverIpField.setText(serverIp)
        rtspUrlField.setText("rtsp://$serverIp:$rtspPort/$streamPath")

        builder.setView(dialogView)

        builder.setPositiveButton("Connect") { _, _ ->
            // Update server IP and RTSP URL
            serverIp = serverIpField.text.toString()
            val rtspUrlText = rtspUrlField.text.toString()

            // Extract RTSP parts if needed
            val rtspRegex = "rtsp://(.*?):(\\d+)/(.*)".toRegex()
            val match = rtspRegex.find(rtspUrlText)

            if (match != null) {
                serverIp = match.groupValues[1]
                rtspPort = match.groupValues[2].toIntOrNull() ?: 8554
                streamPath = match.groupValues[3]
            }

            Timber.d("Connecting to server: $serverIp:$rtspPort/$streamPath")

            // Create new RTSP client with updated configuration
            rtspClient = RtspClient(
                serverIp = serverIp,
                rtspPort = rtspPort,
                dataPort = dataPort,
                streamPath = streamPath
            )

            // Initialize AR Renderer with new RTSP client
            arRenderer = ARRenderer(this, binding.arSceneView, rtspClient)

            // Connect to server
            lifecycleScope.launch {
                try {
                    rtspClient.connect(
                        onConnected = {
                            runOnUiThread {
                                binding.tvStatus.text = "Status: Connected"
                                binding.btnConnect.text = "Connected"
                                binding.btnToggleAr.isEnabled = true

                                // Show toast notification
                                Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDisconnected = {
                            runOnUiThread {
                                binding.tvStatus.text = "Status: Disconnected"
                                binding.btnConnect.text = "Connect to Server"

                                // Show toast notification
                                Toast.makeText(this@MainActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { error ->
                            runOnUiThread {
                                binding.tvStatus.text = "Status: Error"
                                binding.btnConnect.text = "Connect to Server"

                                // Show error message
                                Toast.makeText(this@MainActivity, "Connection error: $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to connect to server")

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to connect: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        connectionDialog = builder.create()
        connectionDialog?.show()
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

            // Initialize AR renderer
            lifecycleScope.launch(Dispatchers.Main) {
                arRenderer.initialize(lifecycleScope)

                // Set up error listener
                arRenderer.setOnARErrorListener { error ->
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }

                // Start streaming AR data
                arRenderer.startStreaming()
            }
        } else {
            // Switch to normal mode
            Timber.d("Disabling AR mode")
            binding.btnToggleAr.text = "Enable AR"

            // Hide AR scene view
            binding.arSceneView.visibility = View.GONE

            // Stop AR streaming
            arRenderer.stopStreaming()

            // Clean up AR renderer
            arRenderer.cleanup()
        }
    }

    /**
     * Update FPS display
     */
    fun updateFps(fps: Float) {
        binding.tvFps.text = "FPS: ${String.format("%.1f", fps)}"
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume()")

        // Update service status
        updateServiceStatus()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause()")

        // Stop streaming if active
        if (arModeActive) {
            arRenderer.stopStreaming()
        }
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
        rtspClient.disconnect()
        arRenderer.cleanup()
        cameraManager.shutdown()

        // Dismiss any open dialogs
        connectionDialog?.dismiss()
    }
}