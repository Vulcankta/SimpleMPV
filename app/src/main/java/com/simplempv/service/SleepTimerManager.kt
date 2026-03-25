package com.simplempv.service

import android.os.Handler
import android.os.Looper

class SleepTimerManager(private val handler: Handler) {

    interface OnTimerFinishListener {
        fun onTimerFinish()
    }

    private var timerFinishListener: OnTimerFinishListener? = null
    private var remainingTimeMillis: Long = 0
    private var isTimerRunning = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isTimerRunning) return

            remainingTimeMillis -= 60_000
            if (remainingTimeMillis <= 0) {
                cancel()
                timerFinishListener?.onTimerFinish()
            } else {
                handler.postDelayed(this, 60_000)
            }
        }
    }

    fun setOnTimerFinishListener(listener: OnTimerFinishListener?) {
        timerFinishListener = listener
    }

    fun start(minutes: Int) {
        cancel()
        remainingTimeMillis = minutes * 60_000L
        isTimerRunning = true
        handler.postDelayed(timerRunnable, 60_000)
    }

    fun cancel() {
        isTimerRunning = false
        handler.removeCallbacks(timerRunnable)
        remainingTimeMillis = 0
    }

    fun isRunning(): Boolean = isTimerRunning

    fun getRemainingMinutes(): Int = ((remainingTimeMillis + 59_999) / 60_000).toInt()

    fun getRemainingTimeString(): String {
        val minutes = getRemainingMinutes()
        return if (minutes >= 60) {
            val hours = minutes / 60
            val mins = minutes % 60
            "${hours}h ${mins}m"
        } else {
            "${minutes}m"
        }
    }
}
