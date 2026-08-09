# CogniTools Android

Personal Android app (Jetpack Compose), sideloaded on Valentin's phone.

## Workflow
After implementing a change, deploy it on the phone (see below) so Valentin can try it, then end with a summary of what changed. Never commit or push at that point: git operations happen only through /wrap-up or an explicit request.

For UI/layout changes with more than one reasonable arrangement (where to put a control, how a view is structured, an interaction model), sketch 2-3 concrete options up front with AskUserQuestion and let Valentin pick before writing any code. Don't implement your first interpretation and iterate from corrections.

## Language exception
This app's UI text (labels, toasts, snackbars, content descriptions, etc.) is French, deliberately, confirmed with Valentin: the app is French-only, sole user, not a shortcut or an oversight. This overrides the global English-only writing instruction, but only for in-app UI text; replies, code, comments, and commit messages to Valentin stay English as usual.

## Deploy method
Build the release APK and install it on the connected phone:

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Always install release builds, never debug (performance matters on device). Debug is only for `run-as` access, e.g. DB inspection.

## Codebase map
Five independent tools live under `app/src/main/java/com/example/myapp/`. Use this map to jump straight to the right file with Read/Grep instead of spawning an exploration agent.

Root package (shared/misc):
- `MainActivity.kt`: app entry point and the whole nav host
- `MenuScreen.kt`: the main menu, one explicit callback per destination
- `Theme.kt`: ThemeManager (dark mode DataStore), AppTheme, LocalIsDarkMode
- `MyApplication.kt`: Application class, holds the FlashcardRepository and DeezerRepository singletons plus the `Context.flashcardRepository` / `Context.deezerRepository` extensions, and the app start housekeeping
- `Buttons.kt`: shared composables, MyButton, SplitMyButton, MySwitch, ShowAlertDialog, AppDialog (the buttons all built on RaisedSurface, the custom dialogs all on AppDialog)
- `ScreenTopBar.kt`: shared back-arrow + title header used by the tool screens
- `Home.kt`: BackIconButton (tap = back, long press = main menu), the LocalGoHome hook, and the idle-return-to-menu lifecycle watcher with its IdleResetGuard
- `Http.kt`: shared httpGet helper and User-Agent (Weather + Wikipedia)
- `BottomFadeOverlay.kt`: shared fade out gradient overlay composable
- `Snackbar.kt`: AppSnackbar, the app wide snackbar screens post undo actions through
- `BackupRestore.kt`: export/import actions for app data
- `Random.kt`: random number generator tool screen
- `Volume.kt`: volume booster foreground service and its screen
- `Wikipedia.kt`: random Wikipedia article tool screen
- `Weather.kt`: GPS-based weather forecast tool screen
- `WeatherApi.kt`: the Open-Meteo forecast/geocoding calls, the saved city, and the device position

`deezer/` (Deezer streaming client: library, playback, offline mirror):
- `DeezerModels.kt`: DeezerTrack/DeezerPlaylist/DeezerQuality data classes
- `DeezerApi.kt`: low level gw-light gateway + media.deezer.com calls, DeezerApiException
- `DeezerCrypto.kt`: Blowfish stripe decryption of the streams, pure functions covered by a JVM unit test
- `DeezerSettings.kt`: DataStore for the ARL credential and quality
- `DeezerSettingsDialog.kt`: the ARL paste dialog
- `DeezerRepository.kt`: the singleton; session lifecycle, MediaController + player state flow, stream cache, library access, "Best pépites" quick-add
- `DeezerDataSource.kt`: resolves `dzr://<sngId>` to a fresh CDN URL at open() time and decrypts on the fly
- `DeezerLibraryCache.kt`: JSON snapshot of favorites + playlists so a cold launch renders instantly
- `DeezerOffline.kt`: DeezerOfflineLibrary, the permanent Best pépites mirror; sync, retry pass, sync log file
- `DeezerPlaybackService.kt`: MediaSessionService owning the ExoPlayer; media notification, its heart/diamond action buttons (`actionButtons`), error recovery
- `DeezerScreen.kt`: tool host, nested NavHost + persistent mini-player
- `DeezerNowPlaying.kt`: MiniPlayerBar and FullPlayerSheet
- `DeezerLibraryScreen.kt`: landing screen, favorites card, playlists row, offline status
- `DeezerPlaylistScreen.kt`: reusable ordered track list (play, remove, like, add to pépites)
- `DeezerSearchScreen.kt`: search screen
- `DeezerDiscoveries.kt`: the daily "Découvertes du jour" batch; new release scan over the profile artists, Flow/track-mix discoveries, the persisted batch/backlog/proposed state
- `DeezerDiscoveriesScreen.kt`: the batch's list screen (add, ignore, add all, ignore all, regenerate)

