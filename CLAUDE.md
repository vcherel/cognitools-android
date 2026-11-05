# CogniTools Android

Personal Android app (Jetpack Compose), sideloaded on Valentin's phone.

## Workflow
After implementing a change, check it compiles with `./gradlew compileReleaseKotlin` (no APK: R8 and packaging are the slow part, and `deploy.py` builds the release anyway) and end with a summary of what changed, without installing it: Valentin often batches several changes and deploys once, through /wrapup or an explicit ask. Never commit or push at that point either.

Run `git push` with `run_in_background`: the pre-push hook runs the JVM unit tests through Gradle, and a push touching the mots fléchés generator (`PuzzleGenerator.kt`, `ClueDictionary.kt`, `Puzzle.kt`, its assets or its test) adds the 2 minute generator test, past the 120 s foreground timeout. To check a change before pushing, run `testReleaseUnitTest -PskipPuzzleTests`, as the hook does; run `PuzzleGeneratorTest` only when the generator itself changed.

Any flashcard whose content is edited (text, answer or picture) gets its progress reset, since Valentin may have learned it the wrong way. For a builtin card, fix it in its seed asset and append its id to `RESETS` in `flashcards/Repository.kt` with the next version number; at the next app start the phone's copy is replaced once by the shipped one, progress wiped. A user card lives only on the phone: fix it with an import file holding the corrected cards with zeroed progress (the import upserts by id), and run the import yourself with `uv run --no-project src/import_flashcards.py <file>`, rather than leaving it to Valentin. A flag picture that gives its own name away is painted over through `NAME_BOXES` (or `NAME_RINGS` for a name written around a seal) in `src/generate_flags.py`.

For UI and layout choices (where to put a control, how a view or dialog is arranged), do not ask Valentin to pick between designs: choose the plainest arrangement that fits the existing screens and build it.

## Language exception
The UI is English, except the Undercover tool, which stays French on purpose: its screens and dialogs pass their own French labels to the shared composables. Content French by nature stays French too: the mots fléchés FR grids and clues, the flashcard seed decks, the Random tool's word list, the French Wikipedia and news text and the parsers that match it, and the names the code matches against the phone's data (the Courses, Ingrédients, Modèle courses and Todo list notes with their `--- Attente` and `--- Week-end` sections, the Best pépites playlist, the Anglais flashcard list, the mail subjects the laptop reads). Dates are formatted with a French locale (French day and month names, weeks starting on Monday in the pickers), the rest of the text around them in English. `src/phone.py`, `frame_stats.py`, `regenerate_profile.py` and the baseline profile journey find screens by their English labels, so renaming a label means updating them.

A complaint about the Music tool's errors starts with `uv run --no-project src/deezer_logs.py`, which pulls every log the tool writes (errors, crossfade, offline sync, discoveries) from `/sdcard/Android/data/com.example.myapp/files/` and groups the error and crossfade lines by kind, with the tracks refused on several days.

A map card complaint starts the same way, with `uv run --only-group maps src/generate_maps.py --sheet <codes>` from the repo root, opened for Valentin. A flag complaint starts with `uv run --no-project --with pillow python src/generate_flags.py --sheet <codes>` from the repo root, opened for Valentin: it puts the shipped picture next to a fresh render and writes nothing to the assets, so the same command previews a generator change before the full run. `--write <codes>` then writes only those pictures into the assets, without downloading the whole list again. After changing the map generator's pages, `--pages` lists where every country lands, without rendering.

The phone's screen is 360 x 825 dp (720 x 1650 px at density 2.0): size UI mockups and fit checks to that width. Mockups go in the gitignored `mockups/` folder and render with `uv run --no-project src/mockup.py <page.html>... --size W,H`, headless Firefox with Inter and Material Symbols cached locally. Build every tool screen mockup on `src/mockup_base.css` (the script copies it next to the page as `app.css`, link it): the real header, cards, raised buttons, dialog and type, with `--c` set to the tool's color. A first mockup in a look of its own was rejected for not matching the app.

A layout complaint starts with `adb exec-out screencap -p`, never with the code. A black capture means the phone is locked: ask Valentin to unlock it and open the screen, then capture again. To capture a screen of your own choosing (a reference for a mockup), ask Valentin first, and check what is in front with `adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'` before capturing: the recents view or another app ends up in the picture.

JSON state is written with `org.json` by hand (or kotlinx `JsonElement` navigation, as in `deezer/`): the build has no kotlinx serialization compiler plugin, so `@Serializable` and `serializer()` do not compile.

