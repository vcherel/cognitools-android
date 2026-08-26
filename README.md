# CogniTools

An Android app built in Kotlin for **fun**, grouping all the little tools and games I like to have on my phone. Jetpack Compose throughout, sideloaded, single user. The in-app text is French on purpose.

<p align="center">
  <img src="/images/main_menu.jpeg" alt="Main menu" width="300">
</p>

## Building and installing

Android Studio, or from the command line with a phone plugged in and USB debugging on:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Always install the release build: performance on device matters, and debug builds are noticeably slower. Debug is only useful for `run-as` access, e.g. inspecting the Room database.

## The tools

### Big ones

- **Notes**: checkbox and separator lines, a pinned Todo widget on the list, PIN lock per note, a 30 day trash, and a Courses/Ingrédients sync mode that keeps a shopping list and an ingredients list reconciled against their model notes.
- **Flashcards**: spaced repetition. The scheduler shows cards you know less often, so reviews concentrate on what is not yet learned. Cross-list stats, bulk import, and a filing dialog the translator and the mots fléchés clue bar both write into.
- **Galerie**: a photo and video browser on top of MediaStore. Albums from your own folders, a full screen swipe viewer with pinch zoom and video playback, batch select by long press and drag, rename, move, share, crop, video trim, pinned pictures, albums lockable behind the notes PIN, and the system 30 day trash.
- **Musique**: a Deezer streaming client (favorites, playlists, search, a daily "Découvertes du jour" batch, an offline mirror of one playlist) and a podcast client (iTunes directory search, RSS subscriptions, downloads, listening progress, a sleep timer that pre-fetches the audio it needs so the night plays through offline) sharing one screen, with a mini player for each.

### Smaller ones

- **Actus**: French and world headlines merged from a fixed set of RSS feeds, readable in the app with the boilerplate stripped out, saved articles kept offline.
- **Mots fléchés**: arrow-word grids generated on the phone, French or English, with a per-word check and reveal. The dictionaries are built offline (see below).
- **Traducteur**: live translation with dictionary entries, text to speech, and a one tap "add to flashcards".
- **Lecture**: an epub reader written against the format directly, no library. Long press a word to open the translator's lookup sheet.
- **Météo**: forecast for your GPS location or a searched city, with temperature, conditions and rain amount.
- **Undercover**: the social deduction party game. Most players share a secret word, the *Undercover* have a different one and *Mr. White* has none. The ~80,000 word pairs were generated with NLP and FastText embeddings (see below).
- **Fichiers**: a plain file explorer, one folder at a time, with a remembered "open with" app per extension.
- **Wiki**: a random Wikipedia article.
- **Random**: random numbers and words.
- **Volume**: pushes the volume past the system limit.

<p align="center">
  <img src="/images/flashcard_menu.jpeg" alt="Flashcard menu" width="300">
  <img src="/images/flashcard.jpeg" alt="Flashcard game" width="300">
</p>

## Generating the assets

Two of the tools ship data generated offline by the Python scripts in `src/`. The project uses [uv](https://docs.astral.sh/uv/).

The Undercover word pairs:

```bash
uv sync
uv run python src/generate_pairs.py
```

`generate_dataset.ipynb` is a thin driver over the same module if you would rather inspect the results interactively:

```bash
uv sync --group notebook
uv run jupyter notebook generate_dataset.ipynb
```

The mots fléchés dictionaries and layouts, from Lexique and fr.wiktionary for French, wordfreq and WordNet for English. The French run scrapes fr.wiktionary into `data/motsfleches_senses.json`, which is not tracked: it takes a few minutes on a fresh clone and is resumable, so only rerun it when the dictionary itself needs rebuilding. `data/lexique.tsv` is a manual download from [lexique.org](http://www.lexique.org/) and is tracked, since nothing fetches it for you.

```bash
uv run python src/generate_motsfleches.py
uv run python src/generate_motsfleches_en.py
uv run python src/generate_layouts.py
```

## Development

Lint and format run on commit through pre-commit, tests run on push:

```bash
uv run --with pre-commit pre-commit install
```

The JVM unit tests cover the pure logic (the Deezer stream decryption, the puzzle generator, the epub parser, the RSS extraction, the translation parsing, the spaced repetition scheduler):

```bash
./gradlew :app:testReleaseUnitTest
```

`CLAUDE.md` carries a one line per file map of the whole codebase, kept current as files are added or renamed.
