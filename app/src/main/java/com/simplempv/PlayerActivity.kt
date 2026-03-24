package com.simplempv

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.simplempv.databinding.ActivityPlayerBinding
import com.simplempv.databinding.BottomSheetControlsBinding
import com.simplempv.databinding.PipMiniControlsBinding
import com.simplempv.model.Video
import com.simplempv.player.MpvPlayerController
import com.simplempv.player.PlaybackStateManager
import com.simplempv.player.ProgressUpdater
import com.simplempv.repository.BookmarkRepository
import com.simplempv.repository.VideoRepository
import com.simplempv.service.PlaybackService
import com.simplempv.service.SleepTimerManager
import com.simplempv.ui.quicklist.QuickListFragment
import java.io.File
import kotlin.math.abs

class PlayerActivity : AppCompatActivity(),
    MpvPlayerController.Callback,
    QuickListFragment.OnVideoSelectedListener {

    private lateinit var playerController: MpvPlayerController
    private lateinit var playbackStateManager: PlaybackStateManager
    private var videoRepository: VideoRepository? = null
    private lateinit var bookmarkRepository: BookmarkRepository
    private lateinit var binding: ActivityPlayerBinding
    private var playbackService: PlaybackService? = null
    private var serviceBound = false
    private var currentVideoTitle: String = "Unknown"

    private var videoList: List<Video> = emptyList()
    private var currentPosition: Int = 0

    private lateinit var surfaceView: SurfaceView
    private lateinit var seekBar: SeekBar
    private lateinit var textViewCurrentTime: TextView
    private lateinit var textViewTotalTime: TextView
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonPrevious: ImageButton
    private lateinit var buttonNext: ImageButton
    private lateinit var buttonOpenPanel: ImageButton
    private lateinit var buttonSpeed: ImageButton
    private lateinit var buttonSnapshot: ImageButton
    private lateinit var buttonInfo: ImageButton
    private lateinit var buttonDecoding: ImageButton
    private lateinit var textViewDebug: TextView
    private lateinit var slidingPaneLayout: SlidingPaneLayout
    private lateinit var quickListFragment: QuickListFragment

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private lateinit var progressUpdater: ProgressUpdater
    private var pendingFdUri: Uri? = null

    private lateinit var gestureDetector: GestureDetector
    private lateinit var doubleTapGestureDetector: GestureDetector
    private var audioManager: AudioManager? = null
    private var screenBrightness: Float = 0.5f
    private var currentVolume: Int = 0
    private var maxVolume: Int = 0
    private var isBrightnessControl: Boolean = false
    private var gestureStartX: Float = 0f
    private var gestureStartY: Float = 0f
    private var isGestureActive: Boolean = false
    private val touchSlop: Int by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    private var lastTouchY: Float = 0f
    private val gestureSensitivity: Float = 0.002f

    private var pipParams: PictureInPictureParams? = null
    private var isInPipMode = false

    private var isRotationLocked = false
    private lateinit var buttonLock: ImageButton
    private lateinit var buttonSleepTimer: ImageButton
    private lateinit var buttonBookmark: ImageButton
    private lateinit var buttonMore: ImageButton
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var bottomSheetControlsBinding: BottomSheetControlsBinding
    private lateinit var pipControlsBinding: PipMiniControlsBinding
    private var sleepTimerMinutes = 0

    private var wasInBackground = false
    private var savedPosition: Long = 0
    private var wasConfigurationChange = false
    private var originalContentUri: Uri? = null
    private var isBottomSheetExpanded = false
    private var pendingSeekPosition: Long = -1
    
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            android.util.Log.d("PlayerActivity", "surfaceCreated: wasInBackground=$wasInBackground")
            
            if (!::playerController.isInitialized || !::progressUpdater.isInitialized) return

            if (wasInBackground || wasConfigurationChange) {
                android.util.Log.d("PlayerActivity", "Reattaching surface after background or config change: wasInBackground=$wasInBackground, wasConfigChange=$wasConfigurationChange")
                
                try {
                    dev.jdtech.mpv.MPVLib.attachSurface(holder.surface)
                    dev.jdtech.mpv.MPVLib.setOptionString("android-surface-size", "${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
                    dev.jdtech.mpv.MPVLib.setOptionString("force-window", "yes")
                    dev.jdtech.mpv.MPVLib.setPropertyString("vo", "gpu")
                    
                    val fdUri = playerController.reopenFd()
                    android.util.Log.d("PlayerActivity", "Reopened fdUri: $fdUri")
                    
                    if (fdUri != null) {
                        dev.jdtech.mpv.MPVLib.command(arrayOf("loadfile", fdUri.toString()))
                        pendingSeekPosition = savedPosition
                    }
                    
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Failed to reattach surface", e)
                }
                
                surfaceView.postDelayed({
                    if (pendingSeekPosition > 0) {
                        playerController.seekTo(pendingSeekPosition)
                        surfaceView.postDelayed({
                            val actualPosition = playerController.getCurrentTime()
                            if (kotlin.math.abs(actualPosition - pendingSeekPosition) > 1000) {
                                android.util.Log.d("PlayerActivity", "Seek may have failed, retrying. Expected: $pendingSeekPosition, Actual: $actualPosition")
                                playerController.seekTo(pendingSeekPosition)
                            }
                            pendingSeekPosition = -1
                        }, 500)
                    }
                    playerController.play()
                    android.util.Log.d("PlayerActivity", "Video resumed at position: ${playerController.getCurrentTime()}")
                }, 1000)
                
                wasInBackground = false
                wasConfigurationChange = false
            } else {
                pendingFdUri?.let { uri ->
                    playerController.attachAndPlay(uri, holder, surfaceView, playbackStateManager.getCurrentVideoKey())
                    progressUpdater.start()
                    pendingFdUri = null
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            android.util.Log.d("PlayerActivity", "surfaceChanged: ${width}x${height}")
            try {
                dev.jdtech.mpv.MPVLib.setOptionString("android-surface-size", "${width}x${height}")
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error setting surface size", e)
            }
            if (::playerController.isInitialized) {
                playerController.onSurfaceSizeChanged(width, height)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (::playerController.isInitialized) {
                savedPosition = playerController.getCurrentTime()
                playerController.pause()
                
                try {
                    dev.jdtech.mpv.MPVLib.setPropertyString("vo", "null")
                    dev.jdtech.mpv.MPVLib.setOptionString("force-window", "no")
                    dev.jdtech.mpv.MPVLib.detachSurface()
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Error detaching surface", e)
                }
            }
        }
    }

    private fun hasVideoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            gestureStartX = e.x
            gestureStartY = e.y
            lastTouchY = e.y
            isGestureActive = false
            val screenWidth = surfaceView.width
            isBrightnessControl = gestureStartX < screenWidth / 2
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (abs(distanceY) > abs(distanceX)) {
                if (!isGestureActive) {
                    val deltaY = e2.y - gestureStartY
                    if (abs(deltaY) > touchSlop) {
                        isGestureActive = true
                    } else {
                        return false
                    }
                }
                val deltaY = lastTouchY - e2.y
                lastTouchY = e2.y

                val normalizedDelta = deltaY * gestureSensitivity

                if (isBrightnessControl) {
                    adjustBrightness(normalizedDelta)
                } else {
                    adjustVolume(normalizedDelta)
                }
                return true
            }
            return false
        }
    }

    private val doubleTapListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            handleDoubleTap(e.x)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    }

    private fun handleDoubleTap(x: Float) {
        val screenWidth = surfaceView.width
        val seekAmount = 10_000L

        when {
            x < screenWidth / 3 -> {
                val newTime = (playerController.getCurrentTime() - seekAmount).coerceAtLeast(0)
                playerController.seekTo(newTime)
                showSeekFeedback("-10s")
            }
            x > screenWidth * 2 / 3 -> {
                val newTime = (playerController.getCurrentTime() + seekAmount).coerceIn(0, playerController.getLength())
                playerController.seekTo(newTime)
                showSeekFeedback("+10s")
            }
        }
    }

    private fun showSeekFeedback(text: String) {
        binding.textViewSeekFeedback.apply {
            visibility = TextView.VISIBLE
            setText(text)
            alpha = 1f
            animate()
                .alpha(0f)
                .setDuration(800)
                .setStartDelay(500)
                .start()
        }
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "extra_video_path"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_POSITION = "extra_video_position"
        const val ACTION_PLAY = "com.simplempv.ACTION_PLAY"
        const val ACTION_PAUSE = "com.simplempv.ACTION_PAUSE"
        const val ACTION_PREVIOUS = "com.simplempv.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.simplempv.ACTION_NEXT"
        
        const val KEY_PLAYBACK_SPEED = "key_playback_speed"
        const val KEY_HARDWARE_DECODING = "key_hardware_decoding"
        const val KEY_ROTATION_LOCKED = "key_rotation_locked"
        const val KEY_SLEEP_TIMER_MINUTES = "key_sleep_timer_minutes"
        const val KEY_SAVED_POSITION = "key_saved_position"
        const val KEY_ORIGINAL_URI = "key_original_uri"
        const val KEY_BOTTOM_SHEET_EXPANDED = "key_bottom_sheet_expanded"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            serviceBound = true

            playbackService?.onSkipToNext = { playNext() }
            playbackService?.onSkipToPrevious = { playPrevious() }
            playbackService?.onSeekTo = { pos -> playerController.seekTo(pos) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    private fun adjustBrightness(delta: Float) {
        val current = window.attributes.screenBrightness
        val currentBrightness = if (current > 0) current else screenBrightness
        val newBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
        val layoutParams = window.attributes
        layoutParams.screenBrightness = newBrightness
        window.attributes = layoutParams
        screenBrightness = newBrightness
        showFeedback("Brightness: ${(newBrightness * 100).toInt()}%")
    }

    private fun adjustVolume(delta: Float) {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
        val currentPercent = current.toFloat() / maxVolume.toFloat()
        val newPercent = (currentPercent + delta).coerceIn(0f, 1f)
        val newVolume = (newPercent * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        val percent = if (maxVolume > 0) (newVolume * 100 / maxVolume) else 0
        showFeedback("Volume: $percent%")
    }

    private fun togglePanel() {
        if (slidingPaneLayout.isOpen) {
            slidingPaneLayout.close()
        } else {
            slidingPaneLayout.open()
        }
    }

    private fun toggleRotationLock() {
        isRotationLocked = !isRotationLocked
        if (isRotationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            buttonLock.setImageResource(R.drawable.ic_lock)
            showFeedback("屏幕已锁定")
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            buttonLock.setImageResource(R.drawable.ic_unlock)
            showFeedback("屏幕已解锁")
        }
    }

    private fun toggleBottomSheet() {
        if (isBottomSheetExpanded) {
            hideBottomSheet()
        } else {
            showBottomSheet()
        }
    }

    private fun showBottomSheet() {
        bottomSheetControlsBinding.root.visibility = View.VISIBLE
        bottomSheetControlsBinding.root.animate()
            .translationY(0f)
            .setDuration(300)
            .start()
        isBottomSheetExpanded = true
    }

    private fun hideBottomSheet() {
        bottomSheetControlsBinding.root.animate()
            .translationY(bottomSheetControlsBinding.root.height.toFloat())
            .setDuration(300)
            .withEndAction {
                bottomSheetControlsBinding.root.visibility = View.GONE
            }
            .start()
        isBottomSheetExpanded = false
    }

    private fun showFeedback(message: String) {
        textViewDebug.text = message
        textViewDebug.visibility = View.VISIBLE
        handler.removeCallbacks(hideFeedbackRunnable)
        handler.postDelayed(hideFeedbackRunnable, 1500)
    }

    private val hideFeedbackRunnable = Runnable {
        textViewDebug.visibility = View.GONE
    }

    private fun setupPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipParams(16, 9)
        }
    }

    private fun updatePipParams(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                pipParams = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(width, height))
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error updating PiP params", e)
            }
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipParams != null) {
            enterPictureInPictureMode(pipParams!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        setupBackPressHandler()
        setupPip()

        playerController = MpvPlayerController(this)
        playerController.setCallback(this)
        playbackStateManager = PlaybackStateManager(this, playerController)
        bookmarkRepository = BookmarkRepository(this)

        if (!playerController.setupPlayer()) {
            Log.e("PlayerActivity", "Failed to setup player, finishing activity")
            Toast.makeText(this, "Video player initialization failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        progressUpdater = ProgressUpdater(
            handler = handler,
            seekBar = seekBar,
            currentTimeTextView = textViewCurrentTime,
            totalTimeTextView = textViewTotalTime,
            playerController = playerController,
            isUserSeekingSupplier = { isUserSeeking },
            updatePlayPauseButton = { updatePlayPauseButton() }
        )

        savedInstanceState?.let { state ->
            restoreInstanceState(state)
        }

        if (!hasVideoPermission()) {
            Toast.makeText(this, "Permission needed to access videos", Toast.LENGTH_LONG).show()
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }
            finish()
            return
        }

        loadVideo()
        handleIntentAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent?) {
        intent?.action ?: return
        when (intent.action) {
            ACTION_PLAY -> playerController.play()
            ACTION_PAUSE -> playerController.pause()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_NEXT -> playNext()
        }
    }

    private fun initViews() {
        surfaceView = binding.surfaceView
        seekBar = binding.seekBar
        textViewCurrentTime = binding.textViewCurrentTime
        textViewTotalTime = binding.textViewTotalTime
        buttonPlayPause = binding.buttonPlayPause
        buttonPrevious = binding.buttonPrevious
        buttonNext = binding.buttonNext
        buttonOpenPanel = binding.buttonOpenPanel
        buttonSpeed = binding.root.findViewById(R.id.buttonSpeed)
        buttonSnapshot = binding.root.findViewById(R.id.buttonSnapshot)
        buttonLock = binding.buttonLock
        buttonInfo = binding.root.findViewById(R.id.buttonInfo)
        buttonInfo.setOnClickListener { showVideoInfo() }
        buttonDecoding = binding.root.findViewById(R.id.buttonDecoding)
        buttonDecoding.setOnClickListener { showDecodingMenu() }
        buttonSleepTimer = binding.root.findViewById(R.id.buttonSleepTimer)
        buttonSleepTimer.setOnClickListener { showSleepTimerMenu() }
        buttonBookmark = binding.root.findViewById(R.id.buttonBookmark)
        buttonBookmark.setOnClickListener { showBookmarksMenu() }
        textViewDebug = binding.textViewDebug
        slidingPaneLayout = binding.slidingPaneLayout

        bottomSheetControlsBinding = binding.bottomSheetControls
        pipControlsBinding = binding.pipControlsLayout
        buttonMore = binding.buttonMore

        buttonMore.setOnClickListener { toggleBottomSheet() }

        pipControlsBinding.buttonPipPlayPause.setOnClickListener {
            togglePlayPause()
        }

        quickListFragment = supportFragmentManager
            .findFragmentById(R.id.quickListContainer) as QuickListFragment

        slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED

        slidingPaneLayout.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) {}
            override fun onPanelOpened(panel: View) {}
            override fun onPanelClosed(panel: View) {}
        })

        buttonPlayPause.setOnClickListener { togglePlayPause() }
        buttonPrevious.setOnClickListener { playPrevious() }
        buttonNext.setOnClickListener { playNext() }
        buttonOpenPanel.setOnClickListener { togglePanel() }
        buttonSpeed.setOnClickListener { showSpeedMenu() }
        buttonLock.setOnClickListener { toggleRotationLock() }
        buttonSnapshot.setOnClickListener { takeSnapshot() }

        bottomSheetControlsBinding.buttonSpeed.setOnClickListener {
            hideBottomSheet()
            showSpeedMenu()
        }

        bottomSheetControlsBinding.buttonDecoding.setOnClickListener {
            hideBottomSheet()
            showDecodingMenu()
        }

        bottomSheetControlsBinding.buttonSnapshot.setOnClickListener {
            hideBottomSheet()
            takeSnapshot()
        }

        bottomSheetControlsBinding.buttonSleepTimer.setOnClickListener {
            hideBottomSheet()
            showSleepTimerMenu()
        }

        bottomSheetControlsBinding.buttonBookmark.setOnClickListener {
            hideBottomSheet()
            showBookmarksMenu()
        }

        bottomSheetControlsBinding.buttonInfo.setOnClickListener {
            hideBottomSheet()
            showVideoInfo()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playerController.getLength()
                    val newTime = (progress * duration / 100)
                    playerController.seekTo(newTime)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isUserSeeking = false }
        })

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

        gestureDetector = GestureDetector(this, gestureListener)
        doubleTapGestureDetector = GestureDetector(this, doubleTapListener)

        surfaceView.setOnTouchListener { v, event ->
            doubleTapGestureDetector.onTouchEvent(event)
            val handled = gestureDetector.onTouchEvent(event)
            handled || v.performClick()
        }

        val currentBrightness = window.attributes.screenBrightness
        screenBrightness = if (currentBrightness > 0) currentBrightness else 0.7f

        bindService(Intent(this, PlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        sleepTimerManager = SleepTimerManager(handler)
        sleepTimerManager.setOnTimerFinishListener(object : SleepTimerManager.OnTimerFinishListener {
            override fun onTimerFinish() {
                playerController.pause()
                Toast.makeText(this@PlayerActivity, "睡眠定时结束", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (slidingPaneLayout.isOpen && slidingPaneLayout.isSlideable) {
                    slidingPaneLayout.close()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadVideo() {
        videoRepository = VideoRepository(this)
        videoList = videoRepository?.getVideos() ?: emptyList()
        quickListFragment.setVideos(videoList)

        currentPosition = intent.getIntExtra(EXTRA_VIDEO_POSITION, 0)

        val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI)

        val videoKey = videoUriString ?: videoPath
        playbackStateManager.setCurrentVideoKey(videoKey)
        quickListFragment.setCurrentVideo(videoKey)

        if (currentPosition in videoList.indices) {
            currentVideoTitle = videoList[currentPosition].displayName
        } else {
            currentVideoTitle = videoPath?.substringAfterLast('/') ?: "Unknown"
        }

        if (!videoPath.isNullOrEmpty() && File(videoPath).canRead()) {
            surfaceView.holder.removeCallback(surfaceCallback)
            surfaceView.holder.addCallback(surfaceCallback)
            playerController.setVideoPath(videoPath)
            playVideoWithPath(videoPath)
        } else if (!videoUriString.isNullOrEmpty()) {
            surfaceView.holder.removeCallback(surfaceCallback)
            surfaceView.holder.addCallback(surfaceCallback)
            val videoUri = Uri.parse(videoUriString)
            val path = videoRepository?.getFilePathFromUri(videoUri)
            playerController.setVideoPath(path)
            playVideo(videoUriString)
        }

        startPlaybackService()
    }

    private fun playVideoWithPath(filePath: String) {
        playerController.setPlaybackSpeed(1.0f)
        try {
            val fileUri = playerController.filePathToUri(filePath)

            if (surfaceView.holder.surface.isValid) {
                playerController.attachAndPlay(fileUri, surfaceView.holder, surfaceView, playbackStateManager.getCurrentVideoKey())
                progressUpdater.start()
            } else {
                pendingFdUri = fileUri
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error in playVideoWithPath", e)
            Toast.makeText(this, "Error playing video: ${e.message}", Toast.LENGTH_LONG).show()
            val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
            if (!videoUriString.isNullOrEmpty()) {
                playVideo(videoUriString)
            }
        }
    }

    private fun playVideo(contentUriString: String) {
        playerController.setPlaybackSpeed(1.0f)
        try {
            val contentUri = Uri.parse(contentUriString)

            val fdUri = playerController.openFdUri(contentUri)
            if (fdUri == null) {
                Log.e("PlayerActivity", "Failed to open file descriptor for URI: $contentUri")
                return
            }

            if (surfaceView.holder.surface.isValid) {
                playerController.attachAndPlay(fdUri, surfaceView.holder, surfaceView, playbackStateManager.getCurrentVideoKey())
                progressUpdater.start()
            } else {
                pendingFdUri = fdUri
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error in playVideo", e)
            Toast.makeText(this, "Error playing video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayPause() {
        playerController.togglePlayPause()
        playbackService?.updatePlaybackState(playerController.isPlaying(), currentVideoTitle, playerController.getCurrentTime())
    }

    private fun updatePlayPauseButton() {
        val isPlaying = playerController.isPlaying()
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateNotification() {
        playbackService?.updatePlaybackState(playerController.isPlaying(), currentVideoTitle, playerController.getCurrentTime())
    }

    private fun playPrevious() {
        playerController.setPlaybackSpeed(1.0f)
        if (currentPosition > 0) {
            saveCurrentPosition()
            currentPosition--
            val newVideoUri = videoList[currentPosition].uri.toString()
            currentVideoTitle = videoList[currentPosition].displayName
            playbackStateManager.setCurrentVideoKey(newVideoUri)
            playVideo(newVideoUri)
            playbackService?.updatePlaybackState(playerController.isPlaying(), currentVideoTitle, playerController.getCurrentTime())
        }
    }

    private fun playNext() {
        playerController.setPlaybackSpeed(1.0f)
        if (currentPosition < videoList.size - 1) {
            saveCurrentPosition()
            currentPosition++
            val newVideoUri = videoList[currentPosition].uri.toString()
            currentVideoTitle = videoList[currentPosition].displayName
            playbackStateManager.setCurrentVideoKey(newVideoUri)
            playVideo(newVideoUri)
            playbackService?.updatePlaybackState(playerController.isPlaying(), currentVideoTitle, playerController.getCurrentTime())
        }
    }

    override fun onVideoSelected(video: Video) {
        val position = videoList.indexOfFirst { it.uri == video.uri }
        if (position >= 0) {
            saveCurrentPosition()
            currentPosition = position
            currentVideoTitle = video.displayName
            playbackStateManager.setCurrentVideoKey(video.uri.toString())
            quickListFragment.setCurrentVideo(video.uri.toString())
            playVideo(video.uri.toString())
            playbackService?.updatePlaybackState(playerController.isPlaying(), currentVideoTitle, playerController.getCurrentTime())
            if (slidingPaneLayout.isOpen) {
                slidingPaneLayout.close()
            }
        }
    }

    private fun saveCurrentPosition() {
        val currentTime = playerController.getCurrentTime()
        playbackStateManager.saveCurrentPosition(currentTime)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        updatePlayPauseButton()
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        runOnUiThread {
            if (width > 0 && height > 0) {
                adjustSurfaceSize(width, height)
                updatePipParams(width, height)
            }
        }
    }

    private fun adjustSurfaceSize(videoWidth: Int, videoHeight: Int) {
        val containerWidth = surfaceView.width
        val containerHeight = surfaceView.height

        if (containerWidth == 0 || containerHeight == 0) {
            return
        }

        val videoRatio = videoWidth.toFloat() / videoHeight
        val screenRatio = containerWidth.toFloat() / containerHeight

        val params = surfaceView.layoutParams
        if (videoRatio > screenRatio) {
            params.width = containerWidth
            params.height = (containerWidth / videoRatio).toInt()
        } else {
            params.height = containerHeight
            params.width = (containerHeight * videoRatio).toInt()
        }
        surfaceView.layoutParams = params
    }

    override fun onProgressUpdate(position: Float, time: Long, length: Long) {
        playbackService?.setPosition(time)
        playbackService?.updatePlaybackState(playerController.isPlaying(), currentVideoTitle, time)
    }

    override fun onError(errorMessage: String) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            binding.controlsLayout.visibility = View.GONE
            bottomSheetControlsBinding.root.visibility = View.GONE
            
            pipControlsBinding.root.visibility = View.VISIBLE
            
            pipControlsBinding.buttonPipPlayPause.setImageResource(if (playerController.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
            
            binding.textViewSeekFeedback.visibility = View.GONE
            binding.textViewDebug.visibility = View.GONE
            
            val params = binding.surfaceView.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.surfaceView.layoutParams = params
            binding.surfaceView.requestLayout()
            
            surfaceView.postDelayed({
                val width = surfaceView.width
                val height = surfaceView.height
                android.util.Log.d("PlayerActivity", "PiP surface size: ${width}x${height}")
                try {
                    dev.jdtech.mpv.MPVLib.setOptionString("android-surface-size", "${width}x${height}")
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Error setting PiP surface size", e)
                }
            }, 100)
        } else {
            binding.controlsLayout.visibility = View.VISIBLE
            pipControlsBinding.root.visibility = View.GONE
            
            val params = binding.surfaceView.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.surfaceView.layoutParams = params
            
            if (::playerController.isInitialized) {
                val videoInfo = playerController.getVideoInfo()
                if (videoInfo != null) {
                    surfaceView.post {
                        adjustSurfaceSize(videoInfo.width, videoInfo.height)
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playerController.isPlaying()) {
            enterPipMode()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipMode) {
            saveCurrentPosition()
        }
        wasInBackground = true
        if (::playerController.isInitialized) {
            savedPosition = playerController.getCurrentTime()
        }
    }
    
    override fun onResume() {
        super.onResume()
        android.util.Log.d("PlayerActivity", "onResume called, wasInBackground=$wasInBackground")
        
        if (wasInBackground) {
            // Don't do anything here - wait for surfaceCreated
            // The actual reload will happen in surfaceCreated when surface is ready
            android.util.Log.d("PlayerActivity", "Waiting for surface to be ready")
        }
        
        if (::progressUpdater.isInitialized) {
            progressUpdater.start()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_player, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_decoding_software -> {
                playerController.setHardwareDecoding(false)
                Toast.makeText(this, R.string.hw_decoding_disabled, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_decoding_hardware -> {
                playerController.setHardwareDecoding(true)
                Toast.makeText(this, R.string.hw_decoding_enabled, Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerController.setPlaybackSpeed(1.0f)
        if (::progressUpdater.isInitialized) {
            progressUpdater.stop()
        }
        handler.removeCallbacks(hideFeedbackRunnable)
        handler.removeCallbacksAndMessages(null)
        surfaceView.holder.removeCallback(surfaceCallback)
        playerController.release()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        sleepTimerManager.cancel()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        wasConfigurationChange = isChangingConfigurations
        
        outState.putString(EXTRA_VIDEO_PATH, intent.getStringExtra(EXTRA_VIDEO_PATH))
        outState.putString(EXTRA_VIDEO_URI, intent.getStringExtra(EXTRA_VIDEO_URI))
        outState.putInt(EXTRA_VIDEO_POSITION, currentPosition)
        
        outState.putFloat(KEY_PLAYBACK_SPEED, playerController.getPlaybackSpeed())
        outState.putBoolean(KEY_HARDWARE_DECODING, playerController.isUsingHardwareDecoding())
        
        outState.putBoolean(KEY_ROTATION_LOCKED, isRotationLocked)
        outState.putInt(KEY_SLEEP_TIMER_MINUTES, sleepTimerMinutes)
        
        outState.putLong(KEY_SAVED_POSITION, savedPosition)
        
        outState.putString(KEY_ORIGINAL_URI, originalContentUri?.toString())
        
        outState.putBoolean(KEY_BOTTOM_SHEET_EXPANDED, isBottomSheetExpanded)
    }

    private fun restoreInstanceState(state: Bundle) {
        val speed = state.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        playerController.setPlaybackSpeed(speed)
        
        val hwDecoding = state.getBoolean(KEY_HARDWARE_DECODING, false)
        playerController.setHardwareDecoding(hwDecoding)
        
        isRotationLocked = state.getBoolean(KEY_ROTATION_LOCKED, false)
        sleepTimerMinutes = state.getInt(KEY_SLEEP_TIMER_MINUTES, 0)
        
        savedPosition = state.getLong(KEY_SAVED_POSITION, 0)
        
        state.getString(KEY_ORIGINAL_URI)?.let {
            originalContentUri = Uri.parse(it)
        }
        
        isBottomSheetExpanded = state.getBoolean(KEY_BOTTOM_SHEET_EXPANDED, false)
        
        if (isBottomSheetExpanded) {
            bottomSheetControlsBinding.root.visibility = View.VISIBLE
            bottomSheetControlsBinding.root.translationY = 0f
        }
        
        if (isRotationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            if (::buttonLock.isInitialized) {
                buttonLock.setImageResource(R.drawable.ic_lock)
            }
        }
    }

    private fun showSpeedMenu() {
        val popup = PopupMenu(this, buttonSpeed)
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        speeds.forEachIndexed { index, speed ->
            popup.menu.add(0, index, index, speed)
        }
        popup.setOnMenuItemClickListener { item ->
            val selectedSpeed = MpvPlayerController.SPEED_OPTIONS[item.itemId]
            playerController.setPlaybackSpeed(selectedSpeed)
            showFeedback("Speed: ${selectedSpeed}x")
            true
        }
        popup.show()
    }

    private fun showDecodingMenu() {
        val popup = PopupMenu(this, buttonDecoding)
        popup.menu.add(0, 0, 0, "Software Decoding")
        popup.menu.add(0, 1, 1, "Hardware Decoding")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> {
                    // Force software decoding using cycle-values (cycles to "no")
                    playerController.cycleDecodingMode()
                    playerController.cycleDecodingMode() // Call twice to ensure it's set to "no"
                    Toast.makeText(this, R.string.hw_decoding_disabled, Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    // Force hardware decoding using cycle-values (cycles to mediacodec)
                    playerController.cycleDecodingMode()
                    Toast.makeText(this, R.string.hw_decoding_enabled, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        popup.show()
    }

    private fun showVideoInfo() {
        val info = playerController.getVideoInfoString()
        val duration = playerController.getLength()
        val durationStr = formatTime(duration)

        AlertDialog.Builder(this)
            .setTitle("视频信息")
            .setMessage("时长: $durationStr\n\n$info")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showSleepTimerMenu() {
        val timerOptions = arrayOf("关闭", "15分钟", "30分钟", "45分钟", "60分钟", "90分钟")

        val currentSelection = when {
            !sleepTimerManager.isRunning() -> 0
            sleepTimerMinutes == 15 -> 1
            sleepTimerMinutes == 30 -> 2
            sleepTimerMinutes == 45 -> 3
            sleepTimerMinutes == 60 -> 4
            sleepTimerMinutes == 90 -> 5
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("睡眠定时器")
            .setSingleChoiceItems(timerOptions, currentSelection) { dialog, which ->
                when (which) {
                    0 -> {
                        sleepTimerManager.cancel()
                        sleepTimerMinutes = 0
                        showFeedback("定时器已关闭")
                    }
                    1 -> {
                        sleepTimerMinutes = 15
                        sleepTimerManager.start(15)
                        showFeedback("定时器: 15分钟")
                    }
                    2 -> {
                        sleepTimerMinutes = 30
                        sleepTimerManager.start(30)
                        showFeedback("定时器: 30分钟")
                    }
                    3 -> {
                        sleepTimerMinutes = 45
                        sleepTimerManager.start(45)
                        showFeedback("定时器: 45分钟")
                    }
                    4 -> {
                        sleepTimerMinutes = 60
                        sleepTimerManager.start(60)
                        showFeedback("定时器: 60分钟")
                    }
                    5 -> {
                        sleepTimerMinutes = 90
                        sleepTimerManager.start(90)
                        showFeedback("定时器: 90分钟")
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddBookmarkDialog() {
        val currentTime = playerController.getCurrentTime()

        AlertDialog.Builder(this)
            .setTitle("添加书签")
            .setMessage("在 ${formatTime(currentTime)} 处添加书签")
            .setPositiveButton("添加") { _, _ ->
                val label = "书签 ${getNextBookmarkNumber()}"
                val videoUri = intent.getStringExtra(EXTRA_VIDEO_URI) ?: return@setPositiveButton
                bookmarkRepository.addBookmark(videoUri, currentTime, label)
                showFeedback("书签已添加")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getNextBookmarkNumber(): Int {
        val videoUri = intent.getStringExtra(EXTRA_VIDEO_URI) ?: return 1
        return bookmarkRepository.getBookmarks(videoUri).size + 1
    }

    private fun showBookmarksMenu() {
        val videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (videoUri.isNullOrEmpty()) {
            Toast.makeText(this, "无法获取视频信息", Toast.LENGTH_SHORT).show()
            return
        }

        val bookmarks = bookmarkRepository.getBookmarks(videoUri)

        if (bookmarks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("书签")
                .setMessage("暂无书签")
                .setPositiveButton("添加当前书签") { _, _ -> showAddBookmarkDialog() }
                .setNegativeButton("关闭", null)
                .show()
            return
        }

        val items = bookmarks.map { "${it.label} - ${formatTime(it.position)}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("书签列表")
            .setItems(items) { _, which ->
                val bookmark = bookmarks[which]
                playerController.seekTo(bookmark.position)
                showFeedback("跳转到 ${formatTime(bookmark.position)}")
            }
            .setPositiveButton("添加当前书签") { _, _ -> showAddBookmarkDialog() }
            .setNeutralButton("删除") { _, _ ->
                showDeleteBookmarkDialog(videoUri, bookmarks)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDeleteBookmarkDialog(videoUri: String, bookmarks: List<BookmarkRepository.Bookmark>) {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "暂无书签可删除", Toast.LENGTH_SHORT).show()
            return
        }

        val items = bookmarks.map { "${it.label} - ${formatTime(it.position)}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要删除的书签")
            .setItems(items) { _, which ->
                val bookmark = bookmarks[which]
                bookmarkRepository.removeBookmark(videoUri, bookmark.position)
                Toast.makeText(this, "已删除: ${bookmark.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun takeSnapshot() {
        if (playerController.takeScreenshot()) {
            Toast.makeText(this, "截图已保存到图片目录", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show()
        }
    }
}
