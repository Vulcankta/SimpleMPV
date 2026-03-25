package com.simplempv.player

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Video information data class.
 */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val codec: String,
    val bitrate: Int
)

/**
 * MPV player controller implementation using libmpv.
 * Matches the SimpleVLC PlayerController API for compatibility.
 */
class MpvPlayerController(private val context: Context) : MPVLib.EventObserver {

    /**
     * Callback interface for UI updates from the player.
     */
    interface Callback {
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onProgressUpdate(position: Float, time: Long, length: Long)
        fun onError(errorMessage: String)
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    private val callbackRef = AtomicReference<Callback?>(null)
    private var currentVideoKey: String? = null
    private var currentSpeed: Float = 1.0f
    private var savedPositions: MutableMap<String, Long> = mutableMapOf()
    private var currentFd: Int = -1
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var originalContentUri: Uri? = null
    private var useHardwareDecoding: Boolean = false
    private var seekErrorCount: Int = 0
    private val seekErrorThreshold: Int = 2

    /**
     * Set the callback for player events.
     */
    fun setCallback(callback: Callback?) {
        callbackRef.set(callback)
    }

    private fun getCallback(): Callback? = callbackRef.get()

    /**
     * Set hardware decoding preference
     * @param useHardware true for hardware decoding, false for software
     */
    fun setHardwareDecoding(useHardware: Boolean) {
        this.useHardwareDecoding = useHardware
    }
    
    /**
     * Set the video file path for screenshot capture
     * @param path Absolute path to the video file
     */
    fun setVideoPath(path: String?) {
        this.currentVideoPath = path
    }

    /**
     * Check if using hardware decoding
     */
    fun isUsingHardwareDecoding(): Boolean = useHardwareDecoding

