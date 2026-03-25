package com.simplempv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.simplempv.R
import com.simplempv.PlayerActivity

class PlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isPlaying = false
    private var currentTitle = "Unknown"

    companion object {
        const val CHANNEL_ID = "simplempv_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.simplempv.ACTION_PLAY"
        const val ACTION_PAUSE = "com.simplempv.ACTION_PAUSE"
        const val ACTION_STOP = "com.simplempv.ACTION_STOP"
        const val ACTION_PREVIOUS = "com.simplempv.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.simplempv.ACTION_NEXT"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SimpleMPV::PlaybackWakeLock"
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_STOP -> stop()
                ACTION_PREVIOUS -> onSkipToPrevious?.invoke()
                ACTION_NEXT -> onSkipToNext?.invoke()
                else -> {}
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        wakeLock?.acquire(30 * 60 * 1000L)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频播放",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "视频播放控制"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "SimpleMPV").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onStop() {
                    stop()
                }

                override fun onSkipToNext() {
                    onSkipToNext?.invoke()
                }

                override fun onSkipToPrevious() {
                    onSkipToPrevious?.invoke()
                }

                override fun onSeekTo(pos: Long) {
                    onSeekTo?.invoke(pos)
                }

                override fun onFastForward() {
                    onSkipToNext?.invoke()
                }

                override fun onRewind() {
                    onSkipToPrevious?.invoke()
                }
            })

            isActive = true
        }
    }

    private fun buildNotification(): Notification {
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "暂停",
                createPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "播放",
                createPendingIntent(ACTION_PLAY)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在播放")
            .setContentText(currentTitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_previous, "上一曲", createPendingIntent(ACTION_PREVIOUS))
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_next, "下一曲", createPendingIntent(ACTION_NEXT))
            .build()
    }

    private fun createPendingIntent(action: String): PendingIntent {
        return PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun updatePlaybackState(playing: Boolean, title: String, position: Long = 0) {
        isPlaying = playing
        currentTitle = title
        currentPosition = position
        updateNotification()

        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, position, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                )
                .build()
        )
    }

    private var currentPosition: Long = 0
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    fun play() {
        isPlaying = true
        updateNotification()
        mediaSession?.isActive = true
    }

    fun pause() {
        isPlaying = false
        updateNotification()
    }

    fun stop() {
        isPlaying = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setPosition(position: Long) {
        currentPosition = position
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
