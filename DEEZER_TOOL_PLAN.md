# Deezer streaming tool — implementation plan

This document is a self contained build plan for adding a Deezer streaming player as a new tool inside the CogniTools Android app. It is written to be executed by an AI or developer who does **not** have the original design conversation. Read the whole thing before starting, then follow the phases in order.

---

## 1. Goal and context

The official Deezer app is heavy and slow on the target phone. The goal is a lightweight native player inside CogniTools that streams full tracks from the owner's own paid Deezer account, using Deezer's private API and on the fly decryption. This must feel dramatically faster than the official app, especially on repeat plays via local caching.

CogniTools is a personal Jetpack Compose app (see the repo `CLAUDE.md` and codebase map). This tool becomes a fourth tool alongside flashcards, notes, and undercover, living under `app/src/main/java/com/example/myapp/deezer/`.

## 2. Scope and guardrails (do not skip)

- **Own account only.** The app uses the owner's personal ARL token from their own paid subscription. It must never ship, bundle, share, or fetch third party / "daily update" ARL tokens. No account sharing, no distribution of decrypted files outside the device.
- **No redistribution.** Decrypted audio stays in a private app cache on device. There is no export, upload, or sharing feature.
- **This violates Deezer's ToS** even with a paid account. That is an accepted, owner acknowledged risk (possible account ban). Do not add telemetry, do not spread requests across accounts, keep request volume app like.
- The ARL is a sensitive credential. Store it in the app's existing encrypted-at-rest DataStore, never log it, never put it in git, never send it anywhere except Deezer's own hosts.

## 3. What already exists in the project (reuse it)

Confirmed from `app/build.gradle` and `gradle/libs.versions.toml`:

- **Media3 / ExoPlayer 1.10.1** already a dependency (`media3-exoplayer`, `media3-ui`, `media3-transformer`). Only `media3-session` must be added.
- **Coil3 3.3.0** (`coil-compose`) for async image loading. Use it for album/cover art.
- **DataStore Preferences 1.1.7** already present. Use it to store the ARL token and settings.
- **kotlinx.serialization.json** present. Use it for API response parsing.
- **Room** present, used by notes and flashcards. Use it for the cached-track index if a DB is preferred over flat files.
- Networking today is plain `HttpURLConnection` via `Http.kt` (`httpGet`). There is **no OkHttp**. Either extend the simple pattern or add OkHttp; see Phase 1 note.
- minSdk 26, targetSdk/compileSdk 36. Release builds are minified (R8) and signed with the debug key. Watch ProGuard rules for kotlinx.serialization and any reflection.

Shared UI building blocks to match the app's look:
- `Buttons.kt`: `MyButton`, `SplitMyButton`, `MySwitch`, `ShowAlertDialog`, all built on `RaisedSurface`.
- `ScreenTopBar.kt`: back arrow + title header used by every tool screen.
- `BottomFadeOverlay.kt`: fade out gradient.
- Navigation is a single NavHost in `MainActivity.kt`; the main menu screen there lists the tools. Add a Deezer entry.

## 4. Architecture overview

```
                 ┌──────────────────────────────────────────┐
                 │            Compose UI (deezer/)            │
                 │  Library / Search / Playlist / NowPlaying  │
                 └───────────────┬───────────────┬───────────┘
                                 │               │
                     browse/search               playback controls
                                 │               │
                 ┌───────────────▼──────┐  ┌──────▼─────────────────┐
                 │  DeezerRepository     │  │  MediaController        │
                 │  (metadata, tokens)   │  │  (talks to service)     │
                 └───────┬──────────┬────┘  └──────────┬─────────────┘
                         │          │                  │
             ┌───────────▼──┐  ┌────▼──────────┐  ┌────▼──────────────────────┐
             │ DeezerApi     │  │ Public API    │  │ DeezerPlaybackService     │
             │ (gw-light +   │  │ api.deezer.com│  │ (MediaSessionService)     │
             │  media/get_url)│  │ (search/meta) │  │  owns the ExoPlayer       │
             └───────┬───────┘  └───────────────┘  └────────┬──────────────────┘
                     │                                       │
              stream URL + track_token                       │ sets MediaItem
                     │                                       │
                     └───────────────► ExoPlayer ◄───────────┘
                                          │
                              ┌───────────▼────────────┐
                              │ DeezerDataSource        │
                              │ decrypts BF_CBC_STRIPE  │
                              │ on the fly, writes to    │
                              │ CacheDataSource cache    │
                              └────────────────────────┘
```

