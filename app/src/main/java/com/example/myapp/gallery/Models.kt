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
    // Epoch millis the photo/video was actually taken (EXIF/metadata), 0 if unknown. Distinct from
    // dateAdded/dateModified, which track when the file landed on or last changed on this device.
    val dateTaken: Long = 0,
    val bucketId: Long,
    val bucketName: String,
    // Folder the file lives in, e.g. "DCIM/Camera/". Used as the MediaStore RELATIVE_PATH
    // target when moving another item into this item's album.
    val relativePath: String,
    val durationMs: Long = 0,
    val mimeType: String = "",
    // For a trashed item, the epoch second at which Android deletes it for good (30 days after it
    // was trashed). 0 for a normal item.
    val dateExpires: Long = 0
)

data class Album(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val coverUri: Uri,
    val coverDateModified: Long,
    val itemCount: Int
)
