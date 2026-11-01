package com.example.myapp

import android.net.Uri
import android.os.Bundle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapp.calendar.CalendarScreen
import com.example.myapp.deezer.DeezerScreen
import com.example.myapp.files.FilesScreen
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.FlashcardDetailScreen
import com.example.myapp.flashcards.FlashcardGameScreen
import com.example.myapp.flashcards.FlashcardListsScreen
import com.example.myapp.gallery.GalleryAlbumGridScreen
import com.example.myapp.gallery.GalleryAlbumsScreen
import com.example.myapp.gallery.GalleryCropScreen
import com.example.myapp.gallery.GalleryPinnedGridScreen
import com.example.myapp.gallery.GalleryTrashScreen
import com.example.myapp.gallery.GalleryTrimScreen
import com.example.myapp.gallery.LocalMediaConsent
import com.example.myapp.gallery.ViewerSource
import com.example.myapp.gallery.GalleryViewerScreen
import com.example.myapp.gallery.rememberIntentSenderRequester
import com.example.myapp.journal.JournalScreen
import com.example.myapp.mail.MailMessageScreen
import com.example.myapp.mail.MailScreen
import com.example.myapp.motsfleches.MotsFlechesScreen
import com.example.myapp.news.NewsArticleScreen
import com.example.myapp.news.NewsSavedScreen
import com.example.myapp.news.NewsScreen
import com.example.myapp.notes.NoteEditorScreen
import com.example.myapp.notes.NotesListScreen
import com.example.myapp.notes.NotesTrashScreen
import com.example.myapp.notes.findTodoNote
import com.example.myapp.reader.BookLibraryScreen
import com.example.myapp.reader.ReaderScreen
import com.example.myapp.translate.TranslateScreen
import com.example.myapp.undercover.UndercoverScreen
import com.example.myapp.workout.WorkoutScreen
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
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

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
                NavHost(
                    navController = navController,
                    startDestination = "menu",
                    enterTransition = { screenEnter },
                    exitTransition = { screenExit }
                ) {
                    screen("menu") {
                        MenuScreen(
                            isDarkMode = isDarkMode,
                            onToggleDarkMode = {
                                coroutineScope.launch { themeManager.setDarkMode(!isDarkMode) }
                            },
                            onOpenNotes = { navController.navigate("notes") },
                            onOpenTodoNote = {
                                coroutineScope.launch {
                                    val todo = findTodoNote(AppDatabase.get(context).noteDao())
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
                            onOpenGallery = { navController.navigate("gallery") },
                            onOpenWallet = { navController.navigate("gallery/wallet") },
                            onOpenPinnedPictures = { navController.navigate("gallery/pinned") },
                            onOpenTool = { route -> navController.navigate(route) }
                        )
                    }
                    screen("randomGenerator") { RandomGeneratorScreen(onBack = back) }
                    screen("volumeBooster") { VolumeBoosterScreen(onBack = back) }
                    screen("undercover") { UndercoverScreen(onBack = back) }
                    screen("wikipedia") { WikipediaScreen(onBack = back) }
                    screen("files") {
                        FilesScreen(onBack = back, onOpenMedia = { id -> navController.navigate("gallery/item/$id") })
                    }
                    screen("weather") { WeatherScreen(onBack = back) }
                    screen("news") {
                        NewsScreen(
                            onBack = back,
                            onOpenArticle = { link -> navController.navigate("news/article/${Uri.encode(link)}") },
                            onOpenSaved = { navController.navigate("news/saved") }
                        )
                    }
                    screen("news/saved") {
                        NewsSavedScreen(
                            onBack = back,
                            onOpenArticle = { link -> navController.navigate("news/article/${Uri.encode(link)}") }
                        )
                    }
                    screen("news/article/{link}") { backStackEntry ->
                        val link = backStackEntry.arguments?.getString("link").orEmpty()
                        NewsArticleScreen(link = link, onBack = back)
                    }
                    screen("journal") {
                        JournalScreen(
                            onBack = back,
                            onOpenArticle = { link -> navController.navigate("news/article/${Uri.encode(link)}") }
                        )
                    }
                    screen("mail") {
                        MailScreen(onBack = back, onOpenMessage = { uid -> navController.navigate("mail/message/$uid") })
                    }
                    screen("mail/message/{uid}") { backStackEntry ->
                        val uid = backStackEntry.arguments?.getString("uid")?.toLongOrNull() ?: 0L
                        MailMessageScreen(uid = uid, onBack = back)
                    }
                    screen(
                        "workout?pickDay={pickDay}",
                        arguments = listOf(navArgument("pickDay") { type = NavType.BoolType; defaultValue = false })
                    ) { backStackEntry ->
                        WorkoutScreen(onBack = back, pickDayInitially = backStackEntry.arguments?.getBoolean("pickDay") ?: false)
                    }
                    screen(
                        "calendar?day={day}",
                        arguments = listOf(navArgument("day") { type = NavType.StringType; defaultValue = "" })
                    ) { backStackEntry ->
                        CalendarScreen(onBack = back, initialDay = backStackEntry.arguments?.getString("day")?.ifEmpty { null })
                    }
                    screen("motsFleches") { MotsFlechesScreen(onBack = back) }
                    screen("translate") { TranslateScreen(onBack = back) }
                    screen("reader") {
                        BookLibraryScreen(
                            onBack = back,
                            onOpenBook = { bookId -> navController.navigate("reader/book/$bookId") }
                        )
                    }
                    screen("reader/book/{bookId}") { backStackEntry ->
                        val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
                        ReaderScreen(bookId = bookId, onBack = back)
                    }
                    screen(
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
                    screen("gallery") {
                        GalleryAlbumsScreen(
                            onBack = back,
                            onOpenAlbum = { bucketId -> navController.navigate("gallery/album/$bucketId") },
                            onOpenTrash = { navController.navigate("gallery/trash") },
                            onOpenPinned = { navController.navigate("gallery/pinned") },
                            onOpenPinnedGrid = { navController.navigate("gallery/pinned/grid") }
                        )
                    }
                    screen("gallery/trash") { GalleryTrashScreen(onBack = back) }
                    screen("gallery/album/{bucketId}") { backStackEntry ->
                        val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
                        GalleryAlbumGridScreen(
                            bucketId = bucketId,
                            onBack = back,
                            onOpenItem = { itemId -> navController.navigate("gallery/viewer/$bucketId/$itemId") }
                        )
                    }
                    // The viewer entry points differ only in where their items come from.
                    viewerRoute("gallery/viewer/{bucketId}/{itemId}", navController, back) { args ->
                        ViewerSource.Album(args?.getString("bucketId")?.toLongOrNull() ?: 0L) to
                            (args?.getString("itemId")?.toLongOrNull() ?: 0L)
                    }
                    screen("gallery/pinned/grid") {
                        GalleryPinnedGridScreen(
                            onBack = back,
                            onOpenItem = { itemId -> navController.navigate("gallery/pinned/item/$itemId") }
                        )
                    }
                    viewerRoute("gallery/pinned", navController, back) { ViewerSource.Pinned to -1L }
                    viewerRoute("gallery/pinned/item/{itemId}", navController, back) { args ->
                        ViewerSource.Pinned to (args?.getString("itemId")?.toLongOrNull() ?: -1L)
                    }
                    viewerRoute("gallery/wallet", navController, back) { ViewerSource.Wallet to -1L }
                    viewerRoute("gallery/item/{itemId}", navController, back) { args ->
                        val itemId = args?.getString("itemId")?.toLongOrNull() ?: -1L
                        ViewerSource.Single(itemId) to itemId
                    }
                    screen("gallery/crop/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryCropScreen(itemId = itemId, onBack = back)
                    }
                    screen("gallery/trim/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryTrimScreen(itemId = itemId, onBack = back)
                    }
                    screen("notes") { NotesListScreen(navController = navController) }
                    screen("notes/trash") { NotesTrashScreen(onBack = back) }
                    screen(
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
                        screen("lists") { FlashcardListsScreen(navController = navController) }
                        screen("elements/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardDetailScreen(
                                listId = listId,
                                navController = navController,
                                onBack = back
                            )
                        }
                        screen("game/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardGameScreen(listId = listId, navController = navController)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A destination that lends its tool's color and icon to the headers and buttons under it. Taken
 * from its own route, not the current one: the screen being left keeps its colors while it fades.
 */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit
) = composable(route, arguments) { backStackEntry ->
    CompositionLocalProvider(LocalToolAccent provides ToolAccents.forRoute(route)) {
        content(backStackEntry)
    }
}

/** A GalleryViewerScreen destination: [source] reads the route's arguments into what it should show. */
private fun NavGraphBuilder.viewerRoute(
    route: String,
    navController: NavController,
    onBack: () -> Unit,
    source: (Bundle?) -> Pair<ViewerSource, Long>
) = screen(route) { backStackEntry ->
    val (viewerSource, initialItemId) = source(backStackEntry.arguments)
    GalleryViewerScreen(
        source = viewerSource,
        initialItemId = initialItemId,
        onBack = onBack,
        onCrop = { id -> navController.navigate("gallery/crop/$id") },
        onTrim = { id -> navController.navigate("gallery/trim/$id") }
    )
}

// Navigation's default is a 700 ms crossfade, long enough that opening a screen felt slow and
// heavy enough to drop most frames on the phone. Every NavHost in the app uses these instead.
internal val screenEnter = fadeIn(tween(150))
internal val screenExit = fadeOut(tween(150))
