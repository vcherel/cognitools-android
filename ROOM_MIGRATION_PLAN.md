# Room migration (flashcards)

Flashcard lists and cards moved from JSON-in-DataStore to a Room (SQLite)
database. **Status: done and verified on device.** Only unit tests remain
(see below), to be picked up in a later session.

## What changed and why

Lists and cards used to live as JSON strings in a single DataStore Preferences
file: key `lists` for the lists, one `elements_<listId>` key per list for its
cards. Every card edit reloaded a whole list, parsed every card, changed one,
re-serialized, and rewrote the entire blob. The `*_timestamp` keys existed only
to force a re-emit, and `getAllElements()` re-read the whole store once per list
(N+1).

Room gives per-row updates, SQL queries, and Flows that auto-emit on table
changes, removing all of that.

`FlashcardRepository`'s public method signatures were preserved, so the screens
(`ListsScreen`, `DetailScreen`, `GameScreen`) were untouched.

## Implementation (done)

- **Build:** Room `2.8.2` + KSP `2.2.20-2.0.2` in `libs.versions.toml`,
  `build.gradle.kts`, `app/build.gradle.kts`.
- **Entities:** `FlashcardList` (`@Entity "lists"`) and `FlashcardElement`
  (`@Entity "cards"`, `listId` foreign key with `ON DELETE CASCADE`, index on
  `listId`) in `Models.kt`.
- **DAO + database:** `FlashcardDao` and `AppDatabase` (process singleton) in
  `Database.kt`. Due-count is computed in SQL, mirroring `isDue`
  (`now - lastReview >= interval * 60000`). `observeListsWithCounts` combines
  lists + total counts + due counts.
- **One-time import:** `FlashcardRepository.ensureMigrated()` copies the old
  DataStore JSON into Room once (guarded by a `migrated_to_room` flag and an
  empty-table check), then stops reading DataStore. The old DataStore file is
  never deleted, so the first run stays reversible. Reuses the existing
  `listFromJsonString` / `fromJson` parsers (kept for this reason; the JSON
  writers `toJson` / `listToJsonString` were removed).
- **Repository + worker:** `FlashcardRepository` internals call the DAO;
  `FlashcardReminderWorker` now goes through the repository instead of reading
  DataStore directly. The `*_timestamp` keys and the manual cascade in
  `deleteList` are gone.

## Verified on device

Backed up `flashcards.preferences_pb` first, installed in place (debug key, so
app data survives), confirmed all **7 lists / 1154 cards** migrated with
per-list counts matching the source and review state (interval, ease factor,
win/loss) preserved. Release build then installed in place and launches clean.
(The ~2324 raw count seen earlier included 1170 orphaned cards from
long-deleted lists that the app never displayed.)

## Remaining: unit tests (next session)

Add unit tests for the spaced-repetition math (the easeFactor/interval/
repetitions update currently inside `GameScreen`, and `resetElement`). This
needs the math extracted out of the composable closure plus a JVM/Room
in-memory test source set, so it's a separate piece of work. Cover at least: a
correct answer advances interval/repetitions and raises easeFactor; a wrong
answer resets repetitions; `resetElement` returns a card to defaults; the SQL
due-count matches the Kotlin `isDue` definition.