Data flow for playing a track:
1. UI asks repository to play a Deezer track id (`SNG_ID`).
2. Repository ensures a valid session (`api_token` + `license_token`) from the stored ARL.
3. Repository calls gw `song.getData` (or `song.getListData`) to get the fresh `TRACK_TOKEN` and metadata.
4. Repository calls `media.deezer.com/v1/get_url` with the `license_token` + `track_token` to get an encrypted CDN URL.
5. Repository builds a `MediaItem` whose URI carries the CDN URL plus the `SNG_ID` (needed to derive the Blowfish key).
6. ExoPlayer streams via a custom `DeezerDataSource` that decrypts the stripe encryption chunk by chunk, wrapped in `CacheDataSource` so bytes are cached for instant replay.

## 5. Dependencies to add

In `gradle/libs.versions.toml` (media3 version ref already `= "1.10.1"`):

```toml
[libraries]
androidx-media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
# media3-datasource and media3-common come transitively with media3-exoplayer; add explicitly only if needed:
# androidx-media3-datasource = { group = "androidx.media3", name = "media3-datasource", version.ref = "media3" }
```

In `app/build.gradle` dependencies block:

```gradle
implementation(libs.androidx.media3.session)
```

Networking: prefer reusing the existing plain `HttpURLConnection` approach to avoid adding OkHttp. gw-light needs POST with a cookie header and a JSON body, which `HttpURLConnection` handles fine. If cookie handling or connection pooling becomes painful, adding OkHttp is acceptable but is not required. Do **not** add a heavy HTTP stack just for convenience.

No crypto library needed: `javax.crypto.Cipher` with `"Blowfish/CBC/NoPadding"` is in the JDK.

## 6. File layout (new package `deezer/`)

Create `app/src/main/java/com/example/myapp/deezer/`:

| File | Responsibility |
| --- | --- |
| `DeezerModels.kt` | Data classes: `DeezerSession`, `DeezerTrack`, `DeezerAlbum`, `DeezerArtist`, `DeezerPlaylist`, plus @Serializable DTOs for API parsing. Cover art URL helpers. |
| `DeezerApi.kt` | Low level calls to gw-light.php and media.deezer.com. Auth (`getUserData`), `song.getData`/`song.getListData`, user favorites, playlists, `get_url`. Public api.deezer.com search. Returns parsed models. Knows nothing about UI. |
| `DeezerCrypto.kt` | Blowfish key derivation from SNG_ID and the stripe decrypt routine. Pure functions, unit testable with a known vector. |
| `DeezerDataSource.kt` | Custom Media3 `DataSource` that fetches the encrypted CDN stream and decrypts BF_CBC_STRIPE on the fly. A `DataSource.Factory` wires it into ExoPlayer. |
| `DeezerPlaybackService.kt` | `MediaSessionService` owning the ExoPlayer instance. Background playback, lockscreen/notification controls, queue. Foreground service (see `Volume.kt` for the existing foreground service + notification pattern). |
| `DeezerRepository.kt` | Singleton (hold it in `MyApplication` like `FlashcardRepository`). Session lifecycle, token refresh, caches metadata, exposes suspend functions + flows to the UI. Bridges to the service via a `MediaController`. |
| `DeezerSettings.kt` | DataStore backed: ARL token, preferred audio quality (MP3_128 / MP3_320 / FLAC), cache size limit. |
| `DeezerLibraryScreen.kt` | Landing screen: favorites, playlists, search entry. Uses `ScreenTopBar`, `MyButton`, Coil art. |
| `DeezerSearchScreen.kt` | Search box + results (tracks/albums/artists) via public API. |
| `DeezerPlaylistScreen.kt` | Track list for a playlist/album, tap to play, play-all. |
| `DeezerNowPlaying.kt` | Now-playing bar (mini) + full player sheet: art, seek bar, play/pause/next/prev, quality indicator. Driven by `MediaController` state. |

Wire the screens into the NavHost in `MainActivity.kt` and add a Deezer tile to the main menu. Add the DataStore-backed ARL entry to whatever settings surface fits (a dedicated Deezer settings screen is cleanest).

## 7. Deezer private API reference

All private calls go to the gw-light gateway. Base:

```
POST https://www.deezer.com/ajax/gw-light.php
  ?method=<METHOD>&input=3&api_version=1.0&api_token=<API_TOKEN>
Headers:
  Cookie: arl=<ARL>
  Accept: */*
  User-Agent: <a normal browser UA string>   // reuse a realistic UA
  Content-Type: text/plain;charset=UTF-8
Body: <JSON payload for the method, or {} >
```