`flashcards/` (spaced repetition flashcards tool):
- `Models.kt`: FlashcardList/FlashcardElement data classes, JSON (de)serialization, stats helpers
- `Database.kt`: Room FlashcardDao
- `Repository.kt`: FlashcardRepository, mediates between DB and UI
- `SpacedRepetition.kt`: reviewCard, the spaced repetition scheduling algorithm
- `ListsScreen.kt`: list of flashcard lists, bulk import dialog
- `DetailScreen.kt`: single list detail/edit screen
- `GameScreen.kt`: the review/quiz screen
- `ElementCard.kt`: flashcard flip card composable
- `StatsSheet.kt`: stats bottom sheet (wait time buckets, scores)

`gallery/` (photo/video gallery on top of MediaStore):
- `Models.kt`: MediaItem, Album, MediaType
- `MediaStoreRepository.kt`: all MediaStore reads (queryAlbums/queryMediaItems) and writes (performRename/Move/Delete/Overwrite + batch variants), WriteOutcome
- `GalleryPermissions.kt`: read-media and all-files permission checks, rememberIntentSenderRequester for scoped storage consent prompts
- `GalleryRefresh.kt`: global refresh counter the screens observe after a write
- `TrashScreen.kt`: the trashed items grid (restore, delete for good) and showTrashedSnackbar
- `GalleryImage.kt`: GalleryAsyncImage, the Coil loader with a dateModified aware cache key
- `AlbumsScreen.kt`: album list, plus the pinned-picture hero card at the top
- `AlbumGridScreen.kt`: thumbnail grid, multi-select with drag, batch actions
- `ViewerScreen.kt`: full screen pager (ViewerSource: an album or the pinned set), image zoom and video playback, per-item tools including pin/unpin and set-as-hero
- `ViewerDialogs.kt`: the viewer's share, rename and move dialogs (MoveDialog is also used by the album grid)
- `GalleryPins.kt`: PinnedMediaItem Room entity/DAO and pin/unpin/setHero/resolve helpers
- `CropScreen.kt`: image crop editor
- `TrimScreen.kt`: video trim editor

`motsfleches/` (French arrow-word grids, generated on the phone):
- `Puzzle.kt`: Slot/Puzzle/PuzzleState, the grid model and what has been typed into it
- `ClueDictionary.kt`: loads `assets/motsfleches_dict.txt` and indexes it by length with a bitset per (position, letter), so pattern lookups and the frequency ceiling are cheap
- `PuzzleGenerator.kt`: parses `assets/motsfleches_layouts.txt` and fills a layout by backtracking (most constrained word first). Pure, covered by a JVM unit test
- `MotsFlechesStore.kt`: the single grid in progress, saved as JSON in filesDir, plus the next grid pre-generated in the background
- `PuzzleGrid.kt`: the grid drawn on one Canvas, definitions printed in their cells with arrows, pinch to zoom
- `MotsFlechesScreen.kt`: the tool screen, clue bar and letter keyboard, per-word check and reveal
- The two assets are generated by `src/generate_motsfleches.py` (Lexique + fr.wiktionary) and `src/generate_layouts.py`. Ship the dictionary as plain text: the Android build unpacks a `.gz` asset at packaging time, which renames it out from under the loader.

