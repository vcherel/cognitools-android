package com.example.myapp

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.example.myapp.gallery.LockedQuickView
import com.example.myapp.gallery.MediaItem
import com.example.myapp.gallery.ViewerSource
import com.example.myapp.gallery.hasReadMediaPermission
import com.example.myapp.gallery.queryMediaItemById
import com.example.myapp.gallery.rememberIntentSenderRequester
import com.example.myapp.gallery.resolveMediaTarget
import com.example.myapp.motsfleches.MotsFlechesScreen
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

    // Set when the app is already running and a new intent (e.g. tapping the Deezer notification)
    // asks it to jump somewhere. Compose observes this and navigates once it fires.
    private val pendingRoute = mutableStateOf<String?>(null)

    // Non-null when a picture/video was opened while the phone is still locked (e.g. the camera's
    // just-taken-photo thumbnail). Compose renders LockedQuickView instead of the full app in that
    // case, so the rest of the app (menu, notes, other albums...) stays behind the real lock screen.
    private val lockedQuickViewItem = mutableStateOf<MediaItem?>(null)

    // True for the whole life of an activity instance that has shown a picture over the keyguard.
    // Such an instance never turns back into the full app while the phone is still locked: once the
    // item is gone (deleted, moved, or a later intent pointing at something unreadable), it closes
    // instead, so the albums or another album's pictures can't end up on screen over the lock
    // screen. Survives a recreation, which would otherwise re-enter onCreate with the flag lost and
    // the just-deleted item no longer resolvable.
    private var lockedSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setOnExitAnimationListener { splashScreenView ->
            // Some launch paths (e.g. "open with" from another app) hand back a splash view with
            // no icon view; the compat library's getter throws instead of returning null there.
            val iconView = try { splashScreenView.iconView } catch (e: NullPointerException) { null }
            if (iconView != null) {
                iconView.animate()
                    .scaleX(1.5f)
                    .scaleY(1.5f)
                    .alpha(0f)
                    .setInterpolator(AnticipateInterpolator())
                    .setDuration(500L)
                    .withEndAction { splashScreenView.remove() }
            } else {
                splashScreenView.remove()
            }
        }

        val quickViewItem = resolveLockedQuickViewItem(intent)
        lockedQuickViewItem.value = quickViewItem
        applyShowWhenLocked(quickViewItem != null)
        lockedSession = quickViewItem != null ||
            (savedInstanceState?.getBoolean(STATE_LOCKED_SESSION) == true && isKeyguardLocked())
        if (lockedSession && quickViewItem == null) {
            // Recreated after the item was deleted or moved: there is nothing left to show, and the
            // full app is not an acceptable fallback with the phone still locked.
            finish()
            return
        }

        // Asking for notification permission over the lock screen makes no sense for a quick photo
        // review that never touches anything notification related.
        if (quickViewItem == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Resolved up front, synchronously: a single indexed-row query, fast enough to do before
        // the first frame so the menu is never the destination Compose actually starts on (which
        // would otherwise flash on screen for the frame or two before a post-composition navigate
        // could react to it). Skipped entirely for a locked session: that route opens the item's
        // whole album, which must not be sitting there ready to fire behind the quick view.
        val initialRoute = if (lockedSession) null else {
            resolveInitialGalleryRoute(this, viewMediaUriFrom(intent))
                ?: routeFromIntent(intent)?.let { it to null }
        }

        enableEdgeToEdge()
        setContent {
            val themeManager = remember { ThemeManager(applicationContext) }
            val isDarkMode by themeManager.isDarkMode.collectAsState(initial = false)
            val pendingRoute by pendingRoute
            val lockedQuickViewItem by lockedQuickViewItem

            AppTheme(isDarkMode = isDarkMode) {
                val quickView = lockedQuickViewItem
                if (quickView != null) {
                    // Outside MainScreen, so the locals it normally provides have to be set here.
                    CompositionLocalProvider(LocalIsDarkMode provides isDarkMode) {
                        LockedQuickView(item = quickView, onClose = { finish() })
                    }
                } else {
                    MainScreen(
                        themeManager = themeManager,
                        isDarkMode = isDarkMode,
                        initialRoute = initialRoute?.first,
                        initialRouteMessage = initialRoute?.second,
                        pendingRoute = pendingRoute,
                        onPendingRouteConsumed = { this.pendingRoute.value = null }
                    )
                }
            }
        }
    }

    // The Deezer notification's PendingIntent reuses this activity (singleTask) instead of stacking
    // a new instance, so a route asked for while the app is already running arrives here. Also the
    // path a locked-quick-view intent takes when the process was already alive in the background.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val quickViewItem = resolveLockedQuickViewItem(intent)
        if (quickViewItem != null) {
            lockedSession = true
            lockedQuickViewItem.value = quickViewItem
            applyShowWhenLocked(true)
            return
        }
        // Nothing to show over the keyguard. If this instance is a locked session, that is the end
        // of it: the camera re-sending the picture just deleted, or any other intent, closes the
        // activity rather than revealing the app underneath. The session only ends normally once
        // the phone is actually unlocked.
        if (lockedSession) {
            if (isKeyguardLocked()) {
                finish()
                return
            }
            lockedSession = false
        }
        lockedQuickViewItem.value = null
        applyShowWhenLocked(false)
        routeFromIntent(intent)?.let { pendingRoute.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LOCKED_SESSION, lockedSession)
    }

    // Non-null only when the device is currently locked and this intent points at a single photo
    // or video the app can already read, i.e. exactly the "camera thumbnail tapped while locked"
    // case. Anything else (permission not granted yet, item not resolvable, device unlocked) falls
    // through to the normal full-app flow instead.
    private fun resolveLockedQuickViewItem(intent: Intent): MediaItem? {
        val uri = viewMediaUriFrom(intent) ?: return null
        if (!isKeyguardLocked()) return null
        if (!hasReadMediaPermission(this)) return null
        val (itemId, _) = resolveMediaTarget(this, uri) ?: return null
        return queryMediaItemById(this, itemId)
    }

    private fun isKeyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    // Lets this activity draw over the keyguard without dismissing it, only while it is showing
    // LockedQuickView. Always explicitly set (true or false) rather than only ever turned on, since
    // a singleTask instance reused via onNewIntent must not keep an earlier "true" around for an
    // unrelated later intent.
    private fun applyShowWhenLocked(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else if (show) {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } else {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "com.example.myapp.OPEN_ROUTE"
        const val ROUTE_DEEZER_NOW_PLAYING = "deezer?openPlayer=true"
        private const val STATE_LOCKED_SESSION = "lockedSession"
    }
}