## Deploy method
At /wrapup, check `adb devices` first. With the phone plugged in, do everything: run `uv run --no-project src/deploy.py` in the background, which builds the release APK, installs it and compiles it (`--no-build` reuses the last build). Without the phone, build no APK: `./gradlew compileReleaseKotlin` plus the pre-push tests are enough, and the deploy waits for the next wrapup with the phone. The script runs these steps:

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE com.example.myapp/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile -m speed-profile -f com.example.myapp
```

The last two lines matter: `adb install` leaves the app uncompiled (`status=verify`) until the phone's overnight idle job, which costs about 25% on cold start and makes everything run on the JIT. The broadcast installs the baseline profile shipped in the APK, the compile applies it; compiling without the broadcast first compiles an empty profile. Full `-m speed` measured slower than `speed-profile`. Neither touches app data.

Regenerate the baseline profile with `uv run --no-project src/regenerate_profile.py`, run in the background: it builds the APKs first, then needs the phone unlocked for about 5 minutes (notify Valentin when it waits on the unlock), keeps MIUI awake with a SHIFT keypress every 10 s (KEYCODE_WAKEUP does not reset the timeout), fails if a screen the journey opens is missing from `baseline-prof.txt` (a step that misses its label is skipped silently), and trashes any test note the journey left behind. It runs on the real data; backing the data dir up first is optional and slow, the stream caches make it about 6 GB (install the debug APK over the release, `adb exec-out run-as com.example.myapp tar cf - .`). Then build and deploy the release as above.

A "this screen feels slow" complaint starts by asking which gesture (opening it, scrolling it, the first scroll after a cold start), never with a guess at the code. Then `uv run --no-project src/frame_stats.py 3 --only notes` (or `--only flashcards`, whose review answers real cards and undoes them) before and after, in the same session, gives the janky frames and the per stage times per navigation step. Read the stage times, not just the janky count: a smooth warm scroll here is about 3 ms of ui and 6 ms of draw at 90 Hz, and the card shadows and Material checkboxes measured under 1 ms each (Nov 2026). For a screen the journey does not cover, add a journey function.

Always install release builds, never debug (performance matters on device). Debug is only for `run-as` access, e.g. DB inspection.

## Codebase map
The independent tools live under `app/src/main/java/com/example/myapp/`. Use this map to jump straight to the right file with Read/Grep instead of spawning an exploration agent. Keep it current when files are added or renamed: a stale map misleads more than no map.

Outside that package: `src/` holds the Python asset generators (see the README): `generate_flags.py` builds the Drapeaux list (`assets/flags/` and `assets/seed_flags.json`, from flagcdn), `generate_maps.py` the Pays (carte), Départements (carte) and Régions (carte) lists (`assets/maps/`, `assets/seed_maps.json`, `assets/seed_dept_maps.json` and `assets/seed_region_maps.json`: a square atlas page per Drapeaux place, the same page for a whole continent, with a zoom lens on anything too small to read there, a France map per department with a Paris zoom for the tiny ones, a France map per region), `generate_pairs.py` builds the Undercover `assets/pairs.json` (`generate_dataset.ipynb` at the root only drives it), the mots fléchés scripts are listed under `motsfleches/` below, `deploy.py` builds, installs and compiles the release, `gradle.py` runs Gradle for it and `regenerate_profile.py` with one progress line per task (the totals from the last run, in `build/`), `mockup.py` renders HTML mockups to PNG (`mockup_base.css` their shared app styles), `workout_figures.py` renders the workout stick figures from `workout/ExerciseFigure.kt` into `mockups/workout_figures.png` (open it for Valentin after any pose change), `deezer_logs.py` pulls and summarizes the music tool's logs, `phone.py` drives the app over adb for `frame_stats.py` (janky frames per navigation step) `regenerate_profile.py` (records and checks the baseline profile) and `import_flashcards.py` (pushes a flashcards bundle and imports it through `flashcards/FlashcardImportReceiver.kt`, a broadcast only the adb shell can send). `data/` holds their inputs, `app/src/test/` the JVM unit tests, `baselineprofile/` the startup profile module (`BaselineProfileGenerator.kt`, the journey the profile is recorded from).

Root package (shared/misc):
- `MainActivity.kt`: app entry point; the window, the intents it is launched with, and the locked-over-the-keyguard quick view
- `AppNavHost.kt`: MainScreen, the whole app's nav graph plus the composition locals and the snackbar host every screen reads
- `MenuScreen.kt`: the main menu, sized to fill the screen without scrolling; every tool ranked by use in one order, a big tool (with its own callbacks and color tinted quick action, both halves counting) on a full row, the small ones in pairs opened through one `onOpenTool(route)`; a long press on any tile toasts its open count
- `MenuUsage.kt`: MenuUsageStore, the per-tool open counters that order the menu
- `Theme.kt`: ThemeManager (dark mode DataStore), AppTheme, LocalIsDarkMode
- `MyApplication.kt`: Application class, holds the FlashcardRepository / DeezerRepository / PodcastRepository singletons plus their `Context.<name>Repository` extensions, and the app start housekeeping
- `Buttons.kt`: shared composables, MyButton, SplitMyButton, MySwitch, ErrorText, LightIconButton (the one-node icon button for anything repeated per card or line), LightCheckbox (its checkbox counterpart, for the Todo widget and a note's lines), AddItemRow (the gray "New item" placeholder where a new entry goes: the Todo widget, the Todo list note, the Calendar day). The buttons are all built on RaisedSurface
- `Dialogs.kt`: ShowAlertDialog and AppDialog, the shell every custom dialog is built on
- `ScreenTopBar.kt`: shared back-arrow + tool chip + title header used by the tool screens
- `ToolAccents.kt`: each tool's color and icon (the menu chips), `ToolAccents.forRoute` and LocalToolAccent, which AppNavHost's `screen` destinations provide from their own route (not the current one, or the screen being left recolors as it fades) so a tool's headers show its chip and its buttons take its tint (`secondary = true` keeps Cancel and the like gray)
- `Home.kt`: BackIconButton (tap = back, long press = main menu), the LocalGoHome hook, and the idle-return-to-menu lifecycle watcher with its IdleResetGuard
- `PlayerUi.kt`: the surfaces `deezer/` and `podcasts/` both draw; MediaArt, MediaListRow, MiniPlayerBar (swipe to change track when given the callbacks), PlayPauseButton, PlayerSeekBar (which owns the position polling), formatPlaybackTime
- `PauseOnOutputLost.kt`: `pauseOnOutputLost`, the device callback both playback services use to pause when a Bluetooth or wired output disappears (MIUI skips the noisy broadcast for some disconnects)
- `MediaControllerHolder.kt`: the MediaController connect-once/stop-the-service plumbing both playback repositories use
- `DaysLeft.kt`: `daysLeftUntil`, the rounded up days a trash row shows (notes and gallery)
- `Plural.kt`: `plural(count)`, the one place the count-to-plural rule lives
- `Normalize.kt`: `deaccented` / `matchNormalized` / `slugified`, plus `normalizeForSearch` (one char out per char in, so the notes search can highlight by index); the one place text is folded for comparison
- `Http.kt`: shared httpGet helper and User-Agent (Weather + Wikipedia + podcast feeds + news)
- `Errors.kt`: `userMessage(throwable)`, what a failed job says on screen; rethrows cancellation so a screen left mid-request never shows an error
- `AtomicWrite.kt`: `File.writeAtomically`, the one way a JSON state file is written (tmp then rename), since the process dies screen-off often enough to truncate a direct write
- `Clipboard.kt`: `copyToClipboard`, the one clipboard write (ErrorText's copy button, the Deezer error log, the playback failure snackbars, the Claude note cut, the translation copy)
- `Share.kt`: `shareUrisIntent` (files, for the gallery and the file explorer) and `shareTextIntent` (a link, for the Deezer track and playlist shares), the one place an ACTION_SEND is built
- `Browser.kt`: `openInBrowser`, the one ACTION_VIEW for a link (news articles, Wiktionary from the mots fléchés, links in a mail), with a snackbar when nothing can open it
- `Duration.kt`: `formatDuration`, the "3d" / "2h 5min" wait formatting the flashcards and the notes list share
- `BottomFadeOverlay.kt`: shared fade out gradient overlay composable
- `Snackbar.kt`: AppSnackbar, the app wide snackbar screens post undo actions through
- `SearchHistory.kt`: recent search terms per surface (notes, cities, Deezer, news) and the RecentSearchChips row
- `Backup.kt`: the menu's save button (its icon ages with the last Yahoo copy); one `cognitools_backup_<date>.json` bundle of the notes, flashcards and calendar, written to Téléchargements and uploaded to Yahoo, keeping the 3 newest in each place. Nothing in the app restores it
- `Random.kt`: random number generator tool screen
- `Volume.kt`: volume booster foreground service and its screen
- `Wikipedia.kt`: random Wikipedia article tool screen
- `Weather.kt`: GPS-based weather forecast tool screen, layout only
- `WeatherFormat.kt`: what that screen prints; the day labels, the emoji per condition code, the rain amounts, the day summary and the error message
- `WeatherApi.kt`: the Open-Meteo forecast/geocoding calls, the saved city, and the device position

`calendar/` (the "Calendar" tool: tasks, birthdays and events with their notifications, plus the jours fériés and the Rennes school holidays):
- `CalendarModels.kt`: CalendarItem (one table for the three kinds), CalendarDao, the backup JSON
- `CalendarSchedule.kt`: when each item reminds (a task at its time or 9:00 then daily until checked, an event the day before at its time or 9:00, a birthday a week before, at 23:59 the eve and at 9:00 unless wished), the 23:00 digest of the next day's tasks and events, the tasks still to do today (for the daily Todo reminder), where a repeating task goes once checked (a week or a month after the check, or its next time on its own day, or the last day of the month), the French public holidays from Easter. Pure, covered by a JVM unit test
- `SchoolHolidays.kt`: the zone B holidays from the ministry's open data API, cached in filesDir and refetched monthly
- `CalendarReminders.kt`: every write goes through it so the one exact alarm points at the next reminder; the notifications with their Done / In 1 h / Tomorrow / Wished buttons, and CalendarReceiver (alarm, buttons, reboot)
- `CalendarScreen.kt`: month grid pager (a dot color per kind, repeating tasks their own, school holidays as a faint band, fériés muted) and the selected day's list, overdue tasks moved onto today
- `CalendarItemDialog.kt`: the add/edit dialog with its date and time pickers; an event's date and time rows are "start → end" ranges, Repeat is one row opening a menu, the pickers start weeks on Monday through a French locale

`deezer/` (Deezer streaming client: library, playback, offline mirror):
- `DeezerModels.kt`: DeezerTrack/DeezerPlaylist/DeezerQuality data classes
- `DeezerApi.kt`: low level gw-light gateway + media.deezer.com calls, DeezerApiException
- `DeezerCrypto.kt`: Blowfish stripe decryption of the streams, pure functions covered by a JVM unit test
- `DeezerSettings.kt`: DataStore for the ARL credential and the shuffle toggle
- `DeezerSettingsDialog.kt`: the ARL paste dialog
- `DeezerRepository.kt`: the singleton; session lifecycle, stream cache, library access (favorites, playlists), "Best pépites" quick-add, the stream failure replacement search. The two `dzr://` cache key helpers are top level here
- `DeezerPlayer.kt`: `repo.player`; the MediaController and its player/queue state flows, play/shuffle/queue edits, the transport controls, `buildMediaItem`, and the played tracks file behind `downloadedTracks`
- `DeezerRefusedTracks.kt`: `repo.refused`, the sngIds Deezer refused to stream lately (30 day expiry, forgotten on any good resolve), left out of new queues and greyed by TrackRow
- `DeezerPendingFavorites.kt`: the likes and unlikes made offline, queued to disk and resent once the phone has internet again
- `DeezerDataSource.kt`: resolves `dzr://<sngId>` to a fresh CDN URL at open() time and decrypts on the fly
- `DeezerLibraryCache.kt`: JSON snapshot of favorites + playlists so a cold launch renders instantly
- `DeezerFavoritesHistory.kt`: the favorites count day by day, one JSON sample per day written on each complete fetch, the past seeded once from each track's `DATE_ADD`
- `DeezerFavoritesHistoryScreen.kt`: the curve of that count, opened by tapping the "N tracks" label on the library's Favorites card
- `DeezerOffline.kt`: DeezerOfflineLibrary, the permanent Best pépites mirror; sync, retry pass, sync log file
- `DeezerPlaybackService.kt`: MediaSessionService owning the ExoPlayer; media notification, its heart/diamond action buttons (`actionButtons`), error recovery, and the look ahead that swaps out a next track Deezer refuses before the crossfade reaches it
- `DeezerCrossfade.kt`: Crossfader, the 8 s blend into the next queued track (a session-less second player takes over the outgoing tail), started early on an outro or silence detected by LevelMeter, a tee on the main player's PCM
- `DeezerScreen.kt`: host for the whole Music tool (music *and* podcasts), nested NavHost + the two persistent mini-players
- `DeezerNowPlaying.kt`: FullPlayerSheet, plus the share-a-track sheet. The mini-player bar itself is the shared one in `PlayerUi.kt`
- `DeezerTrackInfoSheet.kt`: TrackInfoSheet, opened by tapping the title in the full player; release date, credits, album, label, BPM from the public track and album calls
- `DeezerTrackSaver.kt`: `repo.saver`, the full player's download button; saves the track as an ID3 tagged MP3 320 in Download, "already saved" being the file there under its name
- `DeezerQueueSheet.kt`: QueueSheet, the queue opened from the full player: jump to a track, drag to reorder, remove, each edit applied straight to the controller
- `DeezerLibraryScreen.kt`: landing screen; favorites card, playlists rows, followed podcast rows, offline status
- `DeezerTrackRow.kt`: TrackRow (the one tappable track line with its heart and menu, swipe right to queue), the playlist picker dialog, and the add-to-queue / Best pépites / add-to-playlist actions that return the toast to show, reused by every Deezer screen
- `DeezerPlaylistScreen.kt`: reusable ordered track list (play, remove, like, add to pépites)
- `DeezerSearchScreen.kt`: search screen, tracks and podcast shows
- `DeezerArtistScreen.kt`: one artist: header, top tracks, discography grouped by release type
- `DeezerDiscoveries.kt`: the daily "Today's discoveries" batch; Flow/track-mix discoveries, the scan scheduling and its results folded into the backlog, the persisted batch/backlog/proposed state
- `DeezerReleaseScan.kt`: `scanNewReleases`, the network half of the new release scan over the profile and library artists; reads copies of the state, writes none
- `RollingLog.kt`: the capped text log the offline sync, the discoveries batch, the crossfade diagnostics and the error log write, in the external files dir so a release build's log reads with plain adb
- `DeezerErrorsScreen.kt`: the "Error log" (`repo.errorLog`), every failure with its cause chain, copyable in one tap
- `DeezerDiscoveriesScreen.kt`: the batch's list screen (add, ignore, add all, ignore all, regenerate)

