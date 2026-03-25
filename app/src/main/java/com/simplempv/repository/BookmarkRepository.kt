package com.simplempv.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BookmarkRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "bookmarks"
    }

    data class Bookmark(
        val position: Long,
        val label: String,
        val createdAt: Long
    )

    fun addBookmark(videoUri: String, position: Long, label: String) {
        val bookmarks = getBookmarks(videoUri).toMutableList()
        bookmarks.add(Bookmark(position, label, System.currentTimeMillis()))
        saveBookmarks(videoUri, bookmarks)
    }

    fun getBookmarks(videoUri: String): List<Bookmark> {
        val key = getKey(videoUri)
        var json = prefs.getString(key, null)
        
        // Migration: check old key format if new key returns nothing
        if (json == null) {
            val oldKey = getOldKey(videoUri)
            json = prefs.getString(oldKey, null)
            if (json != null) {
                // Migrate: save under new key and remove old key
                val bookmarks = try {
                    val type = object : TypeToken<List<Bookmark>>() {}.type
                    gson.fromJson(json, type) as? List<Bookmark>
                } catch (e: Exception) {
                    null
                }
                if (bookmarks != null) {
                    saveBookmarks(videoUri, bookmarks)
                    prefs.edit().remove(oldKey).apply()
                }
            }
        }
        
        return if (json != null) {
            try {
                val type = object : TypeToken<List<Bookmark>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun removeBookmark(videoUri: String, position: Long) {
        val bookmarks = getBookmarks(videoUri).filter { it.position != position }
        saveBookmarks(videoUri, bookmarks)
    }

    fun removeAllBookmarks(videoUri: String) {
        prefs.edit().remove(getKey(videoUri)).apply()
    }

    fun clearAllBookmarks() {
        prefs.edit().clear().apply()
    }

    private fun saveBookmarks(videoUri: String, bookmarks: List<Bookmark>) {
        val key = getKey(videoUri)
        val json = gson.toJson(bookmarks)
        prefs.edit().putString(key, json).apply()
        prefs.edit().putString("${key}_uri", videoUri).apply()
    }

    private fun getKey(videoUri: String): String {
        val encoded = Base64.encodeToString(videoUri.toByteArray(), Base64.NO_WRAP)
        return "bookmarks_$encoded"
    }

    private fun getOldKey(videoUri: String): String {
        return "bookmarks_${videoUri.hashCode()}"
    }
}
