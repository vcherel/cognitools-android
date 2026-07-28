package com.example.myapp

import android.os.Build
import android.os.Bundle
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapp.deezer.DeezerScreen
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.FlashcardDetailScreen
import com.example.myapp.flashcards.FlashcardGameScreen
import com.example.myapp.flashcards.FlashcardListsScreen
import com.example.myapp.gallery.GalleryAlbumGridScreen
import com.example.myapp.gallery.GalleryAlbumsScreen
import com.example.myapp.gallery.GalleryCropScreen
import com.example.myapp.gallery.GalleryTrashScreen
import com.example.myapp.gallery.GalleryTrimScreen
import com.example.myapp.gallery.GalleryViewerScreen
import com.example.myapp.gallery.LocalMediaConsent
import com.example.myapp.gallery.ViewerSource
import com.example.myapp.gallery.rememberIntentSenderRequester
import com.example.myapp.notes.NoteEditorScreen
import com.example.myapp.notes.NotesListScreen
import com.example.myapp.notes.NotesTrashScreen
import com.example.myapp.notes.TODO_LIST_TITLE
import com.example.myapp.notes.noteTitleAndPreview
import com.example.myapp.undercover.UndercoverScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setOnExitAnimationListener { splashScreenView ->
            splashScreenView.iconView.animate()
                .scaleX(1.5f)
                .scaleY(1.5f)
                .alpha(0f)
                .setInterpolator(AnticipateInterpolator())
                .setDuration(500L)
                .withEndAction { splashScreenView.remove() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge()
        setContent {
            val themeManager = remember { ThemeManager(applicationContext) }
            val isDarkMode by themeManager.isDarkMode.collectAsState(initial = false)

            AppTheme(isDarkMode = isDarkMode) {
                MainScreen(themeManager = themeManager, isDarkMode = isDarkMode)
            }
        }
    }
}

