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
The independent tools live under `app/src/main/java/com/example/myapp/`. Use this map to jump straight to the right file with Read/Grep instead of spawning an exploration agent. Keep it current when files are added or renamed: a stale map misleads more than no map.

Outside that package: `src/` holds the Python asset generators (see the README), `data/` their inputs, `app/src/test/` the JVM unit tests, `baselineprofile/` the startup profile module.

Root package (shared/misc):
- `MainActivity.kt`: app entry point; the window, the intents it is launched with, and the locked-over-the-keyguard quick view
- `AppNavHost.kt`: MainScreen, the whole app's nav graph plus the composition locals and the snackbar host every screen reads
- `MenuScreen.kt`: the main menu; the big tools on top with their own callbacks, the rest a scrolling grid of MenuTool ranked by use and opened through one `onOpenTool(route)`
- `MenuUsage.kt`: MenuUsageStore, the per-tool tap counters that order the menu grid
- `Theme.kt`: ThemeManager (dark mode DataStore), AppTheme, LocalIsDarkMode
- `MyApplication.kt`: Application class, holds the FlashcardRepository / DeezerRepository / PodcastRepository singletons plus their `Context.<name>Repository` extensions, and the app start housekeeping
- `Buttons.kt`: shared composables, MyButton, SplitMyButton, MySwitch, ShowAlertDialog, ErrorText, AppDialog (the buttons all built on RaisedSurface, the custom dialogs all on AppDialog)
- `ScreenTopBar.kt`: shared back-arrow + title header used by the tool screens
- `Home.kt`: BackIconButton (tap = back, long press = main menu), the LocalGoHome hook, and the idle-return-to-menu lifecycle watcher with its IdleResetGuard
- `PlayerUi.kt`: the surfaces `deezer/` and `podcasts/` both draw; MediaArt, MediaListRow, MiniPlayerBar, PlayPauseButton, PlayerSeekBar (which owns the position polling), formatPlaybackTime
- `MediaControllerHolder.kt`: the MediaController connect-once/stop-the-service plumbing both playback repositories use
- `Plural.kt`: `plural(count)`, the one place the French count-to-plural rule lives
- `Normalize.kt`: `deaccented` / `matchNormalized` / `slugified`, the one place text is folded for comparison
- `Http.kt`: shared httpGet helper and User-Agent (Weather + Wikipedia + podcast feeds + news)
- `Errors.kt`: `userMessage(throwable)`, what a failed job says on screen; rethrows cancellation so a screen left mid-request never shows an error
- `Share.kt`: `shareUrisIntent`, the one place an ACTION_SEND / ACTION_SEND_MULTIPLE is built (gallery + file explorer)
- `BottomFadeOverlay.kt`: shared fade out gradient overlay composable
- `Snackbar.kt`: AppSnackbar, the app wide snackbar screens post undo actions through
- `SearchHistory.kt`: recent search terms per surface (notes, cities, Deezer, news) and the RecentSearchChips row
- `BackupRestore.kt`: export/import actions for app data
- `Random.kt`: random number generator tool screen
- `Volume.kt`: volume booster foreground service and its screen
- `Wikipedia.kt`: random Wikipedia article tool screen
- `Weather.kt`: GPS-based weather forecast tool screen, layout only
- `WeatherFormat.kt`: what that screen prints; the day labels, the emoji per condition code, the rain amounts, the day summary and the error message
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
- `DeezerScreen.kt`: host for the whole Musique tool (music *and* podcasts), nested NavHost + the two persistent mini-players
- `DeezerNowPlaying.kt`: FullPlayerSheet, plus the share-a-track sheet. The mini-player bar itself is the shared one in `PlayerUi.kt`
- `DeezerLibraryScreen.kt`: landing screen; favorites card, playlists rows, followed podcast rows, offline status. Also holds TrackRow and the playlist picker every Deezer screen reuses
- `DeezerPlaylistScreen.kt`: reusable ordered track list (play, remove, like, add to pépites)
- `DeezerSearchScreen.kt`: search screen, tracks and podcast shows
- `DeezerDiscoveries.kt`: the daily "Découvertes du jour" batch; new release scan over the profile artists, Flow/track-mix discoveries, the persisted batch/backlog/proposed state
- `DeezerDiscoveriesScreen.kt`: the batch's list screen (add, ignore, add all, ignore all, regenerate)