Responses are JSON with shape `{ "error": ..., "results": ... }`. If `error` is non empty or contains `"VALID_TOKEN_REQUIRED"` / `"GATEWAY_ERROR"` / `"INVALID_TOKEN"`, the session is stale — re-run `getUserData` and retry once.

### 7.1 Session bootstrap

1. First call with `api_token=` empty (`&api_token=`) and the `arl` cookie:
   - method: `deezer.getUserData`
   - body: `{}`
   - from `results` read:
     - `results.checkForm` → this is the **api_token** (CSRF token) for all later gw calls.
     - `results.USER.OPTIONS.license_token` → needed for media/get_url.
     - `results.USER.USER_ID` → the numeric user id, for favorites/playlists.
     - `results.USER.OPTIONS.web_hq` / `web_lossless` (or similar flags) indicate whether 320/FLAC are entitled. Use to pick the max available quality.
   - Also capture any refreshed `arl`/`sid` cookie from the `Set-Cookie` response header and keep the session cookie for subsequent calls.
2. Cache `api_token`, `license_token`, `user_id` in memory as the `DeezerSession`. Refresh by repeating step 1 when a call returns a token error, or proactively when older than ~30 minutes.

### 7.2 Track metadata

- method: `song.getData`, body `{"sng_id": <id>}` for one track, or
- method: `song.getListData`, body `{"sng_ids": [<id>, ...]}` for many.

Useful fields per track in `results` (or `results.data[]` for the list form):
- `SNG_ID` (string/int) — needed for the Blowfish key.
- `TRACK_TOKEN` — short lived (~1 hour); fetch right before playback, do not cache long.
- `DURATION`, `SNG_TITLE`, `ART_NAME`, `ALB_TITLE`.
- `ALB_PICTURE` — md5 hash for cover art (see 7.5).
- `MEDIA_VERSION`, and possibly `MD5_ORIGIN` / `FILESIZE_*`. Modern flow uses `TRACK_TOKEN` + get_url and does **not** require manual MD5_ORIGIN URL construction.

### 7.3 Media URL (get the encrypted stream)

```
POST https://media.deezer.com/v1/get_url
Content-Type: application/json
Body:
{
  "license_token": "<license_token from session>",
  "media": [{
    "type": "FULL",
    "formats": [{ "cipher": "BF_CBC_STRIPE", "format": "<FORMAT>" }]
  }],
  "track_tokens": ["<TRACK_TOKEN>"]
}
```

`<FORMAT>` options, pick the highest the account allows:
- `MP3_128` — always available, safe default and fallback.
- `MP3_320` — requires Premium.
- `FLAC` — requires HiFi.

Response: `data[0].media[0].sources[0].url` is the CDN URL of the **encrypted** file. If `data[0]` contains `errors`, the format is not entitled or the token expired; retry with `MP3_128` or refresh tokens. You can list several formats in `formats` (ordered) and Deezer returns the best available; still verify.

### 7.4 Browsing the library

Two viable sources; use whichever is simpler per screen:

- **Public API `https://api.deezer.com`** (no auth, JSON, easy) for **search** and public content:
  - Search: `GET /search?q=<query>` (also `/search/track`, `/search/album`, `/search/artist`).
  - Album tracks: `GET /album/<id>` and `/album/<id>/tracks`.
  - Playlist tracks (public playlists): `GET /playlist/<id>` and `/playlist/<id>/tracks`.
  - These return `id` values usable directly as `SNG_ID` for playback via the private flow. They give cover art `md5_image` and ready cover URLs.
- **Private gw** for the owner's own library (favorites, private playlists):
  - Favorite songs: method `favorite_song.getList` or `user.getFavoriteSongs`, body `{"user_id": <id>, "start": 0, "nb": 200}`. Confirm exact method name against a current reference (see section 12) since Deezer has renamed these over time.
  - Playlists list: method `deezer.pageProfile` (tab `playlists`) or `user.getPlaylists`. Confirm against reference.
  - Playlist tracks: method `playlist.getSongs`, body `{"playlist_id": <id>, "start": 0, "nb": 500}`.

Recommended split: **search via public API** (robust, no auth quirks), **own favorites/playlists via gw**, **playback always via gw + get_url**.

### 7.5 Cover art

Build from an md5 image hash (`ALB_PICTURE`, or `md5_image` from public API):

```
https://e-cdns-images.dzcdn.net/images/cover/<md5>/500x500-000000-80-0-0.jpg
```

Use `cover`, `artist`, or `playlist` in the path segment as appropriate. Load with Coil3. Public API responses also give direct `cover_medium` / `cover_big` URLs you can use as is.

