package com.yourusername.arcoredetection.network

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.yourusername.arcoredetection.ARRenderer.ArTrackingData
import com.yourusername.arcoredetection.ARRenderer.TapEvent
import com.google.gson.Gson
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles RTSP streaming to laptop server with additional data channel for AR info
 */
class RtspClient(
    private val serverIp: String,
    private val rtspPort: Int = 8554,
    private val dataPort: Int = 8555,
    private val streamPath: String = "live"
) {
    // RTSP client for video streaming
    private val rtspClient = RtspClient(object : ConnectCheckerRtsp {
        override fun onAuthErrorRtsp() {
            Timber.e("RTSP authentication error")
            onErrorCallback?.invoke("RTSP authentication error")
        }

        override fun onAuthSuccessRtsp() {
            Timber.d("RTSP authentication success")
        }

        override fun onConnectionFailedRtsp(reason: String) {
            Timber.e("RTSP connection failed: $reason")
            isConnected = false
            onErrorCallback?.invoke("RTSP connection failed: $reason")
        }

        override fun onConnectionStartedRtsp(rtspUrl: String) {
            Timber.d("RTSP connection started to $rtspUrl")
        }

        override fun onConnectionSuccessRtsp() {
            Timber.d("RTSP connection success")
            isConnected = true
            onConnectedCallback?.invoke()
        }

        override fun onDisconnectRtsp() {
            Timber.d("RTSP disconnected")
            isConnected = false
            onDisconnectedCallback?.invoke()
        }

        override fun onNewBitrateRtsp(bitrate: Long) {
            Timber.d("RTSP new bitrate: $bitrate")
        }
    })

    // Data channel for AR tracking information
    private var dataSocket: Socket? = null
    private var dataOutputStream: BufferedOutputStream? = null

    // Connection state
    private var isConnected = false
    private var isStreaming = AtomicBoolean(false)

    // Callbacks
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    // Video encoder
    private var videoEncoder: MediaCodec? = null
    private val videoBufferInfo = MediaCodec.BufferInfo()

    // Gson for JSON serialization
    private val gson = Gson()

    /**
     * Connect to the RTSP server
     */
    suspend fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            onConnectedCallback = onConnected
            onDisconnectedCallback = onDisconnected
            onErrorCallback = onError

            try {
                // Configure RTSP client
                rtspClient.setProtocol(Protocol.TCP)

                // Connect data channel for AR tracking info
                connectDataChannel()

                // Note: We don't actually connect the RTSP client here,
                // that happens when we start streaming

            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to server")
                onError("Failed to connect: ${e.message}")
            }
        }
    }

    /**
     * Connect the data channel for AR tracking information
     */
    private fun connectDataChannel() {
        try {
            // Create socket and output stream
            dataSocket = Socket(serverIp, dataPort)
            dataOutputStream = BufferedOutputStream(dataSocket!!.getOutputStream())

            Timber.d("Data channel connected to $serverIp:$dataPort")

        } catch (e: IOException) {
            Timber.e(e, "Failed to connect data channel")
            throw e
        }
    }

    /**
     * Initialize video encoder for RTSP streaming
     */
    private fun initVideoEncoder(width: Int, height: Int) {
        try {
            // Get video encoder
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // Configure video format (H.264)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)

            // Set encoding parameters
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, calculateBitrate(width, height))
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // Keyframe every 2 seconds

            // Configure encoder
            videoEncoder?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoEncoder?.start()

            Timber.d("Video encoder initialized: ${width}x${height}")

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize video encoder")
            onErrorCallback?.invoke("Failed to initialize video encoder: ${e.message}")
        }
    }

    /**
     * Calculate appropriate bitrate based on resolution
     */
    private fun calculateBitrate(width: Int, height: Int): Int {
        // Base bitrate on resolution (0.1 bits per pixel at 30fps is a good starting point)
        return (width * height * 30 * 0.1).toInt()
    }

    /**
     * Start RTSP stream
     */
    fun startStream() {
        if (isStreaming.get()) return

        try {
            // Create RTSP URL
            val rtspUrl = "rtsp://$serverIp:$rtspPort/$streamPath"

            // Start RTSP streaming
            rtspClient.connect(rtspUrl)

            isStreaming.set(true)
            Timber.d("RTSP stream started to $rtspUrl")

        } catch (e: Exception) {
            Timber.e(e, "Failed to start RTSP stream")
            onErrorCallback?.invoke("Failed to start streaming: ${e.message}")
        }
    }

    /**
     * Stop RTSP stream
     */
    fun stopStream() {
        if (!isStreaming.get()) return

        try {
            // Stop RTSP streaming
            rtspClient.disconnect()

            // Release video encoder
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null

            isStreaming.set(false)
            Timber.d("RTSP stream stopped")

        } catch (e: Exception) {
            Timber.e(e, "Error stopping RTSP stream")
        }
    }

    /**
     * Disconnect from server
     */
    fun disconnect() {
        // Stop streaming if active
        if (isStreaming.get()) {
            stopStream()
        }

        // Close data channel
        try {
            dataOutputStream?.close()
            dataSocket?.close()
            dataOutputStream = null
            dataSocket = null
        } catch (e: Exception) {
            Timber.e(e, "Error closing data connection")
        }

        isConnected = false
        onDisconnectedCallback?.invoke()

        Timber.d("Disconnected from server")
    }

    /**
     * Send camera frame and AR tracking data to server
     */
    fun sendFrame(image: Image, trackingData: ArTrackingData) {
        if (!isStreaming.get() || !isConnected) return

        try {
            // Initialize encoder if needed
            if (videoEncoder == null) {
                initVideoEncoder(image.width, image.height)
            }

            // Encode and send video frame via RTSP
            encodeAndSendFrame(image)

            // Send AR tracking data via separate data channel
            sendTrackingData(trackingData)

        } catch (e: Exception) {
            Timber.e(e, "Error sending frame")
        }
    }

    /**
     * Encode and send video frame via RTSP
     */
    private fun encodeAndSendFrame(image: Image) {
        // This is a simplified version - actual implementation would
        // handle YUV to encoder input buffer conversion

        try {
            // Get a buffer from the encoder
            val inputBufferIndex = videoEncoder?.dequeueInputBuffer(0) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = videoEncoder?.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()

                // Fill the buffer with the image data
                // (actual implementation would convert YUV420 from camera to the encoder format)
                fillInputBuffer(inputBuffer, image)

                // Queue the buffer
                videoEncoder?.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    inputBuffer?.capacity() ?: 0,
                    System.nanoTime() / 1000,
                    0
                )
            }

            // Get the encoded data from the encoder
            var outputBufferIndex = videoEncoder?.dequeueOutputBuffer(videoBufferInfo, 0) ?: -1
            while (outputBufferIndex >= 0) {
                val outputBuffer = videoEncoder?.getOutputBuffer(outputBufferIndex)

                // Send encoded frame to RTSP client
                if (outputBuffer != null) {
                    rtspClient.sendVideo(outputBuffer, videoBufferInfo)
                }

                // Release the buffer
                videoEncoder?.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = videoEncoder?.dequeueOutputBuffer(videoBufferInfo, 0) ?: -1
            }

        } catch (e: Exception) {
            Timber.e(e, "Error encoding video frame")
        }
    }

    /**
     * Fill encoder input buffer with image data
     * This is a placeholder - actual implementation would depend on the specific image format
     */
    private fun fillInputBuffer(buffer: ByteBuffer?, image: Image) {
        // This is a simplified example - actual implementation would convert
        // YUV420 from camera to the encoder format correctly

        // Get the YUV planes
        val planes = image.planes

        // Get the buffer sizes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        // Copy Y plane
        val ySize = yBuffer.remaining()
        buffer?.put(yBuffer)

        // Copy U and V planes
        // Note: This is a simplified approach and might not be correct for all YUV formats
        val uSize = uBuffer.remaining()
        buffer?.put(uBuffer)

        val vSize = vBuffer.remaining()
        buffer?.put(vBuffer)

        // Rewind the buffer
        buffer?.rewind()
    }

    /**
     * Send AR tracking data via data channel
     */
    private fun sendTrackingData(trackingData: ArTrackingData) {
        try {
            // Check if data channel is connected
            if (dataOutputStream == null) {
                Timber.w("Data channel not connected")
                return
            }

            // Convert tracking data to JSON
            val json = gson.toJson(trackingData)

            // Add message type and length prefix
            val message = "ARDATA ${json.length}\n$json"

            // Send data
            dataOutputStream?.write(message.toByteArray())
            dataOutputStream?.flush()

        } catch (e: Exception) {
            Timber.e(e, "Error sending AR tracking data")

            // Try to reconnect data channel if needed
            if (e is IOException) {
                try {
                    connectDataChannel()
                } catch (reconnectEx: Exception) {
                    Timber.e(reconnectEx, "Failed to reconnect data channel")
                }
            }
        }
    }

    /**
     * Send tap event to server
     */
    fun sendTapEvent(tapEvent: TapEvent) {
        try {
            // Check if data channel is connected
            if (dataOutputStream == null) {
                Timber.w("Data channel not connected")
                return
            }

            // Convert tap event to JSON
            val json = gson.toJson(tapEvent)

            // Add message type and length prefix
            val message = "ARTAP ${json.length}\n$json"

            // Send data
            dataOutputStream?.write(message.toByteArray())
            dataOutputStream?.flush()

            Timber.d("Tap event sent")

        } catch (e: Exception) {
            Timber.e(e, "Error sending tap event")
        }
    }

    /**
     * Check if connected to server
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Check if streaming
     */
    fun isStreaming(): Boolean = isStreaming.get()
}