package com.simplempv.utils

import java.util.concurrent.TimeUnit

object TimeUtils {
    
    /**
     * 格式化持續時間（毫秒）為HH:MM:SS或MM:SS格式
     * @param durationMs 持續時間（毫秒）
     * @return 格式化後的時間字符串
     */
    fun formatDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    /**
     * 格式化時間（毫秒）為MM:SS格式（用於播放器當前時間/總時間顯示）
     * @param ms 時間（毫秒）
     * @return 格式化後的時間字符串（MM:SS）
     */
    fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }
    
    /**
     * 簡化版本：只顯示分鐘和秒（MM:SS），不顯示小時
     * @param durationMs 持續時間（毫秒）
     * @return 格式化後的時間字符串（MM:SS）
     */
    fun formatDurationShort(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
