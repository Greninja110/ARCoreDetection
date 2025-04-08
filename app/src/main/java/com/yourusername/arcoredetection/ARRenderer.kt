package com.yourusername.arcoredetection

import android.content.Context
import android.view.MotionEvent
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.yourusername.arcoredetection.network.RtspClient
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Handles AR environment tracking and streaming to laptop server via RTSP
 */
class ARRenderer(
    private val context: Context,
    private val arSceneView: ArSceneView,
    private val rtspClient: RtspClient
) {
    // Track if AR is active
    private var isARActive = false
    private var isStreaming = false

    // Performance tracking
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fps = 0f

    // Callbacks
    private var onARErrorListener: ((String) -> Unit)? = null

    // Store coroutine scope
    private lateinit var coroutineScope: CoroutineScope

    /**
     * Initialize the AR environment and RTSP connection
     */
    fun initialize(scope: CoroutineScope) {
        Timber.d("Initializing AR environment")
        this.coroutineScope = scope

        try {
            // Use basic initialization without special callbacks
            // We'll rely on manual polling instead of callbacks

            // Start polling for frames and session status
            startMonitoring()

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AR environment")
            onARErrorListener?.invoke("Failed to initialize AR: ${e.message}")
        }
    }

    /**
     * Start monitoring frames and session
     */
    private fun startMonitoring() {
        // We'll monitor the ArSceneView directly from the UI thread
        // This is a simplified approach that should work with any SceneView version

        // Set a touch listener on the ArSceneView to handle taps
        arSceneView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                handleTouch(event)
            }
            false
        }

        // Use a post-delayed handler to check frame updates periodically
        arSceneView.post(object : Runnable {
            override fun run() {
                try {
                    // Check if session is available
                    val session = getSession()
                    if (session != null && !isARActive) {
                        configureSession(session)
                    }

                    // Process current frame if available
                    val frame = getCurrentFrame()
                    if (isStreaming && frame != null) {
                        processArFrame(frame)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in frame monitoring: ${e.message}")
                }

                // Continue monitoring
                arSceneView.postDelayed(this, 16) // ~60fps
            }
        })
    }

    /**
     * Handle touch events for AR plane detection
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        if (!isStreaming) return false

        try {
            val frame = getCurrentFrame() ?: return false

            // Perform hit test at the touch point
            val hitResults = frame.hitTest(event.x, event.y)
            if (hitResults.isEmpty()) return false

            // Find the first hit that's on a plane
            for (hit in hitResults) {
                val trackable = hit.trackable
                if (trackable is Plane) {
                    sendTapEvent(hit, trackable)
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling touch: ${e.message}")
        }

        return false
    }

    /**
     * Get current session safely
     */
    private fun getSession(): Session? {
        return try {
            arSceneView.arSession  // Changed from arSceneView.session
        } catch (e: Exception) {
            Timber.e(e, "Error getting session: ${e.message}")
            null
        }
    }

    /**
     * Get current frame safely
     */
    private fun getCurrentFrame(): Frame? {
        return try {
            arSceneView.currentFrame?.frame  // Changed from arSceneView.arFrame?.frame
        } catch (e: Exception) {
            Timber.e(e, "Error getting frame: ${e.message}")
            null
        }
    }

    /**
     * Configure the AR session
     */
    private fun configureSession(session: Session) {
        Timber.d("AR Session created")

        try {
            // Configure session for environment tracking and depth
            session.configure(session.config.apply {
                // Enable depth if supported
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    depthMode = Config.DepthMode.AUTOMATIC
                }

                // Enable instant placement for better tracking
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            })

            isARActive = true

            // Connect to RTSP server on laptop using the stored coroutine scope
            connectRtspServer(coroutineScope)
        } catch (e: Exception) {
            Timber.e(e, "Error configuring session: ${e.message}")
            onARErrorListener?.invoke("Session configuration error: ${e.message}")
        }
    }

    /**
     * Connect to the RTSP server on laptop
     */
    private fun connectRtspServer(coroutineScope: CoroutineScope) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Timber.d("Connecting to RTSP server...")

                rtspClient.connect(
                    onConnected = {
                        isStreaming = true
                        startStreaming()
                        Timber.d("Connected to RTSP server")
                    },
                    onDisconnected = {
                        isStreaming = false
                        Timber.d("Disconnected from RTSP server")
                    },
                    onError = { error ->
                        Timber.e("RTSP connection error: $error")
                        onARErrorListener?.invoke("RTSP connection error: $error")
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to RTSP server")
                onARErrorListener?.invoke("Server connection failed: ${e.message}")
            }
        }
    }

    /**
     * Process AR frame and stream to laptop
     */
    private fun processArFrame(frame: Frame) {
        // Calculate FPS
        val currentTime = System.currentTimeMillis()
        frameCount++

        if (currentTime - lastFrameTime >= 1000) {
            fps = frameCount * 1000f / (currentTime - lastFrameTime)
            frameCount = 0
            lastFrameTime = currentTime
            Timber.d("Current FPS: $fps")
        }

        try {
            // Get camera image for RTSP stream
            val image = frame.acquireCameraImage()

            // Extract AR tracking data
            val trackingData = ArTrackingData(
                timestamp = currentTime,
                cameraIntrinsics = extractCameraIntrinsics(frame),
                trackingState = frame.camera.trackingState.name,
                cameraPose = convertPoseToFloatArray(frame.camera.pose),
                depthAvailable = frame.hasDisplayGeometryChanged(), // Alternative to acquiring depth image
                detectedPlanes = extractPlaneData(frame)
            )

            // Send frame to RTSP server
            rtspClient.sendFrame(image, trackingData)

            // Always close the image when done
            image.close()

        } catch (e: Exception) {
            Timber.e(e, "Error processing AR frame: ${e.message}")
        }
    }

    /**
     * Convert pose to float array
     */
    private fun convertPoseToFloatArray(pose: com.google.ar.core.Pose): FloatArray {
        val matrix = FloatArray(16)
        pose.toMatrix(matrix, 0)
        return matrix
    }

    /**
     * Extract camera intrinsics information
     */
    private fun extractCameraIntrinsics(frame: Frame): CameraIntrinsics {
        val camera = frame.camera
        val intrinsics = camera.getImageIntrinsics()

        return CameraIntrinsics(
            focalLength = floatArrayOf(intrinsics.focalLength[0], intrinsics.focalLength[1]),
            principalPoint = floatArrayOf(intrinsics.principalPoint[0], intrinsics.principalPoint[1]),
            imageSize = intArrayOf(intrinsics.imageDimensions[0], intrinsics.imageDimensions[1])
        )
    }

    /**
     * Extract detected planes information
     */
    private fun extractPlaneData(frame: Frame): List<PlaneData> {
        return frame.getUpdatedTrackables(Plane::class.java).map { plane ->
            PlaneData(
                id = plane.hashCode().toString(),
                center = plane.centerPose.translation,
                normal = plane.centerPose.zAxis,
                extent = floatArrayOf(plane.extentX, plane.extentZ),
                type = plane.type.name
            )
        }
    }

    /**
     * Send tap event to server
     */
    private fun sendTapEvent(hitResult: HitResult, plane: Plane) {
        try {
            val tapEvent = TapEvent(
                timestamp = System.currentTimeMillis(),
                hitPosition = hitResult.hitPose.translation,
                hitNormal = hitResult.hitPose.zAxis,
                planeId = plane.hashCode().toString()
            )

            rtspClient.sendTapEvent(tapEvent)
            Timber.d("Tap event sent to server")

        } catch (e: Exception) {
            Timber.e(e, "Error sending tap event: ${e.message}")
        }
    }

    // Data class definitions remain the same
    data class ArTrackingData(
        val timestamp: Long,
        val cameraIntrinsics: CameraIntrinsics,
        val trackingState: String,
        val cameraPose: FloatArray,
        val depthAvailable: Boolean,
        val detectedPlanes: List<PlaneData>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ArTrackingData

            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            return timestamp.hashCode()
        }
    }

    data class CameraIntrinsics(
        val focalLength: FloatArray,
        val principalPoint: FloatArray,
        val imageSize: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CameraIntrinsics

            if (!focalLength.contentEquals(other.focalLength)) return false
            if (!principalPoint.contentEquals(other.principalPoint)) return false
            if (!imageSize.contentEquals(other.imageSize)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = focalLength.contentHashCode()
            result = 31 * result + principalPoint.contentHashCode()
            result = 31 * result + imageSize.contentHashCode()
            return result
        }
    }

    data class PlaneData(
        val id: String,
        val center: FloatArray,
        val normal: FloatArray,
        val extent: FloatArray,
        val type: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PlaneData

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }
    }

    data class TapEvent(
        val timestamp: Long,
        val hitPosition: FloatArray,
        val hitNormal: FloatArray,
        val planeId: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TapEvent

            if (timestamp != other.timestamp) return false
            if (!hitPosition.contentEquals(other.hitPosition)) return false
            if (planeId != other.planeId) return false

            return true
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + hitPosition.contentHashCode()
            result = 31 * result + planeId.hashCode()
            return result
        }
    }

    /**
     * Start streaming AR data to laptop
     */
    fun startStreaming() {
        if (!isStreaming && isARActive) {
            rtspClient.startStream()
            isStreaming = true
            Timber.d("Started RTSP streaming")
        }
    }

    /**
     * Stop streaming AR data
     */
    fun stopStreaming() {
        if (isStreaming) {
            rtspClient.stopStream()
            isStreaming = false
            Timber.d("Stopped RTSP streaming")
        }
    }

    /**
     * Check if AR is currently active
     */
    fun isActive(): Boolean = isARActive

    /**
     * Check if currently streaming
     */
    fun isStreaming(): Boolean = isStreaming

    /**
     * Set a callback for AR errors
     */
    fun setOnARErrorListener(listener: (String) -> Unit) {
        this.onARErrorListener = listener
    }

    /**
     * Clean up resources when done
     */
    fun cleanup() {
        Timber.d("Cleaning up AR Renderer")
        stopStreaming()
        rtspClient.disconnect()
        isARActive = false
    }
}