`notes/` (notes tool, the biggest one):
- `Models.kt`: the Note entity, its JSON (de)serialization, and NoteDao (the Room DAO)
- `NoteText.kt`: everything a note's plain text encodes; the special note titles, checkbox/separator prefixes, formatInline, quantity and waiting-date suffixes, noteTitleAndPreview
- `NotesListScreen.kt`: list of notes screen, with the pinned Todo widget
- `NoteEditorScreen.kt`: the note editor shell; load/save, autosave, undo, edit-mode text field, menus, per-line actions
- `NoteEditing.kt`: pure text helpers; input transformations, slash commands, inline/line marker toggling, muscu day
- `NoteViewMode.kt`: the read-only note rendering; per-line checkbox/separator/text, drag reorder, double-tap to edit. Takes one NoteLineActions from the editor
- `NoteLock.kt`: PIN lock for notes, PinDialog
- `NotesTrashScreen.kt`: the trashed notes screen (restore, delete for good, empty the trash)
- `NoteSyncActions.kt`: the flows the editor triggers across the Courses/Ingrédients/model notes (move, add, re-sort, reconcile) and the batch state behind the reconcile dialog
- `IngredientSync.kt`: the pure text side of that sync: group parsing and rendering (Ingrédients: `Modèle ingrédients`, anonymous blank-line groups; Courses: `Modèle courses`, named "--- Nom" sections), NoteSyncBatch/ReconcileItem, closeness ranking
- `IngredientDialogs.kt`: the reconcile dialog and the add-an-item name prompt

`undercover/` (party game tool):
- `Data.kt`: Player, GameSettings, GameState, MrWhiteScenario, ScoreValues data model
- `Functions.kt`: elimination, win condition, Mr. White guess resolution logic
- `Setup.kt`: player setup screen, role/word assignment
- `MainScreen.kt`: game entry screen
- `PlayScreen.kt`: main play/discussion screen
- `VotingScreen.kt`: voting screen
- `EliminationResultScreen.kt`: result after a vote
- `MrWhiteGuessScreen.kt`: Mr. White's word guess screen
- `GameOverScreen.kt`: end of game screen
- `LeaderboardScreen.kt`: cross game leaderboard
- `Settings.kt`: game settings screen, validateGameSettings

## Cross-cutting things, and where they actually live
The map above is by feature. These are the ones you won't find by feature name:

- **Room**: one database for the whole app, declared in `flashcards/Database.kt` (version 7). The notes `Note` entity and `NoteDao` are registered there too but defined in `notes/Models.kt`; same for the gallery's `PinnedMediaItem`/`PinnedMediaItemDao`, which live in `gallery/GalleryPins.kt`.
- **Media notification and lockscreen buttons**: `deezer/DeezerPlaybackService.kt`, `actionButtons()`. Media3 draws the notification; the buttons are `CommandButton`s handled in `SessionCallback.onCustomCommand`, so they act without opening the app.
- **Icons**: screens use Compose `Icons.*` (material-icons-extended). `res/drawable/` only holds what the framework needs as a real resource: the launcher and the notification action icons. Media3 has no icon constant for most things, so a custom button icon means a vector in `res/drawable/` passed to `setCustomIconResId`.
- **Singletons**: `MyApplication` holds FlashcardRepository and DeezerRepository. Screens reach them as `context.flashcardRepository` / `context.deezerRepository` (extensions declared in `MyApplication.kt`), never by casting. Anything long lived hangs off there, not off an object/DI graph.
- **App start work**: `MyApplication.onCreate` purges the expired trashed notes and calls `FlashcardRepository.seedAndPurge()` (seeds the builtin flashcard lists on a fresh install, drops mastered cards outside them). The repositories themselves assume this has been kicked off.
- **HTTP**: `Http.kt`'s `httpGet` serves Weather and Wikipedia only. Deezer has its own client in `deezer/DeezerApi.kt`, Gallery does no networking.
- **Foreground services**: `Volume.kt` (volume booster) and `deezer/DeezerPlaybackService.kt`. The Deezer offline sync deliberately has none.
- **30 day trash**: two different mechanisms. Notes carry a `deletedAt` timestamp and are purged by `MyApplication.onCreate`. The gallery uses MediaStore's own trash (`IS_TRASHED`, `performTrashBatch`/`performRestoreBatch`), which Android empties by itself; it only exists from API 30 on, below that a delete stays permanent.
- **Media consent launcher**: registered once in `MainActivity` and passed down through `LocalMediaConsent`, so an undo posted after its screen is gone can still show the system dialog. Gallery screens read it instead of calling `rememberIntentSenderRequester` themselves.
