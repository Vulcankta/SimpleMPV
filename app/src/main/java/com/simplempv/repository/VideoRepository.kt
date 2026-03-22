package com.simplempv.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.simplempv.model.Video

enum class SortOrder {
    DATE_DESC,    // 日期降序（最新优先）
    DATE_ASC,     // 日期升序（最早优先）
    NAME_ASC,     // 名称升序 (A-Z)
    NAME_DESC,    // 名称降序 (Z-A)
    SIZE_DESC,    // 大小降序（大文件优先）
    SIZE_ASC,     // 大小升序（小文件优先）
    DURATION_DESC, // 时长降序（长视频优先）
    DURATION_ASC  // 时长升序（短视频优先）
}

class VideoRepository(private val context: Context) {
    
    private val contentResolver: ContentResolver = context.contentResolver
    
    fun getVideos(sortOrder: SortOrder = SortOrder.DATE_DESC): List<Video> {
        val videos = mutableListOf<Video>()
        
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        
        val sortOrderString = when (sortOrder) {
            SortOrder.DATE_DESC -> "${MediaStore.Video.Media.DATE_ADDED} DESC"
            SortOrder.DATE_ASC -> "${MediaStore.Video.Media.DATE_ADDED} ASC"
            SortOrder.NAME_ASC -> "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
            SortOrder.NAME_DESC -> "${MediaStore.Video.Media.DISPLAY_NAME} DESC"
            SortOrder.SIZE_DESC -> "${MediaStore.Video.Media.SIZE} DESC"
            SortOrder.SIZE_ASC -> "${MediaStore.Video.Media.SIZE} ASC"
            SortOrder.DURATION_DESC -> "${MediaStore.Video.Media.DURATION} DESC"
            SortOrder.DURATION_ASC -> "${MediaStore.Video.Media.DURATION} ASC"
        }
        
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrderString
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                
                val displayName = cursor.getString(nameColumn) ?: "Unknown"
                val data = cursor.getString(dataColumn) ?: ""
                
                videos.add(
                    Video(
                        id = id,
                        displayName = displayName,
                        path = data,
                        dateAdded = cursor.getLong(dateColumn),
                        duration = cursor.getLong(durationColumn),
                        size = cursor.getLong(sizeColumn),
                        uri = contentUri
                    )
                )
            }
        }
        
        return videos
    }
    
    fun getFilePathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                return cursor.getString(pathColumn)
            }
        }
        
        return null
    }
}