`podcasts/` (podcast subscriptions and playback, surfaced inside the Musique tool):
- `Models.kt`: PodcastFavorite/PodcastEpisode/PodcastCatalogItem/PodcastEpisodeProgress/PodcastDownload and PodcastDao
- `PodcastApi.kt`: the iTunes directory search and the RSS feed parsing
- `PodcastRepository.kt`: the singleton; followed shows (Room), the merged episode list re-fetched live from each feed, heard/seen state, listening progress, the MediaController. Downloads and the sleep timer are the two objects below, reached as `repo.downloads` and `repo.sleepTimer`
- `PodcastDownloads.kt`: the download queue and what counts as downloaded (derived from the bytes held, never a flag), the one-at-a-time worker, the migration off the old file-per-download layout, and `openAudio` (the by-hand redirect following podcast enclosures need)
- `PodcastSleepTimer.kt`: the timer that pauses playback, plus the pre-fetch, the coverage badge and the watchdog that make sure the audio to reach its end is on the phone
- `PodcastStreamCache.kt`: the one store for podcast audio, keyed by the episode's audio URL, read and written by playback, the sleep timer pre-fetch and the downloads alike. A download is the whole resource held plus a protected key, which its custom evictor never evicts and never counts against the LRU cap
- `PodcastPlaybackService.kt`: MediaSessionService owning the episode ExoPlayer; its own notification id and channel, distinct from the Deezer one
- `PodcastDownloadService.kt`: foreground service holding the download notification (progress, cancel) and a wake lock, so a download survives the lock screen and the app closing. The work itself stays in the repository
- `PodcastDownloadsScreen.kt`: every downloaded episode, all shows merged, read from the downloads table
- `PodcastNowPlaying.kt`: PodcastFullPlayerSheet, the mark-heard toggle, the sleep timer dialog and its coverage badge
- `PodcastEpisodesScreen.kt`: one followed show's episode list (play, download, mark heard, unfollow)

`files/` (file explorer, one folder at a time):
- `FileOps.kt`: the java.io.File side; listing/sorting a folder, the breadcrumb chain, size/date formatting, rename/create/delete/copy/move, and the FileProvider intents that open or share a file
- `FileDefaults.kt`: the app chosen for each extension, captured from the system "Ouvrir avec" sheet by its receiver and stored in DataStore
- `FilesScreen.kt`: the screen; breadcrumb, long press selection with its action bar, the cut/copy clipboard bar, the rename and new-folder dialogs

`flashcards/` (spaced repetition flashcards tool):
- `Models.kt`: FlashcardList/FlashcardElement data classes, JSON (de)serialization, stats helpers
- `AddToFlashcards.kt`: the shared filing dialog (list picker + editable card) the translator and the mots fleches clue bar both open
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
- `TrashScreen.kt`: the trashed items grid (multi-select with drag, restore, delete for good) and showTrashedSnackbar
- `GalleryImage.kt`: GalleryAsyncImage, the Coil loader with a dateModified aware cache key
- `GalleryMediaViewers.kt`: the zoomable image and the video player the viewer pages are made of
- `LockedQuickView.kt`: the single item shown over the keyguard, with the gallery tools that make sense there
- `AlbumsScreen.kt`: album list, plus the pinned-picture hero card at the top (tap opens the viewer, its grid button the pinned grid)
- `PinnedGridScreen.kt`: every pinned picture as a grid; tap opens the viewer on it, long press starts the same multi-selection as an album grid (sweep included), whose bar detaches instead of pinning
- `AlbumGridScreen.kt`: thumbnail grid, batch actions, and `sweepSelection`, the long-press-and-drag multi-select the trash grid reuses
- `ViewerScreen.kt`: full screen pager (ViewerSource: an album, the pinned set, the Wallet album or a single item), image zoom and video playback, per-item tools including pin/unpin and set-as-hero
- `ViewerDialogs.kt`: `ViewerDialog`/`ViewerDialogs`, the viewer's whole dialog run, plus the share, rename, move and info dialogs themselves (MoveDialog is also used by the album grid)
- `GalleryPins.kt`: PinnedMediaItem Room entity/DAO and pin/unpin/setHero/resolve helpers
- `GalleryLock.kt`: the albums put behind the notes PIN, kept as a set of bucket ids in DataStore
- `CropScreen.kt`: image crop editor
- `TrimScreen.kt`: video trim editor

