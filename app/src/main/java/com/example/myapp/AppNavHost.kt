package com.example.myapp

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapp.deezer.DeezerScreen
import com.example.myapp.files.FilesScreen
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.FlashcardDetailScreen
import com.example.myapp.flashcards.FlashcardGameScreen
import com.example.myapp.flashcards.FlashcardListsScreen
import com.example.myapp.gallery.GalleryAlbumGridScreen
import com.example.myapp.gallery.GalleryAlbumsScreen
import com.example.myapp.gallery.GalleryCropScreen
import com.example.myapp.gallery.GalleryTrashScreen
import com.example.myapp.gallery.GalleryTrimScreen
import com.example.myapp.gallery.LocalMediaConsent
import com.example.myapp.gallery.ViewerSource
import com.example.myapp.gallery.GalleryViewerScreen
import com.example.myapp.gallery.rememberIntentSenderRequester
import com.example.myapp.motsfleches.MotsFlechesScreen
import com.example.myapp.news.NewsArticleScreen
import com.example.myapp.news.NewsSavedScreen
import com.example.myapp.news.NewsScreen
import com.example.myapp.notes.NoteEditorScreen
import com.example.myapp.notes.NotesListScreen
import com.example.myapp.notes.NotesTrashScreen
import com.example.myapp.notes.TODO_LIST_TITLE
import com.example.myapp.notes.noteTitleAndPreview
import com.example.myapp.reader.BookLibraryScreen
import com.example.myapp.reader.ReaderScreen
import com.example.myapp.translate.TranslateScreen
import com.example.myapp.undercover.UndercoverScreen
import kotlinx.coroutines.launch

/**
 * The whole app's navigation graph, plus the composition locals and the snackbar host every tool
 * screen reads. MainActivity owns the window and the intents; everything on screen starts here.
 */
