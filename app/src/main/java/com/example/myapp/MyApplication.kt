package com.example.myapp

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.example.myapp.deezer.DeezerRepository
import com.example.myapp.flashcards.FlashcardRepository

class MyApplication : Application(), SingletonImageLoader.Factory {
    val flashcardRepository: FlashcardRepository by lazy { FlashcardRepository(this) }
    val deezerRepository: DeezerRepository by lazy { DeezerRepository(this) }

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