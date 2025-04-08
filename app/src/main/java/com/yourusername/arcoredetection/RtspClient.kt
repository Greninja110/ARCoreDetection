package com.yourusername.arcoredetection

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import com.yourusername.arcoredetection.models.DetectedObject
import com.yourusername.arcoredetection.models.DetectionResult
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client to handle RTSP streaming and communication with the detection server
 */
class RtspClient(private val context: Context) {

    // Server configuration
    private var serverUrl: String = "http://192.168.1.100:5000" // Default, should be configurable
    private var rtspUrl: String = "rtsp://192.168.1.100:8554/live" // Default RTSP URL

    // ExoPlayer for RTSP playback
    private var exoPlayer: ExoPlayer? = null

    // Connection state
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected

    // Streaming state
    private val _isStreaming = MutableLiveData<Boolean>(false)
    val isStreaming: LiveData<Boolean> = _isStreaming

    // Detection results
    private val _detectionResults = MutableLiveData<DetectionResult>()
    val detectionResults: LiveData<DetectionResult> = _detectionResults

    // Stats
    private val _fps = MutableLiveData<Float>(0f)
    val fps: LiveData<Float> = _fps

    // Error state
    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    // Background job for fetching detections
    private var detectionJob: Job? = null
    private val isPolling = AtomicBoolean(false)

    // API service for communicating with the server
    private lateinit var apiService: DetectionApiService

    /**
     * Configure the client with server URLs
     */
    fun configure(serverUrl: String, rtspUrl: String) {
        this.serverUrl = serverUrl
        this.rtspUrl = rtspUrl

        // Create Retrofit instance with new server URL
        val retrofit = Retrofit.Builder()
            .baseUrl(serverUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(DetectionApiService::class.java)

        Timber.d("Configured with server: $serverUrl, RTSP: $rtspUrl")
    }

    /**
     * Initialize and prepare the ExoPlayer for RTSP streaming
     */
    fun initializePlayer(onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null): ExoPlayer {
        Timber.d("Initializing ExoPlayer for RTSP")

        // Release existing player if any
        releasePlayer()

        // Create a new ExoPlayer instance
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            // Add a listener to handle player events
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            Timber.d("Player ready, starting playback")
                            play()
                            _isStreaming.postValue(true)
                        }
                        Player.STATE_ENDED -> {
                            Timber.d("Playback ended")
                            _isStreaming.postValue(false)
                        }
                        Player.STATE_BUFFERING -> {
                            Timber.d("Buffering...")
                        }
                        Player.STATE_IDLE -> {
                            Timber.d("Player idle")
                            _isStreaming.postValue(false)
                        }
                    }
                }

                // Updated to use the newer VideoSize class
                override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
                    Timber.d("Video size changed: ${videoSize.width} x ${videoSize.height}")
                    onVideoSizeChanged?.invoke(videoSize.width, videoSize.height)
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    Timber.e("Player error: ${error.message}")
                    _error.postValue("Streaming error: ${error.message}")
                    _isStreaming.postValue(false)
                }
            })

            // Set repeat mode to continuously stream
            repeatMode = Player.REPEAT_MODE_OFF

            // Prepare the RTSP media source
            try {
                val rtspUri = Uri.parse(rtspUrl)
                val mediaItem = MediaItem.fromUri(rtspUri)
                val rtspMediaSource = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true) // Force TCP for more reliable streaming
                    .createMediaSource(mediaItem)

                setMediaSource(rtspMediaSource)
                prepare()

                _isConnected.postValue(true)

                Timber.d("Player prepared with RTSP source: $rtspUrl")
            } catch (e: Exception) {
                Timber.e(e, "Failed to prepare RTSP source")
                _error.postValue("Failed to prepare RTSP stream: ${e.message}")
                _isConnected.postValue(false)
            }
        }

        return exoPlayer!!
    }

    /**
     * Release the ExoPlayer resources
     */
    fun releasePlayer() {
        exoPlayer?.let {
            Timber.d("Releasing player")
            it.stop()
            it.release()
        }
        exoPlayer = null
        _isStreaming.postValue(false)
    }

    /**
     * Start streaming from the RTSP server
     */
    fun startStreaming() {
        exoPlayer?.let {
            Timber.d("Starting stream playback")
            it.play()
        } ?: run {
            Timber.e("Cannot start streaming: Player not initialized")
            _error.postValue("Player not initialized")
        }
    }

    /**
     * Stop streaming from the RTSP server
     */
    fun stopStreaming() {
        exoPlayer?.let {
            Timber.d("Stopping stream playback")
            it.pause()
        }
    }

    /**
     * Start polling for detection results from the server
     */
    fun startDetectionPolling(coroutineScope: CoroutineScope) {
        if (isPolling.compareAndSet(false, true)) {
            Timber.d("Starting detection polling")

            detectionJob = coroutineScope.launch {
                while (isActive && isPolling.get()) {
                    try {
                        // Fetch detection results
                        val objects = apiService.getDetectedObjects()

                        // Convert to app model
                        val detectedObjects = objects.map { DetectedObject.fromJson(it) }

                        // Update LiveData
                        _detectionResults.postValue(DetectionResult(detectedObjects))

                        // Fetch stats
                        val stats = apiService.getStats()
                        stats["fps"]?.let { fpsStr ->
                            try {
                                val fpsValue = fpsStr.toFloat()
                                _fps.postValue(fpsValue)
                            } catch (e: NumberFormatException) {
                                Timber.e("Failed to parse FPS: $fpsStr")
                            }
                        }

                        // No errors, clear any previous error
                        _error.postValue(null)
                        _isConnected.postValue(true)
                    } catch (e: Exception) {
                        when (e) {
                            is IOException -> {
                                Timber.e(e, "Network error while polling")
                                _error.postValue("Network error: ${e.message}")
                                _isConnected.postValue(false)
                            }
                            else -> {
                                Timber.e(e, "Error while polling for detections")
                                _error.postValue("Error: ${e.message}")
                            }
                        }
                    }

                    // Poll every 500ms
                    delay(500)
                }
            }
        }
    }

    /**
     * Stop polling for detection results
     */
    fun stopDetectionPolling() {
        if (isPolling.compareAndSet(true, false)) {
            Timber.d("Stopping detection polling")
            detectionJob?.cancel()
            detectionJob = null
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        Timber.d("Cleaning up RTSP client")
        stopDetectionPolling()
        releasePlayer()
    }

    /**
     * Retrofit interface for the detection server API
     */
    interface DetectionApiService {
        @GET("/detected_objects")
        suspend fun getDetectedObjects(): List<Map<String, Any>>

        @GET("/stats")
        suspend fun getStats(): Map<String, String>
    }
}