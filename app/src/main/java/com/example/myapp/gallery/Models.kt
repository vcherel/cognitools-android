package com.example.myapp.gallery

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val type: MediaType,
    val dateAdded: Long,
    val dateModified: Long,
    val bucketId: Long,
    val bucketName: String,
    // Folder the file lives in, e.g. "DCIM/Camera/". Used as the MediaStore RELATIVE_PATH
    // target when moving another item into this item's album.
    val relativePath: String,
    val durationMs: Long = 0,
    val mimeType: String = ""
)

data class Album(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val coverUri: Uri,
    val coverDateModified: Long,
    val itemCount: Int
)
