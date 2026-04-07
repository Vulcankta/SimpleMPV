package com.simplempv.player

import android.content.Context
import com.simplempv.repository.PlaybackHistoryRepository

class PlaybackStateManager(
    private val context: Context,
    private val playerController: MpvPlayerController
) {
    private var savedPosition: Long = 0
    private var currentVideoKey: String? = null
    private val historyRepository: PlaybackHistoryRepository by lazy {
        PlaybackHistoryRepository(context)
    }

    fun setCurrentVideoKey(key: String?) {
        currentVideoKey = key
        playerController.setCurrentVideoKey(key)

        val position = if (key != null) historyRepository.getPosition(key) else 0L
        savedPosition = position
        // 使用帶 key 的版本，避免依賴調用順序
        if (key != null) {
            playerController.setSavedPosition(key, position)
        }
    }

    fun getCurrentVideoKey(): String? = currentVideoKey

    fun getSavedPosition(): Long = savedPosition

    fun getSavedPosition(key: String): Long {
        return historyRepository.getPosition(key)
    }

    fun saveCurrentPosition(currentTime: Long) {
        currentVideoKey?.let { key ->
            historyRepository.savePosition(key, currentTime)
            savedPosition = currentTime
            playerController.setSavedPosition(key, currentTime)
        }
    }

    fun savePosition(key: String, time: Long) {
        historyRepository.savePosition(key, time)
        if (key == currentVideoKey) {
            savedPosition = time
            playerController.setSavedPosition(key, time)
        }
    }

    fun setSavedPosition(position: Long) {
        savedPosition = position
        currentVideoKey?.let { key ->
            playerController.setSavedPosition(key, position)
        }
    }

    fun clearPosition(key: String) {
        historyRepository.clearPosition(key)
        if (key == currentVideoKey) {
            savedPosition = 0
            playerController.setSavedPosition(key, 0)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onPlaybackProgress(currentTime: Long) {}
}