`podcasts/` (podcast subscriptions and playback, surfaced inside the Music tool):
- `Models.kt`: PodcastFavorite/PodcastEpisode/PodcastCatalogItem/PodcastEpisodeProgress/PodcastDownload and PodcastDao
- `PodcastApi.kt`: the iTunes directory search and the RSS feed parsing
- `PodcastRepository.kt`: the singleton; followed shows, the merged live episode list, heard state, progress, the MediaController; `repo.downloads` and `repo.sleepTimer` below
- `PodcastDownloads.kt`: the download queue and what counts as downloaded (derived from the bytes held, never a flag), the one-at-a-time worker, and `openAudio` (the by-hand redirect following podcast enclosures need)
- `PodcastSleepTimer.kt`: the timer that pauses playback, plus the pre-fetch, the coverage badge and the watchdog that make sure the audio to reach its end is on the phone
- `PodcastStreamCache.kt`: the one store for podcast audio, keyed by audio URL; a download is a protected key its custom evictor never evicts
- `PodcastPlaybackService.kt`: MediaSessionService owning the episode ExoPlayer; its own notification id and channel, distinct from the Deezer one
- `PodcastDownloadService.kt`: foreground service holding the download notification and a wake lock; the work stays in the repository
- `PodcastDownloadsScreen.kt`: every downloaded episode, all shows merged, read from the downloads table
- `PodcastNowPlaying.kt`: PodcastFullPlayerSheet, the mark-heard toggle, the sleep timer dialog and its coverage badge
- `PodcastEpisodesScreen.kt`: one followed show's episode list (play, download, mark heard, unfollow)

