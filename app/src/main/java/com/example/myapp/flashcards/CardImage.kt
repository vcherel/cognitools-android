package com.example.myapp.flashcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/*
 * A card's image is a reference string: "asset:flags/fr.webp" for the pictures shipped in the APK,
 * "user:<uuid>.jpg" for one picked from the phone, copied into filesDir so it outlives the original.
 */

private const val ASSET_PREFIX = "asset:"
private const val USER_PREFIX = "user:"
private const val MAX_DIMENSION = 1024

private fun Context.cardImagesDir() = File(filesDir, "flashcard_images")

private fun Context.userImageFile(image: String): File? =
    if (image.startsWith(USER_PREFIX)) File(cardImagesDir(), image.removePrefix(USER_PREFIX)) else null

private fun Context.cardImageModel(image: String): Any =
    userImageFile(image) ?: "file:///android_asset/${image.removePrefix(ASSET_PREFIX)}"

@Composable
fun CardImage(
    image: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    AsyncImage(
        model = LocalContext.current.cardImageModel(image),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * Copies the picked picture into the app, downscaled to [MAX_DIMENSION] on its long side and with its
 * EXIF orientation applied (ImageDecoder does it), so backups carrying it stay small.
 */
suspend fun importCardImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val name = "${UUID.randomUUID()}.jpg"
        context.cardImagesDir().mkdirs()
        File(context.cardImagesDir(), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        USER_PREFIX + name
    } catch (_: Exception) {
        null
    }
}

/** The picked picture's bytes, for a backup: the builtin ones ship with the app and need none. */
fun Context.userImageBase64(image: String?): String? {
    val file = image?.let { userImageFile(it) }?.takeIf { it.exists() } ?: return null
    return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
}

fun Context.restoreUserImage(image: String, base64: String) {
    val file = userImageFile(image) ?: return
    if (file.exists()) return
    file.parentFile?.mkdirs()
    file.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
}

/**
 * Deletes the copied pictures no card points at any more: a deleted card or list, a replaced
 * picture, an edit dialog cancelled after picking one.
 */
fun Context.sweepCardImages(referenced: Collection<String>) {
    val keep = referenced.mapNotNull { userImageFile(it)?.name }.toSet()
    cardImagesDir().listFiles()?.forEach { if (it.name !in keep) it.delete() }
}