    /**
     * Initialize the MPV player instance.
     * @return true if initialization succeeded, false otherwise
     */
    fun setupPlayer(): Boolean {
        return try {
            // Initialize MPVLib with context
            MPVLib.create(context)
            
            // Apply decoding mode based on auto-detection
            if (useHardwareDecoding) {
                MPVLib.setOptionString("hwdec", "mediacodec")
            } else {
                MPVLib.setOptionString("hwdec", "no")
            }
            
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("video-zoom", "0")
            MPVLib.setOptionString("video-pan-x", "0")
            MPVLib.setOptionString("video-pan-y", "0")
            
            // Additional stability options
            MPVLib.setOptionString("gapless-audio", "yes")
            MPVLib.setOptionString("fbo-format", "auto")
            
            // Screenshot directory - use app's private directory for reliability
            val screenshotDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
            if (!screenshotDir.exists()) {
                screenshotDir.mkdirs()
            }
            MPVLib.setOptionString("screenshot-directory", screenshotDir.absolutePath)
            MPVLib.setOptionString("screenshot-format", "jpg")
            MPVLib.setOptionString("screenshot-template", "mpv-shot-%f-%p")
            
            MPVLib.init()
            
            // Add observer for events
            MPVLib.addObserver(this)
            
            // Observe time position and duration for progress updates (format 5 = MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("time-pos", 5)
            MPVLib.observeProperty("duration/full", 5)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Report a seek error (call this when seek causes visual artifacts/freeze)
     * After threshold errors, automatically switch to software decoding
     */
    fun reportSeekError() {
        seekErrorCount++
        if (seekErrorCount >= seekErrorThreshold && useHardwareDecoding) {
            // Automatically switch to software decoding
            switchToSoftwareDecoding()
        }
    }

    /**
     * Switch to software decoding (called automatically or manually)
     */
    private fun switchToSoftwareDecoding() {
        try {
            MPVLib.setOptionString("hwdec", "no")
            useHardwareDecoding = false
            getCallback()?.onError("Hardware decoding unstable, switched to software decoding")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var currentPlayingUri: Uri? = null
    private var currentVideoPath: String? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private var currentVideoSurface: SurfaceView? = null

    /**
     * Release resources and close file descriptors.
     */
    fun release() {
        try {
            closeFd()
            MPVLib.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restart player with new settings (e.g., after changing decoding mode)
     * This will restart playback from the beginning - use switchDecodingMode for seamless switching
     * @return true if restart succeeded
     */
    fun restart(): Boolean {
        release()
        return setupPlayer()
    }

    /**
     * Switch decoding mode while keeping video playing
     * Saves position, restarts with new hwdec setting, and resumes playback
     * @param uri The current video URI to replay
     * @param surfaceHolder The surface holder to attach
     * @param videoSurface The surface view
     * @param savedPosition The position to seek to after restart
     * @return true if switch succeeded
     */
    fun switchDecodingMode(uri: Uri, surfaceHolder: SurfaceHolder, videoSurface: SurfaceView, savedPosition: Long): Boolean {
        try {
            // Release current player
            release()
            
            // Reinitialize with new settings (hwdec already set via setHardwareDecoding)
            if (!setupPlayer()) {
                return false
            }
            
            // Attach surface
            MPVLib.attachSurface(surfaceHolder.surface)
            
            // Start playback
            MPVLib.command(arrayOf("loadfile", uri.toString()))
            
            // Seek to saved position after a short delay to allow video to load
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    seekTo(savedPosition)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 500)
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Cycle through decoding modes without restarting playback.
     * Uses mpv's cycle-values command to toggle between hardware and software decoding.
     * This is the preferred method for switching decoders as it doesn't interrupt playback.
     */
    fun cycleDecodingMode() {
        try {
            MPVLib.command(arrayOf("cycle-values", "hwdec", "mediacodec,mediacodec-copy", "no"))
            useHardwareDecoding = !useHardwareDecoding
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, duration).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show toast", e)
        }
    }

    fun takeScreenshot(): Boolean {
        return try {
            val videoPath = currentVideoPath
            android.util.Log.d(TAG, "takeScreenshot: videoPath = $videoPath")
            
            if (videoPath.isNullOrEmpty()) {
                showToast("Screenshot failed: no video path")
                return false
            }
            
            val retriever = android.media.MediaMetadataRetriever()
            try {
                android.util.Log.d(TAG, "Setting data source: $videoPath")
                retriever.setDataSource(videoPath)
                
                val currentPosition = getCurrentTime()
                android.util.Log.d(TAG, "Current position: $currentPosition ms")
                
                var bitmap = retriever.getFrameAtTime(currentPosition * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                
                if (bitmap == null) {
                    bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                
                if (bitmap != null) {
                    android.util.Log.d(TAG, "Got bitmap: ${bitmap.width}x${bitmap.height}")
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val screenshotDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
                    if (!screenshotDir.exists()) {
                        screenshotDir.mkdirs()
                    }
                    val filename = "SimpleMPV_$timestamp.jpg"
                    val screenshotFile = File(screenshotDir, filename)
                    
                    screenshotFile.outputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    bitmap.recycle()
                    
                    android.util.Log.d(TAG, "Screenshot saved: ${screenshotFile.absolutePath}")
                    showToast("Screenshot saved: ${screenshotFile.name}")
                    scanFileToGallery(screenshotFile)
                    true
                } else {
                    android.util.Log.e(TAG, "getFrameAtTime returned null")
                    showToast("Screenshot failed: cannot capture frame")
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Screenshot error", e)
                showToast("Screenshot error: ${e.message}")
                false
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Screenshot error: ${e.message}")
            false
        }
    }
    
    private fun scanFileToGallery(file: File) {
        try {
            val paths = arrayOf(file.absolutePath)
            MediaScannerConnection.scanFile(context, paths, arrayOf("image/jpeg")) { path, uri ->
                android.util.Log.d(TAG, "Screenshot scanned to gallery: $path -> $uri")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to scan screenshot to gallery", e)
        }
    }

    /**
     * Attach surface to player and start playback of the given URI.
     * @param uri Media URI (fd:// or file://)
     * @param surfaceHolder SurfaceHolder to attach
     * @param videoSurface The SurfaceView for video display
     * @param videoKey Optional video key to look up saved position. If null, uses currentVideoKey.
     */
    fun attachAndPlay(uri: Uri, surfaceHolder: SurfaceHolder, videoSurface: SurfaceView, videoKey: String?) {
        val key = videoKey ?: currentVideoKey
        setCurrentVideoKey(key)
        currentPlayingUri = uri

        try {
            val surface = surfaceHolder.surface
            if (surface.isValid) {
                MPVLib.attachSurface(surface)
            }

            val uriString = uri.toString()
            MPVLib.command(arrayOf("loadfile", uriString))

            // Restore saved position if available
            key?.let {
                val position = getSavedPosition(it)
                if (position > 0) {
                    MPVLib.command(arrayOf("seek", position.toString(), "absolute"))
                }
            }
        } catch (e: Exception) {
            getCallback()?.onError("Failed to play: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Called when surface size changes (e.g., orientation change).
     * @param width New surface width
     * @param height New surface height
     */
    fun onSurfaceSizeChanged(width: Int, height: Int) {
        // MPVLib handles surface changes automatically
    }

    /**
     * Open a file descriptor for the given content URI and return a fd:// URI for playback.
     * @return fd:// URI or null if failed
     */
    fun openFdUri(contentUri: Uri): Uri? {
        originalContentUri = contentUri
        return try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(contentUri, "r")
            if (parcelFileDescriptor != null) {
                currentFd = parcelFileDescriptor!!.fd
                Uri.parse("fd://$currentFd")
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Convert file path to URI.
     */
    fun filePathToUri(filePath: String): Uri {
        return Uri.parse(filePath)
    }

    /**
     * Close the current file descriptor.
     */
    fun closeFd() {
        if (currentFd != -1) {
            try {
                parcelFileDescriptor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            parcelFileDescriptor = null
            currentFd = -1
        }
    }

    fun reopenFd(): Uri? {
        return originalContentUri?.let { openFdUri(it) }
    }

    /**
     * Set saved playback position for the current video.
     */
    fun setSavedPosition(position: Long) {
        currentVideoKey?.let {
            savedPositions[it] = position
        }
    }

    /**
     * Get saved playback position for a specific video key.
     */
    fun getSavedPosition(key: String): Long {
        return savedPositions[key] ?: 0L
    }

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentTime(): Long {
        return try {
            val time = MPVLib.getPropertyDouble("time-pos")
            // mpv returns time in seconds, convert to milliseconds
            ((time ?: 0.0) * 1000).toLong()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total media length in milliseconds.
     */
    fun getLength(): Long {
        return try {
            val duration = MPVLib.getPropertyDouble("duration/full")
            // mpv returns duration in seconds, convert to milliseconds
            ((duration ?: 0.0) * 1000).toLong()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Check if player is currently playing.
     */
    fun isPlaying(): Boolean {
        return try {
            val paused = MPVLib.getPropertyBoolean("pause")
            !(paused ?: true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start or resume playback.
     */
    fun play() {
        try {
            MPVLib.setPropertyBoolean("pause", false)
            getCallback()?.onPlaybackStateChanged(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        try {
            MPVLib.setPropertyBoolean("pause", true)
            getCallback()?.onPlaybackStateChanged(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stop playback.
     */
    fun stop() {
        try {
            MPVLib.command(arrayOf("stop"))
            getCallback()?.onPlaybackStateChanged(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Toggle play/pause state.
     */
    fun togglePlayPause() {
        try {
            val playing = isPlaying()
            MPVLib.command(arrayOf("cycle", "pause"))
            getCallback()?.onPlaybackStateChanged(!playing)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Seek to specified time in milliseconds.
     */
    fun seekTo(time: Long) {
        try {
            // Convert milliseconds to seconds for mpv
            val timeSeconds = time / 1000.0
            MPVLib.command(arrayOf("seek", timeSeconds.toString(), "absolute"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Set video key for saved position tracking.
     */
    fun setCurrentVideoKey(key: String?) {
        currentVideoKey = key
    }

    /**
     * Get current video key.
     */
    fun getCurrentVideoKey(): String? {
        return currentVideoKey
    }

    /**
     * Set playback speed.
     * @param speed Speed multiplier (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x)
     */
    fun setPlaybackSpeed(speed: Float) {
        try {
            MPVLib.setPropertyDouble("speed", speed.toDouble())
            currentSpeed = speed
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get current playback speed.
     * @return Current speed multiplier
     */
    fun getPlaybackSpeed(): Float {
        return currentSpeed
    }

    /**
     * Get current video information.
     * @return VideoInfo or null if not available
     */
    fun getVideoInfo(): VideoInfo? {
        return try {
            val width = MPVLib.getPropertyInt("video-params/w") ?: 0
            val height = MPVLib.getPropertyInt("video-params/h") ?: 0
            VideoInfo(
                width = width,
                height = height,
                frameRate = 30,
                codec = "unknown",
                bitrate = 0
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get formatted video info string for display.
     */
    fun getVideoInfoString(): String {
        val info = getVideoInfo() ?: return "No video info"
        return "${info.width}x${info.height} @ ${info.frameRate}fps"
    }

    override fun event(eventId: Int) {
        when (eventId) {
            6 /* MPV_EVENT_START_FILE */ -> {
                getCallback()?.onPlaybackStateChanged(true)
            }
            7 /* MPV_EVENT_END_FILE */ -> {
                getCallback()?.onPlaybackStateChanged(false)
            }
            8 /* MPV_EVENT_FILE_LOADED */ -> {
                val info = getVideoInfo()
                info?.let {
                    getCallback()?.onVideoSizeChanged(it.width, it.height)
                }
            }
        }
    }

    override fun eventProperty(property: String) {
        // Not used
    }

    override fun eventProperty(property: String, value: Long) {
        // Not used
    }

    private var videoDuration: Long = 0L
    
    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                val timeMs = (value * 1000).toLong()
                val length = if (videoDuration > 0) videoDuration else getLength()
                if (length > 0) {
                    getCallback()?.onProgressUpdate((timeMs.toFloat() / length.toFloat()), timeMs, length)
                }
            }
            "duration/full" -> {
                videoDuration = (value * 1000).toLong()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                getCallback()?.onPlaybackStateChanged(!value)
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        // Not used
    }

    companion object {
        private const val TAG = "MpvPlayerController"
        val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