@Composable
fun MainScreen(
    themeManager: ThemeManager,
    isDarkMode: Boolean,
    initialRoute: String?,
    initialRouteMessage: String?,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit
) {
    val navController = rememberNavController()
    val idleGuard = remember { IdleResetGuard() }
    val goHome: () -> Unit = { navController.popBackStack("menu", inclusive = false) }
    val back: () -> Unit = { navController.popBackStackOnce() }

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

        LaunchedEffect(initialRoute) {
            val route = initialRoute ?: return@LaunchedEffect
            navController.navigate(route)
            initialRouteMessage?.let { AppSnackbar.show(it) }
        }

        LaunchedEffect(pendingRoute) {
            val route = pendingRoute ?: return@LaunchedEffect
            navController.navigate(route) { launchSingleTop = true }
            onPendingRouteConsumed()
        }

        val snackbarHostState = rememberAppSnackbarHostState()

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
                            onOpenMotsFleches = { navController.navigate("motsFleches") },
                            onOpenTranslate = { navController.navigate("translate") },
                            onOpenReader = { navController.navigate("reader") },
                            onOpenUndercover = { navController.navigate("undercover") },
                            onOpenVolume = { navController.navigate("volumeBooster") },
                            onOpenRandom = { navController.navigate("randomGenerator") },
                            onOpenWikipedia = { navController.navigate("wikipedia") },
                            onOpenFiles = { navController.navigate("files") },
                            onOpenNews = { navController.navigate("news") },
                            onOpenGallery = { navController.navigate("gallery") },
                            onOpenWallet = { navController.navigate("gallery/wallet") },
                            onOpenPinnedPictures = { navController.navigate("gallery/pinned") }
                        )
                    }
                    composable("randomGenerator") { RandomGeneratorScreen(onBack = back) }
                    composable("volumeBooster") { VolumeBoosterScreen(onBack = back) }
                    composable("undercover") { UndercoverScreen(onBack = back) }
                    composable("wikipedia") { WikipediaScreen(onBack = back) }
                    composable("files") { FilesScreen(onBack = back) }
                    composable("weather") { WeatherScreen(onBack = back) }
                    composable("news") {
                        NewsScreen(
                            onBack = back,
                            onOpenArticle = { link -> navController.navigate("news/article/${Uri.encode(link)}") },
                            onOpenSaved = { navController.navigate("news/saved") }
                        )
                    }
                    composable("news/saved") {
                        NewsSavedScreen(
                            onBack = back,
                            onOpenArticle = { link -> navController.navigate("news/article/${Uri.encode(link)}") }
                        )
                    }
                    composable("news/article/{link}") { backStackEntry ->
                        val link = backStackEntry.arguments?.getString("link").orEmpty()
                        NewsArticleScreen(link = link, onBack = back)
                    }
                    composable("motsFleches") { MotsFlechesScreen(onBack = back) }
                    composable("translate") { TranslateScreen(onBack = back) }
                    composable("reader") {
                        BookLibraryScreen(
                            onBack = back,
                            onOpenBook = { bookId -> navController.navigate("reader/book/$bookId") }
                        )
                    }
                    composable("reader/book/{bookId}") { backStackEntry ->
                        val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
                        ReaderScreen(bookId = bookId, onBack = back)
                    }
                    composable(
                        "deezer?openPlayer={openPlayer}",
                        arguments = listOf(navArgument("openPlayer") { type = NavType.BoolType; defaultValue = false })
                    ) { backStackEntry ->
                        val openPlayer = backStackEntry.arguments?.getBoolean("openPlayer") ?: false
                        DeezerScreen(
                            onBack = back,
                            openFullPlayerInitially = openPlayer,
                            onOpenVolume = { navController.navigate("volumeBooster") }
                        )
                    }
                    composable("gallery") {
                        GalleryAlbumsScreen(
                            onBack = back,
                            onOpenAlbum = { bucketId -> navController.navigate("gallery/album/$bucketId") },
                            onOpenTrash = { navController.navigate("gallery/trash") },
                            onOpenPinned = { navController.navigate("gallery/pinned") }
                        )
                    }
                    composable("gallery/trash") { GalleryTrashScreen(onBack = back) }
                    composable("gallery/album/{bucketId}") { backStackEntry ->
                        val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
                        GalleryAlbumGridScreen(
                            bucketId = bucketId,
                            onBack = back,
                            onOpenItem = { itemId -> navController.navigate("gallery/viewer/$bucketId/$itemId") }
                        )
                    }
                    // The three viewer entry points differ only in where their items come from.
                    viewerRoute("gallery/viewer/{bucketId}/{itemId}", navController, back) { args ->
                        ViewerSource.Album(args?.getString("bucketId")?.toLongOrNull() ?: 0L) to
                            (args?.getString("itemId")?.toLongOrNull() ?: 0L)
                    }
                    viewerRoute("gallery/pinned", navController, back) { ViewerSource.Pinned to -1L }
                    viewerRoute("gallery/wallet", navController, back) { ViewerSource.Wallet to -1L }
                    composable("gallery/crop/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryCropScreen(itemId = itemId, onBack = back)
                    }
                    composable("gallery/trim/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryTrimScreen(itemId = itemId, onBack = back)
                    }
                    composable("notes") { NotesListScreen(navController = navController) }
                    composable("notes/trash") { NotesTrashScreen(onBack = back) }
                    composable(
                        "note/{noteId}?editAt={editAt}&search={search}",
                        arguments = listOf(
                            navArgument("editAt") { type = NavType.IntType; defaultValue = -1 },
                            navArgument("search") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { backStackEntry ->
                        val noteId = backStackEntry.arguments?.getString("noteId") ?: "new"
                        val editAt = backStackEntry.arguments?.getInt("editAt") ?: -1
                        val search = backStackEntry.arguments?.getString("search").orEmpty()
                        NoteEditorScreen(
                            noteId = noteId,
                            initialEditOffset = editAt,
                            searchQuery = search,
                            onBack = back
                        )
                    }
                    navigation(startDestination = "lists", route = "flashcards") {
                        composable("lists") { FlashcardListsScreen(navController = navController) }
                        composable("elements/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardDetailScreen(
                                listId = listId,
                                navController = navController,
                                onBack = back
                            )
                        }
                        composable("game/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardGameScreen(listId = listId, navController = navController)
                        }
                    }
                }
            }
        }
    }
}

/** A GalleryViewerScreen destination: [source] reads the route's arguments into what it should show. */
private fun NavGraphBuilder.viewerRoute(
    route: String,
    navController: androidx.navigation.NavController,
    onBack: () -> Unit,
    source: (android.os.Bundle?) -> Pair<ViewerSource, Long>
) = composable(route) { backStackEntry ->
    val (viewerSource, initialItemId) = source(backStackEntry.arguments)
    GalleryViewerScreen(
        source = viewerSource,
        initialItemId = initialItemId,
        onBack = onBack,
        onCrop = { id -> navController.navigate("gallery/crop/$id") },
        onTrim = { id -> navController.navigate("gallery/trim/$id") }
    )
}
