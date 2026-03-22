package com.simplempv.model

import android.net.Uri

data class Video(
    val id: Long,
    val displayName: String,
    val path: String,
    val dateAdded: Long,
    val duration: Long,
    val size: Long,
    val uri: Uri
)
