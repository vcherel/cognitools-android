package com.example.myapp

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.example.myapp.deezer.DeezerRepository
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.FlashcardRepository
import com.example.myapp.notes.NOTES_TRASH_RETENTION_DAYS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApplication : Application(), SingletonImageLoader.Factory {
    val flashcardRepository: FlashcardRepository by lazy { FlashcardRepository(this) }
    val deezerRepository: DeezerRepository by lazy { DeezerRepository(this) }

    // The notes trash keeps its own retention window; the gallery trash is MediaStore's, which
    // Android empties on its own.
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val cutoff = System.currentTimeMillis() -
                NOTES_TRASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L
            AppDatabase.get(this@MyApplication).noteDao().purgeExpiredTrashedNotes(cutoff)
        }
    }

    // Lets Coil (used by the gallery tool) decode a video's first frame as its thumbnail,
    // the same way it decodes an image.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory()) // load remote https images (Deezer cover art)
                add(VideoFrameDecoder.Factory())
            }
            .build()
}