private fun routeFromIntent(intent: Intent): String? =
    intent.getStringExtra(MainActivity.EXTRA_OPEN_ROUTE)

// Set when the app was launched to open a picture or video from another app ("Open with" /
// default handler).
private fun viewMediaUriFrom(intent: Intent): Uri? {
    val type = intent.type ?: return null
    val isMedia = type.startsWith("image/") || type.startsWith("video/")
    return if (intent.action == Intent.ACTION_VIEW && isMedia) intent.data else null
}

// The nav route to jump to once the app starts, plus an optional snackbar to explain a fallback,
// e.g. when the item couldn't be resolved or media permission hasn't been granted yet.
private fun resolveInitialGalleryRoute(context: Context, viewMediaUri: Uri?): Pair<String, String?>? {
    if (viewMediaUri == null) return null
    if (!hasReadMediaPermission(context)) {
        return "gallery" to "Autorisez l'accès aux photos et vidéos pour l'ouvrir directement"
    }
    val target = resolveMediaTarget(context, viewMediaUri) ?: return "gallery" to "Impossible d'ouvrir ce fichier"
    val (itemId, bucketId) = target
    return "gallery/viewer/$bucketId/$itemId" to null
}

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
                            onOpenUndercover = { navController.navigate("undercover") },
                            onOpenVolume = { navController.navigate("volumeBooster") },
                            onOpenRandom = { navController.navigate("randomGenerator") },
                            onOpenWikipedia = { navController.navigate("wikipedia") },
                            onOpenGallery = { navController.navigate("gallery") },
                            onOpenWallet = { navController.navigate("gallery/wallet") },
                            onOpenPinnedPictures = { navController.navigate("gallery/pinned") }
                        )
                    }
                    composable("randomGenerator") {
                        RandomGeneratorScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable("volumeBooster") {
                        VolumeBoosterScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable("undercover") {
                        UndercoverScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable("wikipedia") {
                        WikipediaScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable("weather") {
                        WeatherScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable("motsFleches") {
                        MotsFlechesScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable(
                        "deezer?openPlayer={openPlayer}",
                        arguments = listOf(navArgument("openPlayer") { type = NavType.BoolType; defaultValue = false })
                    ) { backStackEntry ->
                        val openPlayer = backStackEntry.arguments?.getBoolean("openPlayer") ?: false
                        DeezerScreen(
                            onBack = { navController.popBackStackOnce() },
                            openFullPlayerInitially = openPlayer
                        )
                    }
                    composable("gallery") {
                        GalleryAlbumsScreen(
                            onBack = { navController.popBackStackOnce() },
                            onOpenAlbum = { bucketId -> navController.navigate("gallery/album/$bucketId") },
                            onOpenTrash = { navController.navigate("gallery/trash") },
                            onOpenPinned = { navController.navigate("gallery/pinned") }
                        )
                    }
                    composable("gallery/trash") {
                        GalleryTrashScreen(onBack = { navController.popBackStackOnce() })
                    }
                    composable("gallery/album/{bucketId}") { backStackEntry ->
                        val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
                        GalleryAlbumGridScreen(
                            bucketId = bucketId,
                            onBack = { navController.popBackStackOnce() },
                            onOpenItem = { itemId -> navController.navigate("gallery/viewer/$bucketId/$itemId") }
                        )
                    }
                    composable("gallery/viewer/{bucketId}/{itemId}") { backStackEntry ->
                        val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryViewerScreen(
                            source = ViewerSource.Album(bucketId),
                            initialItemId = itemId,
                            onBack = { navController.popBackStackOnce() },
                            onCrop = { id -> navController.navigate("gallery/crop/$id") },
                            onTrim = { id -> navController.navigate("gallery/trim/$id") }
                        )
                    }
                    composable("gallery/pinned") {
                        GalleryViewerScreen(
                            source = ViewerSource.Pinned,
                            initialItemId = -1L,
                            onBack = { navController.popBackStackOnce() },
                            onCrop = { id -> navController.navigate("gallery/crop/$id") },
                            onTrim = { id -> navController.navigate("gallery/trim/$id") }
                        )
                    }
                    composable("gallery/wallet") {
                        GalleryViewerScreen(
                            source = ViewerSource.Wallet,
                            initialItemId = -1L,
                            onBack = { navController.popBackStackOnce() },
                            onCrop = { id -> navController.navigate("gallery/crop/$id") },
                            onTrim = { id -> navController.navigate("gallery/trim/$id") }
                        )
                    }
                    composable("gallery/crop/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryCropScreen(itemId = itemId, onBack = { navController.popBackStackOnce() })
                    }
                    composable("gallery/trim/{itemId}") { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId")?.toLongOrNull() ?: 0L
                        GalleryTrimScreen(itemId = itemId, onBack = { navController.popBackStackOnce() })
                    }
                    composable("notes") {
                        NotesListScreen(navController = navController)
                    }
                    composable("notes/trash") {
                        NotesTrashScreen(onBack = { navController.popBackStackOnce() })
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
                            onBack = { navController.popBackStackOnce() }
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
                                onBack = { navController.popBackStackOnce() }
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