## 8. Decryption reference (BF_CBC_STRIPE)

This is the core "hard" part and it is a fixed, known algorithm. Implement exactly.

### 8.1 Blowfish key derivation

```
secret = "g4el58wc0zvf9na1"            // ASCII, 16 bytes, fixed
md5 = lowercase_hex( MD5( ascii(SNG_ID_as_string) ) )   // 32 hex chars
key = ByteArray(16)
for i in 0..15:
    key[i] = md5[i].code XOR md5[i + 16].code XOR secret[i].code
// note: XOR of the *ASCII character codes* of the hex digits and the secret,
// not of raw nibble values.
```

### 8.2 Stripe decrypt

The downloaded CDN file is split into fixed 2048 byte chunks. Only some chunks are encrypted:

```
Cipher: Blowfish/CBC/NoPadding
IV: byte[] { 0, 1, 2, 3, 4, 5, 6, 7 }     // fixed
chunkSize = 2048

for each chunk index i (0-based), reading sequentially:
    if (i % 3 == 0) and (chunk.length == 2048):
        output += BlowfishCbcDecrypt(chunk, key, IV)
    else:
        output += chunk            // pass through unchanged
```

Notes:
- Only every third chunk is encrypted, and only if it is a full 2048 bytes. The final short chunk is always passed through.
- Reset/reinit the cipher (fresh IV) for **each** decrypted chunk; CBC state must not carry across chunks.
- The result is a normal MP3 (or FLAC) byte stream.

### 8.3 Unit test vector

Add a JUnit test in `test/` that:
- Derives the key for a known `SNG_ID` and asserts the 16 bytes.
- Feeds a small crafted buffer through the stripe routine and checks pass-through vs decrypt boundaries at chunk indices 0, 1, 2, 3.
Capture a real known-good key from a reference implementation to lock the vector.

## 9. Media3 integration

### 9.1 Custom DataSource

Implement `DeezerDataSource : androidx.media3.datasource.DataSource`:
- Constructed per-open with the CDN URL and the `SNG_ID` (to derive the key). Pass the `SNG_ID` in the `MediaItem` URI (custom scheme or query param) or via a `DataSpec` custom key, then have the factory read it.
- On `open(dataSpec)`: open the HTTP connection to the CDN URL, honoring `dataSpec.position` for seeks. Because decryption is chunk aligned to 2048, map byte-range seeks to whole-chunk boundaries and discard the intra-chunk remainder so decryption stays aligned.
- On `read(buffer, offset, length)`: pull encrypted bytes, run them through the stripe decryptor keeping a 2048 byte alignment buffer, return decrypted bytes.
- Wrap it: `DeezerDataSource` → `DefaultDataSource`-style for the raw HTTP part, then decryption, then wrap the whole thing in Media3 `CacheDataSource` (with a `SimpleCache` in app cache dir, size-capped from settings) so decrypted bytes are cached for instant replay. Cache key = `SNG_ID` + format, **not** the CDN URL (URLs are ephemeral).
- Alternative simpler-but-slower approach for Phase 0/1: download the whole encrypted file, decrypt to a temp file, hand ExoPlayer a `file://`. Ship the streaming DataSource in Phase 1/2 once correctness is proven.

### 9.2 Playback service

