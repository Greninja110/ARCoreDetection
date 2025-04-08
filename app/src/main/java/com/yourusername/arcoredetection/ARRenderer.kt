package com.yourusername.arcoredetection

import android.content.Context
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
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

    /**
     * Initialize the AR environment and RTSP connection
     */
    fun initialize(coroutineScope: CoroutineScope) {
        Timber.d("Initializing AR environment")

        try {
            // Set up session callback
            arSceneView.onSessionCreated = { session ->
                Timber.d("AR Session created")

                // Configure session for environment tracking and depth
                session.configure(session.config.apply {
                    // Enable depth if supported
                    if (session.isDepthModeSupported(com.google.ar.core.Config.DepthMode.AUTOMATIC)) {
                        this.depthMode = com.google.ar.core.Config.DepthMode.AUTOMATIC
                    }

                    // Enable instant placement for better tracking
                    this.instantPlacementMode = com.google.ar.core.Config.InstantPlacementMode.LOCAL_Y_UP
                })

                isARActive = true

                // Connect to RTSP server on laptop
                connectRtspServer(coroutineScope)
            }

            // Setup tap handling to send tap events to server
            arSceneView.onTapPlane = { hitResult, plane, _ ->
                if (isStreaming) {
                    sendTapEvent(hitResult, plane)
                }
                true
            }

            // Set up frame listener to stream camera and AR data
            arSceneView.onFrame = { arFrame ->
                if (isStreaming) {
                    processArFrame(arFrame)
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AR environment")
            onARErrorListener?.invoke("Failed to initialize AR: ${e.message}")
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
                cameraPose = frame.camera.pose.toMatrix(),
                depthAvailable = frame.acquireDepthImage() != null,
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

    /**
     * Data classes for streaming
     */
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
}