`files/` (file explorer, one folder at a time):
- `FileOps.kt`: the java.io.File side; listing/sorting a folder, the breadcrumb chain, size/date formatting, rename/create/delete/copy/move, and the FileProvider intents that open or share a file
- `FileDefaults.kt`: the app chosen for each extension, captured from the system "Open with" sheet by its receiver and stored in DataStore
- `FilesScreen.kt`: the screen; breadcrumb, pictures and videos opened in the gallery viewer (found in MediaStore by path), long press selection with its action bar, the cut/copy clipboard bar, the rename and new-folder dialogs

`flashcards/` (spaced repetition flashcards tool):
- `Models.kt`: FlashcardList/FlashcardElement data classes, JSON (de)serialization, stats helpers
- `CardImage.kt`: a card's optional front picture, `asset:` (shipped, the Drapeaux flags) or `user:` (picked, copied downscaled into filesDir, carried in backups as base64), the CardImage composable and the start-up sweep of unreferenced copies
- `AddToFlashcards.kt`: the shared filing dialog (list picker + editable card) the translator and the mots fleches clue bar both open
- `Database.kt`: Room FlashcardDao
- `Repository.kt`: FlashcardRepository, mediates between DB and UI; also keeps the builtin Birthdays list matched to the calendar's birthdays (`followBirthdays`, collected from app start)
- `FlashcardImportReceiver.kt`: upserts a bundle pushed into the app's external files dir; exported but guarded by DUMP, so only `adb shell am broadcast` reaches it
- `SpacedRepetition.kt`: reviewCard, the spaced repetition scheduling algorithm
- `ListsScreen.kt`: list of flashcard lists, bulk import dialog
- `DetailScreen.kt`: single list detail/edit screen
- `GameScreen.kt`: the review/quiz screen
- `ElementCard.kt`: flashcard flip card composable
- `StatsSheet.kt`: stats bottom sheet (7 day due forecast, maturity bar, scores); the maturity stages and `dueForecast` live in `Models.kt`

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
- `ViewerScreen.kt`: full screen pager (ViewerSource: an album, the pinned set, the Wallet album or a single item), image zoom and video playback, per-item tools including pin/unpin, and in the pinned set and the Wallet the top bar arrows that swap a picture with its neighbour
- `ViewerDialogs.kt`: `ViewerDialog`/`ViewerDialogs`, the viewer's whole dialog run, plus the share, rename, move and info dialogs themselves (MoveDialog is also used by the album grid)
- `GalleryPins.kt`: PinnedMediaItem Room entity/DAO (ordered by `position`, the first is the hero card) and pin/unpin/swap/resolve helpers
- `WalletOrder.kt`: the Wallet pictures' order set with those arrows, kept as ids in DataStore since MediaStore has none
- `GalleryLock.kt`: the albums put behind the notes PIN, kept as a set of bucket ids in DataStore
- `CropScreen.kt`: image editor, crop, 90° rotation and a pen (colors, widths, undo), decoded with its EXIF orientation
- `TrimScreen.kt`: video trim editor

