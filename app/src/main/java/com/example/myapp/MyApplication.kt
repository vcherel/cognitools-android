package com.example.myapp

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.example.myapp.deezer.DeezerRepository
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.FlashcardRepository
import com.example.myapp.notes.NOTES_TRASH_RETENTION_DAYS
import com.example.myapp.podcasts.PodcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApplication : Application(), SingletonImageLoader.Factory {
    val flashcardRepository: FlashcardRepository by lazy { FlashcardRepository(this) }
    val deezerRepository: DeezerRepository by lazy { DeezerRepository(this) }
    val podcastRepository: PodcastRepository by lazy { PodcastRepository(this) }

    // The notes trash keeps its own retention window; the gallery trash is MediaStore's, which
    // Android empties on its own.
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val cutoff = System.currentTimeMillis() -
                NOTES_TRASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L
            AppDatabase.get(this@MyApplication).noteDao().purgeExpiredTrashedNotes(cutoff)
            flashcardRepository.seedAndPurge()
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

// The two app wide singletons, reachable from any Context instead of casting at every call site.
val Context.flashcardRepository: FlashcardRepository
    get() = (applicationContext as MyApplication).flashcardRepository

val Context.deezerRepository: DeezerRepository
    get() = (applicationContext as MyApplication).deezerRepository

val Context.podcastRepository: PodcastRepository
    get() = (applicationContext as MyApplication).podcastRepository