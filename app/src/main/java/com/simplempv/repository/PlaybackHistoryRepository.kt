package com.simplempv.repository

import android.content.Context
import android.content.SharedPreferences

class PlaybackHistoryRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    fun savePosition(videoPath: String, position: Long) {
        prefs.edit().putLong(getKey(videoPath), position).apply()
    }

    fun getPosition(videoPath: String): Long {
        return prefs.getLong(getKey(videoPath), 0)
    }

    fun clearPosition(videoPath: String) {
        prefs.edit().remove(getKey(videoPath)).apply()
    }

    private fun getKey(videoPath: String): String {
        return "position_$videoPath"
    }

    companion object {
        private const val PREFS_NAME = "playback_history"
    }
}