`journal/` (the daily newsletter written on the laptop by `~/Documents/Code/daily-journal`, read and rated here):
- `JournalModels.kt`: the issue and feedback shapes and their org.json (de)serialization, mirroring that repo's `issue.py`
- `JournalRepository.kt`: the singleton; issues fetched from the mailbox's Journal folder into `filesDir/journal` only on refresh (14 kept, as on the mailbox), the read marks behind the menu tile's amber unread fill, where each issue was left (scroll only, cards reopen collapsed) and which issue to reopen, the ratings, notes, idea answers and comments, opened sources and items added to the Todo list per issue, sent back as `feedback_<date>.json` a few seconds after the last change (pending dates survive a killed process)
- `JournalScreen.kt`: one scroll of the day's issue, collapsed cards (tap for the full text, an item's picture as a thumbnail that grows full width when expanded), thumbs, note dialog, the add to Todo list button (asks Todo list or Week-end), sources opened in the news article screen, the arrows to older issues, the mark as read check, the header's News button (the news tool has no menu tile of its own)

`mail/` (the Yahoo inbox, read and delete only, fetched only when the screen opens or on refresh; no background work; plus the backup upload and the journal files):
- `MailSettings.kt`: DataStore for the Yahoo address and its app password
- `MailRepository.kt`: the singleton; one read only IMAP session per fetch, delete as a move to Trash with undo, the MIME walk, MailState, `uploadBackup` (the app backups APPENDed to a CogniTools folder and pruned there), and `readFiles`/`putFile` (JSON attachments named like their mail's subject, the journal's transport)
- `VerificationCode.kt`: `findVerificationCode`, the one-time code a mail carries, found near a code keyword. Pure, covered by a JVM unit test
- `MailScreen.kt`: the inbox list with a bin on each row, the code chip that copies in one tap, the account dialog
- `MailMessageScreen.kt`: one mail in a WebView (no JavaScript), links handed to the browser, a bin in the header

