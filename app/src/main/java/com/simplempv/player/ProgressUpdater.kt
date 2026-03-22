package com.simplempv.player

import android.os.Handler
import android.widget.SeekBar
import android.widget.TextView
import com.simplempv.utils.TimeUtils

class ProgressUpdater(
    private val handler: Handler,
    private val seekBar: SeekBar,
    private val currentTimeTextView: TextView,
    private val totalTimeTextView: TextView,
    private val playerController: MpvPlayerController,
    private val isUserSeekingSupplier: () -> Boolean,
    private val updatePlayPauseButton: () -> Unit
) {
    private var progressUpdaterRunnable: Runnable? = null
    private var isRunning = false

    fun start() {
        stop()
        isRunning = true

        progressUpdaterRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                if (!isUserSeekingSupplier()) {
                    try {
                        val length = playerController.getLength()
                        if (length > 0) {
                            val time = playerController.getCurrentTime()
                            val position = time.toFloat() / length
                            seekBar.progress = (position * 100).toInt()
                            currentTimeTextView.text = TimeUtils.formatTime(time)
                            totalTimeTextView.text = TimeUtils.formatTime(length)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                updatePlayPauseButton()
                if (isRunning) {
                    handler.postDelayed(this, 500)
                }
            }
        }
        progressUpdaterRunnable?.let { handler.postDelayed(it, 500) }
    }

    fun stop() {
        isRunning = false
        progressUpdaterRunnable?.let { handler.removeCallbacks(it) }
        progressUpdaterRunnable = null
    }

    fun release() {
        stop()
    }
}
