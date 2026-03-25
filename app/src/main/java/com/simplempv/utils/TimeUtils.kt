package com.simplempv.utils

import java.util.concurrent.TimeUnit

object TimeUtils {

    fun formatDuration(durationMs: Long): String = formatTime(durationMs)

    fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"

        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun formatDurationShort(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