`motsfleches/` (arrow-word grids, French or English, generated on the phone):
- `Puzzle.kt`: Slot/Puzzle/PuzzleState, the grid model and what has been typed into it
- `ClueDictionary.kt`: loads `assets/motsfleches_dict_<lang>.txt` and indexes it by length with a bitset per (position, letter), so pattern lookups and the frequency ceiling are cheap
- `PuzzleGenerator.kt`: parses `assets/motsfleches_layouts.txt` and fills a layout by backtracking (most constrained word first). Pure, covered by a JVM unit test
- `MotsFlechesStore.kt`: MotsFlechesLang (FR/EN, one dictionary asset and one save file each), the language setting, the grid in progress per language saved as JSON in filesDir, plus the next grid pre-generated in the background
- `PuzzleGrid.kt`: the grid drawn on one Canvas, definitions printed in their cells with arrows, pinch to zoom
- `MotsFlechesScreen.kt`: the tool screen, clue bar and letter keyboard, per-word check and reveal
- The assets are generated by `src/generate_motsfleches.py`, `src/generate_motsfleches_en.py` and `src/generate_layouts.py`, sharing `src/motsfleches_common.py`. Ship the dictionary as plain text: the build unpacks a `.gz` asset at packaging time.

`recipes/` (the recipe the laptop's `/recipes` skill picked from the Ingrédients note, `~/Documents/Code/daily-journal`):
- `RecipeModels.kt`: the recipe shape and the notes snapshot, mirroring that repo's `recipe.py`
- `RecipeRepository.kt`: the singleton, created at app start; uploads the Ingrédients and Modèle courses snapshot to the Journal folder a few seconds after either changes, fetches the latest recipe on refresh (only one kept), the ticked steps, and the undoable edits to Ingrédients and Courses
- `RecipeNotes.kt`: the pure text side of those edits, on top of `notes/IngredientSync.kt`
- `RecipeTimers.kt`: the cooking timers (`repo.timers`), each an exact alarm plus a countdown notification replaced by an insistent alarm stream one at the end, kept in `filesDir/recipes/timers.json`, RecipeTimerReceiver (end, Stop, reboot), and `stepDurations`, the durations a step's text names
- `RecipeScreen.kt`: one scroll (ingredients with the missing ones marked live, Add missing to Courses, steps to tick with a timer chip per duration, screen kept on), the header timer button and the timer rows pinned under it, and the Cooked dialog (Started, Delete and Groceries per ingredient, a tap on its name for a comment in parentheses, Groceries implying Delete unless started or commented, more ingredients added from Ingrédients)

`news/` (news reader, French and world headlines from RSS):
- `NewsModels.kt`: NewsArticle, the NewsRead/NewsSaved/NewsProgress Room entities and NewsDao, plus the 7 day retention shared by the read marks and the reading positions
- `NewsFeeds.kt`: the hardcoded categories, the outlet feeds each merges (France 24 deliberately absent: its bodies are client rendered), `NewsSources` (the DataStore of fetched outlets)
- `NewsApi.kt`: RSS/Atom parsing, `parseArchivePage` for older franceinfo articles, `extractArticle` (jsoup body extraction). Pure, JVM tested
- `NewsRepository.kt`: the singleton; the merged articles per category with their freshness window, the read state, the saved articles and their offline text, and the reading position of the article left unfinished
- `NewsScreen.kt`: the tool screen; category tabs, the resume card on top, article list, search across everything loaded
- `NewsArticleScreen.kt`: one article read in the app, with save/open in the browser, the progress bar under the header, the restore-where-you-stopped scroll, and the paywall fallback
- `NewsSavedScreen.kt`: the starred articles, readable offline
- `NewsUi.kt`: NewsArticleRow, NewsResumeCard, the relative date formatting and the browser intent

`notes/` (notes tool, the biggest one):
- `Models.kt`: the Note entity, its JSON (de)serialization, NoteDao (the Room DAO), and NotesLists, the notes and trash observed from app start so the list opens with its first frame filled
- `NoteText.kt`: everything a note's plain text encodes; the special note titles, checkbox/separator prefixes, formatInline, quantity and waiting-date suffixes, noteTitleAndPreview
- `NotesListScreen.kt`: list of notes screen; the grid, the search, the trash entry, and NoteCard, the colored card every note is drawn on
- `TodoWidget.kt`: TodoWidgetCard, the pinned Todo preview on the list; toggle and delete lines in place, add an item at either end of the active block
- `NoteEditorScreen.kt`: the note editor's layout; the edit-mode text field, the read-only view, the in-note search bar and NoteEditorDialogs (the two PIN dialogs, the reconcile run, the add-an-item prompt)
- `NoteEditorState.kt`: NoteEditorState and `rememberNoteEditorState`, everything about the note itself; load, autosave, undo and redo stacks, saveContent, the lock and its PIN gate, and the fake blank lines edit mode pads the text with
- `NoteEditorTopBar.kt`: the editor's header; the editable title plus every button and menu entry, driven by a NoteEditorBarState/NoteEditorBarActions pair
- `NoteLineEdits.kt`: the per-line edits the read-only view makes (toggle, quantity, delete with undo), all going through one editLines
- `NoteEditing.kt`: pure text helpers; input transformations, slash commands, inline/line marker toggling, the Movies note's S1E10 episode bump
- `NoteViewMode.kt`: the read-only note rendering; per-line checkbox/separator/text, drag reorder, double-tap to edit, the Todo list's add placeholders (top of the active block, end of every block). Takes one NoteLineActions from the editor
- `NoteLock.kt`: the app's one PIN (notes *and* locked gallery albums, see `gallery/GalleryLock.kt`) and PinDialog
- `NotesTrashScreen.kt`: the trashed notes screen (restore, delete for good, empty the trash)
- `NoteSyncActions.kt`: the flows the editor triggers across the Courses/Ingrédients/model notes (move, add, re-sort, reconcile) and the batch state behind the reconcile dialog
- `IngredientSync.kt`: the pure text side of that sync, parsing the `Modèle courses` note and rendering Courses and Ingrédients from it
- `IngredientDialogs.kt`: the reconcile dialog and the add-an-item name prompt
- `TodoNote.kt`: `withTodoSections` (the `--- Attente` and `--- Week-end` separators the Todo list always carries, added back at app start and on every add), `addToTodoNote`, the unchecked item appended to the active block or to a section, written by the journal's per item button, and `findTodoNote`
- `TodoReminders.kt`: the Todo list notifications, the unchecked items left plus the calendar tasks still to do today (daily, an hour after the workout slot whether or not the workout reminder fires, an exact alarm when allowed, moved by `WorkoutReminder.setTimes`), those under `--- Attente` (random, about every 3 days, drawn between half and one and a half times its mean and pushed out of the night), and those under `--- Week-end` (every Friday at 18:00); `todoItems`, `nextDailyTodoReminder`, `nextTodoReminder` and `nextWeekendReminder` are pure, covered by a JVM unit test

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

`workout/` (workout reminder and guided sessions, the "Workout" grid tool):
- `WorkoutProgram.kt`: Exercise (Reps, Timed), WorkoutProgram (exercises, rounds, the two rests), the default program and its JSON
- `WorkoutSession.kt`: the steps a program expands to (exercise and rest), the boss round (the last one), the XP and level curve, the boss names, SessionProgress and `advance` (each set's reps or hold ms), the session times and `recordProgression`, the per exercise history behind the rep suggestions, bests and progress curves (matched by name and type). Pure, covered by a JVM unit test
- `WorkoutStore.kt`: the program, the XP and every finished session's time and sets in the workout DataStore, the session in progress in `filesDir/workout_session.json` (resumed after a kill), `finish` (adds the XP and logs the session through `markDone`)
- `WorkoutSessionScreen.kt`: the guided session; header clock, the dot path, exercise cards (the drawing, last and best, count chips plus a number pad that log the set; the timed hold counting up past its target until Stop), rests with +15 s and Skip, the boss round in red with its health bar, the burst and label shown on each logged set, the victory screen with the XP bar and confetti
- `WorkoutSounds.kt`: Cues, the vibrations and the sounds synthesized in code, played on the notification stream without focus so music keeps playing
- `ExerciseFigure.kt`: the looping stick figure of each default exercise, found by name, two poses interpolated on a Canvas
- `WorkoutProgramScreen.kt`: the program editor; drag to reorder, the New exercise placeholder, the exercise dialog, the rounds and rest rows
- `WorkoutSchedule.kt`: ReminderTimes (19:30 weekdays, 15:00 weekends by default), when the reminder goes off after a session (two days off) or a snooze, the days since the last session and the month/year counts. Pure, covered by a JVM unit test
- `WorkoutReminder.kt`: the DataStore state (next reminder, every session, due, the times), the one exact alarm, the notification with its in 1 h / tomorrow buttons (a tap on its body opens the screen on the day picker), `markDoneWithUndo` (the screen's "Session done" check), and WorkoutReceiver (alarm, buttons, reboot)
- `WorkoutScreen.kt`: next reminder (tap it to pick the day it comes back) and last session, Start (or Resume) session with the "Session done" check beside it, the snooze buttons while a reminder is due, the level card, the program card, the two reminder times with their time picker, the records (each session that beat the best of the current program), the progress card (per exercise curve and best set), the history; `lastSessionText` is shared with the notification, `Card` with the other workout screens

`undercover/` (party game tool):
- `GameModels.kt`: Player, GameSettings, GameState, MrWhiteScenario, ScoreValues data model
- `GameRules.kt`: elimination, win condition, Mr. White guess resolution logic
- `Setup.kt`: role/word assignment and PlayerSetupFlow, the name, warning and secret word steps per player
- `UndercoverScreen.kt`: game entry screen, the GameState dispatcher
- `PlayScreen.kt`: main play/discussion screen
- `VotingScreen.kt`: voting screen
- `EliminationResultScreen.kt`: result after a vote
- `MrWhiteGuessScreen.kt`: Mr. White's word guess screen
- `GameOverScreen.kt`: end of game screen
- `LeaderboardScreen.kt`: cross game leaderboard
- `Settings.kt`: game settings screen, validateGameSettings

## Cross-cutting things, and where they actually live
The map above is by feature. These are the ones you won't find by feature name:

- **Room**: one database for the whole app, declared in `flashcards/Database.kt` (version 25). The notes `Note` entity and `NoteDao` are registered there too but defined in `notes/Models.kt`; same for the gallery's `PinnedMediaItem`/`PinnedMediaItemDao` in `gallery/GalleryPins.kt`, the podcasts' tables in `podcasts/`, the reader's `Book`/`BookDao` in `reader/BookModels.kt`, the news tool's `NewsRead`/`NewsSaved`/`NewsProgress`/`NewsDao` in `news/NewsModels.kt`, and the calendar's `CalendarItem`/`CalendarDao` in `calendar/CalendarModels.kt`.
- **Word to flashcard loop**: the reader, the translator and the flashcards are one chain. Long pressing a word in `reader/ReaderScreen.kt` opens `translate/WordLookupSheet`, which writes a `FlashcardElement` into the list used last (remembered in `TranslateStore`). The reader and the workout session are the only screens that call `SuppressIdleReset`.
- **Media notification and lockscreen buttons**: `deezer/DeezerPlaybackService.kt`, `actionButtons()`. Media3 draws the notification; the buttons are `CommandButton`s handled in `SessionCallback.onCustomCommand`, so they act without opening the app. `podcasts/PodcastPlaybackService.kt` does the same with its ±30 s pair.
- **Two playback stacks, one player at a time**: music (`deezer/`) and podcasts (`podcasts/`) each have their own repository, service, notification and mini-player, and they are deliberately mutually exclusive: `DeezerRepository.stopPodcastPlayback()` and `PodcastRepository.playEpisode()` stop the other one, because two foreground services fighting over audio focus used to take the app down. Their two services must also keep **different notification ids and channels** (see either `onCreate`). What they share lives in the root package: `PlayerUi.kt` (every surface) and `MediaControllerHolder.kt` (the connection and the stop).
- **Icons**: screens use Compose `Icons.*` (material-icons-extended). `res/drawable/` only holds what the framework needs as a real resource: the launcher and the notification action icons. Media3 has no icon constant for most things, so a custom button icon means a vector in `res/drawable/` passed to `setCustomIconResId`.
- **Singletons**: `MyApplication` holds FlashcardRepository, DeezerRepository, PodcastRepository, NewsRepository, MailRepository, JournalRepository and RecipeRepository. Screens reach them as `context.flashcardRepository` / `context.deezerRepository` / `context.podcastRepository` / `context.newsRepository` / `context.mailRepository` / `context.journalRepository` / `context.recipeRepository` (extensions declared in `MyApplication.kt`), never by casting. Anything long lived hangs off there, not off an object/DI graph.
- **App start work**: `MyApplication.onCreate` purges the expired trashed notes and the expired news read marks and reading positions, calls `FlashcardRepository.seedAndPurge()` (seeds the builtin flashcard lists on a fresh install, drops mastered cards outside them), primes the saved discoveries batch, and sweeps the stream cache of heard podcast episodes. The repositories themselves assume this has been kicked off.
- **HTTP**: `Http.kt`'s `httpGet` serves Weather, Wikipedia, the podcast feeds/directory, the news feeds/pages and the school holidays. Deezer has its own client in `deezer/DeezerApi.kt`, mail goes over IMAP through JavaMail in `mail/MailRepository.kt`, podcast episode audio is fetched by hand in `PodcastDownloads.openAudio` (tracking prefixes need manual redirect following), Gallery does no networking.
- **All files access**: `gallery/GalleryPermissions.kt` owns the MANAGE_EXTERNAL_STORAGE check and the settings requester; the file explorer reads them from there rather than duplicating the check.
- **Alarms**: `workout/WorkoutReminder.kt`, one exact alarm re-armed by `MyApplication.onCreate` and by WorkoutReceiver on boot and app update, notification id 1004, after the three in the services. `notes/TodoReminders.kt`, three alarms (the daily one exact when allowed, the others inexact) re-armed the same way by TodoReminderReceiver, notification ids 1005, 1006 and 1007 (also their PendingIntent request codes, so the activity intents don't overwrite the workout's). `calendar/CalendarReminders.kt`, one exact alarm for the next reminder of any item, re-armed by `MyApplication.onCreate`, CalendarReceiver and every calendar write; its notification ids (and activity request codes) are hashed into 2000000 to 2999999, the evening digest's is 1999999.
- **Foreground services**: `Volume.kt` (volume booster), `deezer/DeezerPlaybackService.kt`, `podcasts/PodcastPlaybackService.kt` and `podcasts/PodcastDownloadService.kt`. The Deezer offline sync deliberately has none.
- **30 day trash**: two different mechanisms. Notes carry a `deletedAt` timestamp and are purged by `MyApplication.onCreate`. The gallery uses MediaStore's own trash (`IS_TRASHED`, `performTrashBatch`/`performRestoreBatch`), which Android empties by itself.
- **Media consent launcher**: registered once in `MainActivity` and passed down through `LocalMediaConsent`, so an undo posted after its screen is gone can still show the system dialog. Gallery screens read it instead of calling `rememberIntentSenderRequester` themselves.
- **Text folding**: any "are these the same thing?" comparison goes through `Normalize.kt`. `deaccented()` is the base (NFD, marks stripped, lowercased), `matchNormalized()` also drops punctuation and is what `DeezerTrack.matchKey` and the podcast title matching use, `slugified()` builds URL path segments, `normalizeForSearch()` is the notes search. Do not hand-roll another Normalizer call.
