# CogniTools Android

Personal Android app (Jetpack Compose), sideloaded on Valentin's phone.

## Workflow
After implementing a change, deploy it on the phone (see below) so Valentin can try it, then end with a summary of what changed. Never commit or push at that point: git operations happen only through /wrap-up or an explicit request.

For UI/layout changes with more than one reasonable arrangement (where to put a control, how a view is structured, an interaction model), sketch 2-3 concrete options up front with AskUserQuestion and let Valentin pick before writing any code. Don't implement your first interpretation and iterate from corrections.

## Deploy method
Build the release APK and install it on the connected phone:

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Always install release builds, never debug (performance matters on device). Debug is only for `run-as` access, e.g. DB inspection.

## Codebase map
Three independent tools live under `app/src/main/java/com/example/myapp/`. Use this map to jump straight to the right file with Read/Grep instead of spawning an exploration agent.

Root package (shared/misc):
- `MainActivity.kt`: app entry point, theme manager, main menu screen, nav host
- `MyApplication.kt`: Application class, holds the FlashcardRepository singleton
- `Buttons.kt`: shared composables, MyButton, SplitMyButton, MySwitch, ShowAlertDialog
- `BottomFadeOverlay.kt`: shared fade out gradient overlay composable
- `BackupRestore.kt`: export/import actions for app data
- `Random.kt`: random number generator tool screen
- `Volume.kt`: volume booster foreground service and its screen
- `Wikipedia.kt`: random Wikipedia article tool screen
- `Weather.kt`: GPS-based weather forecast tool screen (Open-Meteo)

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

`notes/` (notes tool, the biggest one):
- `Models.kt`: Note data class, checkbox/separator line parsing helpers, quantity suffix parsing, formatInline
- `NoteDao`: Room DAO, defined in `Models.kt`
- `NotesListScreen.kt`: list of notes screen
- `NoteEditorScreen.kt`: the note editor itself (43K, largest file); checkbox lines, undo, formatting toolbar
- `NoteLock.kt`: PIN lock for notes, PinDialog

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