`motsfleches/` (arrow-word grids, French or English, generated on the phone):
- `Puzzle.kt`: Slot/Puzzle/PuzzleState, the grid model and what has been typed into it
- `ClueDictionary.kt`: loads `assets/motsfleches_dict_<lang>.txt` and indexes it by length with a bitset per (position, letter), so pattern lookups and the frequency ceiling are cheap
- `PuzzleGenerator.kt`: parses `assets/motsfleches_layouts.txt` and fills a layout by backtracking (most constrained word first). Pure, covered by a JVM unit test
- `MotsFlechesStore.kt`: MotsFlechesLang (FR/EN, one dictionary asset and one save file each), the language setting, the grid in progress per language saved as JSON in filesDir, plus the next grid pre-generated in the background
- `PuzzleGrid.kt`: the grid drawn on one Canvas, definitions printed in their cells with arrows, pinch to zoom
- `MotsFlechesScreen.kt`: the tool screen, clue bar and letter keyboard, per-word check and reveal
- The assets are generated by `src/generate_motsfleches.py` (Lexique + fr.wiktionary), `src/generate_motsfleches_en.py` (wordfreq + WordNet glosses) and `src/generate_layouts.py`, the layouts being shared by both languages. What the two dictionary scripts have in common (the size limits, the clue shortening, the asset format) lives in `src/motsfleches_common.py`. Ship the dictionary as plain text: the Android build unpacks a `.gz` asset at packaging time, which renames it out from under the loader.

