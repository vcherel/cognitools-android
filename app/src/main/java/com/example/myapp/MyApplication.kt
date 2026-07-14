package com.example.myapp

import android.app.Application
import com.example.myapp.flashcards.FlashcardRepository

class MyApplication : Application() {
    val flashcardRepository: FlashcardRepository by lazy { FlashcardRepository(this) }
}