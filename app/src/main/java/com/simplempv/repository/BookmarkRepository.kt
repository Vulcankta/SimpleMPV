package com.simplempv.repository

import android.content.Context
import android.content.SharedPreferences
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
        val json = prefs.getString(getKey(videoUri), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
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
        val json = gson.toJson(bookmarks)
        prefs.edit().putString(getKey(videoUri), json).apply()
    }

    private fun getKey(videoUri: String): String {
        return "bookmarks_${videoUri.hashCode()}"
    }
}