`news/` (news reader, French and world headlines from RSS):
- `NewsModels.kt`: NewsArticle, the NewsRead/NewsSaved/NewsProgress Room entities and NewsDao, plus the 7 day retention shared by the read marks and the reading positions
- `NewsFeeds.kt`: the hardcoded categories (À la une, France, Monde, Éco, Tech, Sciences), the outlet feeds each one merges (Le Monde, franceinfo, Le Figaro; France 24 is deliberately absent, its article bodies are client rendered and never extractable), and `NewsSources`, the DataStore set of outlets actually fetched (franceinfo alone by default, the others opt-in from the screen's overflow menu), plus the franceinfo sections "charger plus" pages through
- `NewsApi.kt`: RSS/Atom parsing, the canonical article link, `parseArchivePage` (franceinfo's own section pages, the only way to articles older than a feed's fixed window) and `extractArticle`, the jsoup readable-body extraction. Pure, covered by a JVM unit test
- `NewsRepository.kt`: the singleton; the merged articles per category with their freshness window, the read state, the saved articles and their offline text, and the reading position of the article left unfinished
- `NewsScreen.kt`: the tool screen; category tabs, the resume card on top, article list, search across everything loaded
- `NewsArticleScreen.kt`: one article read in the app, with save/open in the browser, the progress bar under the header, the restore-where-you-stopped scroll, and the paywall fallback
- `NewsSavedScreen.kt`: the starred articles, readable offline
- `NewsUi.kt`: NewsArticleRow, NewsResumeCard, the relative date formatting and the browser intent

`notes/` (notes tool, the biggest one):
- `Models.kt`: the Note entity, its JSON (de)serialization, and NoteDao (the Room DAO)
- `NoteText.kt`: everything a note's plain text encodes; the special note titles, checkbox/separator prefixes, formatInline, quantity and waiting-date suffixes, noteTitleAndPreview
- `NotesListScreen.kt`: list of notes screen, with the pinned Todo widget
- `NoteEditorScreen.kt`: the note editor's layout; the edit-mode text field, the read-only view, the in-note search bar and NoteEditorDialogs (the two PIN dialogs, the reconcile run, the add-an-item prompt)
- `NoteEditorState.kt`: NoteEditorState and `rememberNoteEditorState`, everything about the note itself; load, autosave, undo stack, saveContent, the lock and its PIN gate, and the fake blank lines edit mode pads the text with
- `NoteEditorTopBar.kt`: the editor's header; the editable title plus every button and menu entry, driven by a NoteEditorBarState/NoteEditorBarActions pair
- `NoteLineEdits.kt`: the per-line edits the read-only view makes (toggle, quantity, muscu day, delete with undo), all going through one editLines
- `NoteEditing.kt`: pure text helpers; input transformations, slash commands, inline/line marker toggling, muscu day
- `NoteViewMode.kt`: the read-only note rendering; per-line checkbox/separator/text, drag reorder, double-tap to edit. Takes one NoteLineActions from the editor
- `NoteLock.kt`: the app's one PIN (notes *and* locked gallery albums, see `gallery/GalleryLock.kt`) and PinDialog
- `NotesTrashScreen.kt`: the trashed notes screen (restore, delete for good, empty the trash)
- `NoteSyncActions.kt`: the flows the editor triggers across the Courses/Ingrédients/model notes (move, add, re-sort, reconcile) and the batch state behind the reconcile dialog
- `IngredientSync.kt`: the pure text side of that sync: group parsing and rendering (Ingrédients: `Modèle ingrédients`, anonymous blank-line groups; Courses: `Modèle courses`, named "--- Nom" sections), NoteSyncBatch/ReconcileItem, closeness ranking
- `IngredientDialogs.kt`: the reconcile dialog and the add-an-item name prompt

`reader/` (epub reader):
- `Epub.kt`: the whole epub format side, no library: java.util.zip opens the archive, jsoup parses the OPF manifest, the table of contents and the chapter markup into TextBlock/InlineSpan. Pure, covered by a JVM unit test
- `BookModels.kt`: the Book Room entity and BookDao, plus import (copy the picked file into `filesDir/books`, read its metadata, extract the cover) and delete
- `ReaderStore.kt`: the font size setting, shared by every book
- `BookLibraryScreen.kt`: the cover grid, epub import, long press to delete
- `ReaderScreen.kt`: the reading screen; one chapter scrolled continuously, controls shown on tap, chapters sheet, progress written back when the scrolling settles, long press a word to open the translator's lookup sheet

`translate/` (translator):
- `TranslateApi.kt`: the keyless `translate.googleapis.com/translate_a/single` call (the endpoint Google's own web widget uses), its bare-array response parsed into a translation, the detected language and the dictionary entries
- `TranslateStore.kt`: the target language and the recent lookups (the flashcard list is fixed: the big button always files into Anglais)
- `TranslateUi.kt`: the pieces both entry points share, TranslationCard, AddToFlashcardsButton, the TextToSpeech reader, and WordLookupSheet (what the reader opens)
- `TranslateScreen.kt`: the tool screen, debounced live translation and the history

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

- **Room**: one database for the whole app, declared in `flashcards/Database.kt` (version 15). The notes `Note` entity and `NoteDao` are registered there too but defined in `notes/Models.kt`; same for the gallery's `PinnedMediaItem`/`PinnedMediaItemDao` in `gallery/GalleryPins.kt`, the podcasts' tables in `podcasts/`, the reader's `Book`/`BookDao` in `reader/BookModels.kt`, and the news tool's `NewsRead`/`NewsSaved`/`NewsProgress`/`NewsDao` in `news/NewsModels.kt`.
- **Word to flashcard loop**: the reader, the translator and the flashcards are one chain. Long pressing a word in `reader/ReaderScreen.kt` opens `translate/WordLookupSheet`, which writes a `FlashcardElement` into the list used last (remembered in `TranslateStore`). The reader is also the only screen that calls `SuppressIdleReset`.
- **Media notification and lockscreen buttons**: `deezer/DeezerPlaybackService.kt`, `actionButtons()`. Media3 draws the notification; the buttons are `CommandButton`s handled in `SessionCallback.onCustomCommand`, so they act without opening the app. `podcasts/PodcastPlaybackService.kt` does the same with its ±30 s pair.
- **Two playback stacks, one player at a time**: music (`deezer/`) and podcasts (`podcasts/`) each have their own repository, service, notification and mini-player, and they are deliberately mutually exclusive: `DeezerRepository.stopPodcastPlayback()` and `PodcastRepository.playEpisode()` stop the other one, because two foreground services fighting over audio focus used to take the app down. Their two services must also keep **different notification ids and channels** (see either `onCreate`). What they share lives in the root package: `PlayerUi.kt` (every surface) and `MediaControllerHolder.kt` (the connection and the stop).
- **Icons**: screens use Compose `Icons.*` (material-icons-extended). `res/drawable/` only holds what the framework needs as a real resource: the launcher and the notification action icons. Media3 has no icon constant for most things, so a custom button icon means a vector in `res/drawable/` passed to `setCustomIconResId`.
- **Singletons**: `MyApplication` holds FlashcardRepository, DeezerRepository, PodcastRepository and NewsRepository. Screens reach them as `context.flashcardRepository` / `context.deezerRepository` / `context.podcastRepository` / `context.newsRepository` (extensions declared in `MyApplication.kt`), never by casting. Anything long lived hangs off there, not off an object/DI graph.
- **App start work**: `MyApplication.onCreate` purges the expired trashed notes and the expired news read marks and reading positions, calls `FlashcardRepository.seedAndPurge()` (seeds the builtin flashcard lists on a fresh install, drops mastered cards outside them), primes the saved discoveries batch, and sweeps the stream cache of heard podcast episodes. The repositories themselves assume this has been kicked off.
- **HTTP**: `Http.kt`'s `httpGet` serves Weather, Wikipedia, the podcast feeds/directory and the news feeds/pages. Deezer has its own client in `deezer/DeezerApi.kt`, podcast episode audio is fetched by hand in `PodcastDownloads.openAudio` (tracking prefixes need manual redirect following), Gallery does no networking.
- **All files access**: `gallery/GalleryPermissions.kt` owns the MANAGE_EXTERNAL_STORAGE check and the settings requester; the file explorer reads them from there rather than duplicating the check.
- **Foreground services**: `Volume.kt` (volume booster), `deezer/DeezerPlaybackService.kt`, `podcasts/PodcastPlaybackService.kt` and `podcasts/PodcastDownloadService.kt`. The Deezer offline sync deliberately has none.
- **30 day trash**: two different mechanisms. Notes carry a `deletedAt` timestamp and are purged by `MyApplication.onCreate`. The gallery uses MediaStore's own trash (`IS_TRASHED`, `performTrashBatch`/`performRestoreBatch`), which Android empties by itself; it only exists from API 30 on, below that a delete stays permanent.
- **Media consent launcher**: registered once in `MainActivity` and passed down through `LocalMediaConsent`, so an undo posted after its screen is gone can still show the system dialog. Gallery screens read it instead of calling `rememberIntentSenderRequester` themselves.
- **Text folding**: any "are these the same thing?" comparison goes through `Normalize.kt`. `deaccented()` is the base (NFD, marks stripped, lowercased), `matchNormalized()` also drops punctuation and is what `DeezerTrack.matchKey` and the podcast title matching use, `slugified()` builds URL path segments. Do not hand-roll another Normalizer call.
