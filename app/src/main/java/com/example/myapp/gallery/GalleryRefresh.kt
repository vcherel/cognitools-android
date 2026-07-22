package com.example.myapp.gallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bumped after a rename/move/delete/crop/trim so album and album-grid screens know to reload,
// without needing a shared view model across the gallery's nav graph.
object GalleryRefresh {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun bump() {
        _version.value++
    }
}