`DeezerPlaybackService : MediaSessionService`:
- Holds one `ExoPlayer` built with the custom `DataSource.Factory` and a `MediaSession`.
- Provides the media notification (Media3 gives this largely for free via `MediaSessionService` + `DefaultMediaNotificationProvider`).
- Foreground service with `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. See `Volume.kt` for the existing foreground service + notification channel pattern and manifest declarations to mirror.
- Manifest: declare the service with `<intent-filter><action android:name="androidx.media3.session.MediaSessionService"/></intent-filter>`, add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `INTERNET`, `WAKE_LOCK`, and `POST_NOTIFICATIONS` (API 33+) permissions.
- UI connects with a `MediaController` (from `media3-session`) to send transport commands and observe state.

## 10. ARL token handling (owner chose: manual paste + refresh)

- A settings field where the owner pastes the ARL. Store in DataStore (`DeezerSettings`).
- On app start / first Deezer use, bootstrap the session (7.1). If `getUserData` returns an empty/guest user or token error, show a clear "ARL expired, paste a fresh one" state.
- Do **not** build an in-app WebView login scraper (explicitly not chosen). Keep it manual.
- Never log the ARL. Redact it in any error surface.

## 11. Caching

- Decrypted audio: Media3 `SimpleCache` in `context.cacheDir/deezer`, LRU evicted, size cap from settings (default e.g. 1–2 GB). Keyed by `SNG_ID`+format.
- Metadata (track/album/playlist JSON, cover art via Coil's own disk cache): light Room table or in-memory + DataStore is enough. Coil handles image caching.
- This cache is the main reason the tool will beat the official app on repeat plays.

## 12. Reference implementations to consult

When an exact method name, field, or key is uncertain, check these current, well maintained reverse engineering references rather than guessing:
- `deezer-py` (Python) — clean, current gw + get_url + decryption reference.
- `deemix` — the canonical downloader; source shows method names and quality handling.
- `d-fi` / `diezel` / `deezload` — additional cross checks for the crypto and endpoints.
Match the algorithm and endpoints against at least one of these before finalizing.

## 13. Build phases and milestones

Do them in order. Do not build UI before the pipeline is proven.

### Phase 0 — Feasibility spike (throwaway, prove the pipeline)
Goal: prove ARL → session → track token → stream URL → decrypt → playable file works today.
- Small standalone Kotlin (a JUnit test or a `main()` / instrumented test) that takes an ARL and one `SNG_ID` and produces a playable MP3 on disk.
- Steps: `getUserData` → `song.getData` → `get_url` → download → `DeezerCrypto` decrypt → write file → confirm it plays.
- **Exit criteria:** the output file plays. If Deezer changed anything, it surfaces here in an afternoon.
- Keep `DeezerCrypto.kt` from this phase; discard the rest of the spike.

### Phase 1 — Playback core
- `DeezerModels`, `DeezerApi`, `DeezerCrypto` (from spike), `DeezerRepository`, `DeezerSettings`.
- `DeezerDataSource` (start with the simple download-then-decrypt-to-temp variant if needed), `DeezerPlaybackService`, `MediaController` wiring.
- **Exit criteria:** can play a hardcoded track id in the background with a working notification, from the owner's pasted ARL.

### Phase 2 — UI and library
- `DeezerLibraryScreen`, `DeezerSearchScreen`, `DeezerPlaylistScreen`, `DeezerNowPlaying`, Deezer settings screen with ARL field.
- Hook into `MainActivity.kt` NavHost + main menu tile, using shared composables (`ScreenTopBar`, `MyButton`, `RaisedSurface`) and Coil art.
- Streaming `DeezerDataSource` + `CacheDataSource` for instant replay and seeks.
- **Exit criteria:** browse favorites/playlists, search, tap to play, background controls, cached replays instant.

### Phase 3 — Polish
- Quality selection (auto-pick max entitled), gapless/queue behavior, offline cache management UI, graceful ARL-expiry prompts, error toasts.

## 14. Verification

- Unit: `DeezerCrypto` key vector + stripe boundaries (section 8.3).
- Integration (manual, on device with real ARL): each phase's exit criteria.
- Build/deploy per repo `CLAUDE.md`: `./gradlew assembleRelease` then `adb install -r app/build/outputs/apk/release/app-release.apk`. Release build only (performance). Verify R8/ProGuard does not strip serialization or Media3 classes; add keep rules if playback or parsing breaks only in release.

## 15. Known gotchas

- **`TRACK_TOKEN` expires in ~1 hour.** Fetch it right before playback, never cache it long. Cache the `SNG_ID` instead and re-resolve the URL on play.
- **CDN URLs are ephemeral.** Never use them as cache keys.
- **ARL expires (~3–4 months) or on web logout.** Handle the "guest user" response and prompt for a fresh paste.
- **Quality entitlement.** `get_url` errors if you request 320/FLAC without the plan. Always fall back to `MP3_128`.
- **Geo/availability.** Some tracks are unavailable; `get_url` returns errors per track. Surface a skip, do not crash.
- **Token error strings** (`VALID_TOKEN_REQUIRED`, `INVALID_TOKEN`, `GATEWAY_ERROR`): refresh session once, then retry, then surface.
- **Cipher reset per chunk.** Forgetting to reinit CBC per 2048-chunk yields garbage audio.
- **Realistic User-Agent** on gw calls; a scraper-looking UA can get blocked.
- **Method-name drift.** Favorites/playlists gw method names have changed historically; verify against a current reference (section 12).
- **R8 in release.** kotlinx.serialization and Media3 may need keep rules; test playback in the release build, not just debug.

## 16. Definition of done

A Deezer tile on the main menu opens a tool where the owner, after pasting their own ARL once, can browse their favorites and playlists, search the catalog, and stream full tracks with background playback and lockscreen controls, where repeat plays are served instantly from an on-device decrypted cache, and where the whole thing is noticeably faster than the official app on the target phone.