@Composable
fun MainScreen(themeManager: ThemeManager, isDarkMode: Boolean) {
    val navController = rememberNavController()
    val idleGuard = remember { IdleResetGuard() }
    val goHome: () -> Unit = { navController.popBackStack("menu", inclusive = false) }

    // Coming back after a long time away starts over from the main menu
    IdleReturnToMenu(guard = idleGuard, onIdle = goHome)

    // One consent launcher for the whole app: an undo posted from a screen that is already gone
    // (deleting from the gallery viewer) still has something to ask Android with.
    val mediaConsent = rememberIntentSenderRequester()

    CompositionLocalProvider(
        LocalIsDarkMode provides isDarkMode,
        LocalGoHome provides goHome,
        LocalIdleResetGuard provides idleGuard,
        LocalMediaConsent provides mediaConsent
    ) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            AppSnackbar.requests.collect { request ->
                val result = snackbarHostState.showSnackbar(
                    message = request.message,
                    actionLabel = request.actionLabel,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) request.onAction?.invoke()
            }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                NavHost(navController = navController, startDestination = "menu") {
                    composable("menu") {
                        MenuScreen(
                            isDarkMode = isDarkMode,
                            onToggleDarkMode = {
                                coroutineScope.launch { themeManager.setDarkMode(!isDarkMode) }
                            },
                            onOpenNotes = { navController.navigate("notes") },
                            onOpenTodoNote = {
                                coroutineScope.launch {
                                    val todo = AppDatabase.get(context).noteDao().getNotes()
                                        .firstOrNull {
                                            noteTitleAndPreview(it).first
                                                .equals(TODO_LIST_TITLE, ignoreCase = true)
                                        }
                                    navController.navigate("notes")
                                    if (todo != null) navController.navigate("note/${todo.id}")
                                }
                            },
                            onOpenDeezer = { navController.navigate("deezer") },
                            onOpenFlashcards = { navController.navigate("flashcards") },
                            onPlayFlashcards = {
                                navController.navigate("lists")
                                navController.navigate("game/all")
                            },
                            onOpenWeather = { navController.navigate("weather") },
                            onOpenUndercover = { navController.navigate("undercover") },
                            onOpenVolume = { navController.navigate("volumeBooster") },
                            onOpenRandom = { navController.navigate("randomGenerator") },
                            onOpenWikipedia = { navController.navigate("wikipedia") },
                            onOpenGallery = { navController.navigate("gallery") },
                            onOpenPinnedPictures = { navController.navigate("gallery/pinned") }
                        )
                    }
                    composable("randomGenerator") {
                        RandomGeneratorScreen(onBack = { navController.popBackStack() })
                    }
                    composable("volumeBooster") {
                        VolumeBoosterScreen(onBack = { navController.popBackStack() })
                    }
                    composable("undercover") {
                        UndercoverScreen(onBack = { navController.popBackStack() })
                    }
                    composable("wikipedia") {
                        WikipediaScreen(onBack = { navController.popBackStack() })
                    }
                    composable("weather") {
                        WeatherScreen(onBack = { navController.popBackStack() })
                    }
                    composable("deezer") {
                        DeezerScreen(onBack = { navController.popBackStack() })
                    }
                    composable("gallery") {
                        GalleryAlbumsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = { bucketId -> navController.navigate("gallery/album/$bucketId") },
                            onOpenTrash = { navController.navigate("gallery/trash") },
                            onOpenPinned = { navController.navigate("gallery/pinned") }
                        )
                    }
                    composable("gallery/trash") {
                        GalleryTrashScreen(onBack = { navController.popBackStack() })
                    }
                    composable("gallery/album/{bucketId}") { backStackEntry ->
                        val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
                        GalleryAlbumGridScreen(
                            bucketId = bucketId,
                            onBack = { navController.popBackStack() },
                            onOpenItem = { itemId -> navController.navigate("gallery/viewer/$bucketId/$itemId") }
                        )
                    }
                    composable("gallery/viewer/{bucketId}/{itemId}") { backStackEntry ->
                        val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryViewerScreen(
                            source = ViewerSource.Album(bucketId),
                            initialItemId = itemId,
                            onBack = { navController.popBackStack() },
                            onCrop = { id -> navController.navigate("gallery/crop/$id") },
                            onTrim = { id -> navController.navigate("gallery/trim/$id") }
                        )
                    }
                    composable("gallery/pinned") {
                        GalleryViewerScreen(
                            source = ViewerSource.Pinned,
                            initialItemId = -1L,
                            onBack = { navController.popBackStack() },
                            onCrop = { id -> navController.navigate("gallery/crop/$id") },
                            onTrim = { id -> navController.navigate("gallery/trim/$id") }
                        )
                    }
                    composable("gallery/crop/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryCropScreen(itemId = itemId, onBack = { navController.popBackStack() })
                    }
                    composable("gallery/trim/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryTrimScreen(itemId = itemId, onBack = { navController.popBackStack() })
                    }
                    composable("notes") {
                        NotesListScreen(navController = navController)
                    }
                    composable("notes/trash") {
                        NotesTrashScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        "note/{noteId}?editAt={editAt}",
                        arguments = listOf(navArgument("editAt") { type = NavType.IntType; defaultValue = -1 })
                    ) { backStackEntry ->
                        val noteId = backStackEntry.arguments?.getString("noteId") ?: "new"
                        val editAt = backStackEntry.arguments?.getInt("editAt") ?: -1
                        NoteEditorScreen(
                            noteId = noteId,
                            initialEditOffset = editAt,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    navigation(startDestination = "lists", route = "flashcards") {
                        composable("lists") {
                            FlashcardListsScreen(navController = navController)
                        }
                        composable("elements/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardDetailScreen(
                                listId = listId,
                                navController = navController,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("game/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardGameScreen(
                                listId = listId,
                                navController = navController
                            )
                        }
                    }
                }
            }
        }
    }
}
