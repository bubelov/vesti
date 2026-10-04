# AGENTS.md - Developer Guide for Vesti

## Project Overview

Vesti is a web feed reader with three hosts sharing one multiplatform core:

- **Miniflux** (syncs with a Miniflux server)
- **Embedded** (uses the in-app RSS/Atom parser)

It targets **Android**, the **JVM** (desktop) and the **browser (Kotlin/Wasm)**
from a shared Kotlin Multiplatform codebase, with a Compose Multiplatform UI.

## Modules

- **`:shared`** — Kotlin Multiplatform (Android + JVM + wasmJs): the whole
  portable core. `parser` (RSS/Atom via Ksoup), `db` (raw SQLite over
  `androidx.sqlite`), `backend` (Miniflux + Embedded over Ktor), `opml`,
  `feedsettings`, `og` (pure planning), `sync`, `auth`, `json`, `util`,
  `platform`. No `java.*` in `commonMain`.
- **`:ui`** — Kotlin Multiplatform (Android + JVM + wasmJs): the Compose
  Multiplatform UI (`VestiApp`, `AppState`, the screens, `VestiTheme`). The
  Android-specific platform services and the `AbstractComposeView` host live in
  `androidMain`.
- **`:app`** — the Android application. A thin host: it creates the
  `Database` with `AndroidSQLiteDriver`, sets up `Sync`, and puts `:ui`'s
  `VestiComposeView` on screen. It has no Compose compiler and no UI code.
- **`:webApp`** — the wasmJs browser application. `main` builds the
  `Database` with `androidx.sqlite`'s `WebWorkerSQLiteDriver` (driving the
  vendored `sqlite-worker.js`), and renders `VestiApp` through
  `ComposeViewport`.
- **`:desktopApp`** — the JVM desktop application. `main` builds the
  `Database` with `androidx.sqlite`'s `BundledSQLiteDriver` (stored under the
  platform's per-user data directory) and renders `VestiApp` in a Compose
  Desktop `Window`. The `compose.desktop` DSL also packages native
  installers (dmg/msi/deb).

## Build Commands

```bash
./gradlew :app:assembleDebug                 # Android debug APK
./gradlew :shared:jvmTest                    # shared-core unit tests (the bulk)
./gradlew :ui:compileKotlinJvm :ui:compileKotlinWasmJs
./gradlew :webApp:wasmJsBrowserDistribution  # browser bundle in webApp/build/dist
./gradlew :shared:compileKotlinWasmJs        # wasm compile check for the core
./gradlew :desktopApp:run                    # launch the desktop app
./gradlew :desktopApp:createDistributable    # app image in desktopApp/build/compose/binaries
./gradlew :desktopApp:packageDistributionForCurrentOS  # native installer
```

Run a single test:

```bash
./gradlew :shared:jvmTest --tests "org.vestifeed.backend.MinifluxTest"
```

Serve the wasm build (a static server is enough; OPFS persistence additionally
needs `Cross-Origin-Opener-Policy: same-origin` and
`Cross-Origin-Embedder-Policy: require-corp`):

```bash
python3 -m http.server -d webApp/build/dist/wasmJs/productionExecutable 8080
```

## Architecture Notes

### Shared core is de-JVM'd

`commonMain` uses only multiplatform code:

- **HTTP**: Ktor (`CIO` on Android/JVM, `JS` on wasm). The Miniflux client uses
  an `HttpResponseValidator` that reproduces the old OkHttp `errorInterceptor`:
  a 401 reports through `AuthEvents` and throws
  `MinifluxUnauthenticatedException`; other non-2xx throw `okio.IOException`.
- **JSON**: kotlinx.serialization, with Gson-style accessors in
  `org.vestifeed.json`.
- **Date/time**: `kotlin.time.Instant` and `kotlinx-datetime`. There is no
  `OffsetDateTime` anywhere; feed parsing handles RFC 822 and both ISO forms in
  `org.vestifeed.util`.
- **IO**: Okio. **Atomics/locks**: atomicfu (the `PlatformLock` `expect`).
- **HTML/XML**: Ksoup (a KMP jsoup port). XML is parsed with
  `Parser.xmlParser()`; use `element.text()` (not `html()`) for content fields,
  because `text()` matches the old DOM `textContent` and does not leak CDATA
  markers.
- **Database**: `androidx.sqlite` raw SQL, no ORM. Its web driver is async, so
  **every query method is `suspend`** and `Database.connect()` is a separate
  step from construction. On JVM/Android the connection is wrapped in
  `LockingSQLiteConnection`; on wasm it is returned as-is (single-threaded).

### UI is Compose Multiplatform

Screens are `@Composable`s in `:ui`'s `commonMain`. `AppState` owns the
database connection, the config (`conf` is Compose state), `Sync`, and a small
back stack. Platform effects are injected through `VestiPlatform`
(`openUrl`, `shareText`).

On Android, `:ui` exposes `VestiComposeView` (an `AbstractComposeView`) so the
`:app` module needs no Compose compiler: the activity sets the database and
platform implementation on it.

On the JVM, `:desktopApp` drives the same `VestiApp` from Compose Desktop's
`application {}`/`Window`, with a `DesktopVestiPlatform` (system browser +
clipboard). It uses `BundledSQLiteDriver` because desktop has no system SQLite
to fall back on.

### Icons

All icons are Material Symbols, never emoji — the one exception is the
country-flag emoji in the curated feeds catalog, which has no Material
equivalent (`org.vestifeed.ui.screens.CuratedCollectionBadge`).
`ui/src/commonMain/composeResources/font/material_symbols.ttf` is a ~130 KB
subset of the outlined variable font containing only the glyphs the app uses;
`VestiTheme` loads it through Compose Multiplatform resources and provides it as
`LocalIconFont`. Draw one with the `MaterialSymbol` composable and a glyph
constant from `org.vestifeed.ui.icons.MaterialSymbols` (the icons' private-use
codepoints).

`composeResources` needs `androidResources { enable = true }` in `:ui`'s
`android {}` block for the font to be packed into the Android host's assets.

To add an icon: pick its codepoint (glyph name = the upstream ligature name),
add a `MaterialSymbols` constant, and extend the subset by re-running, from the
full `material-symbols-outlined-*.ttf`:

```bash
pyftsubset material-symbols-outlined.ttf \
  --unicodes=U+E5C4,U+E8B6,... --no-layout-closure \
  --output-file=ui/src/commonMain/composeResources/font/material_symbols.ttf
```

### The browser database worker

`androidx.sqlite` has no wasm driver that runs on the main thread. The
`WebWorkerSQLiteDriver` talks to a Web Worker implementing a small JSON message
protocol (`open`/`prepare`/`step`/`close`). `webApp/src/wasmJsMain/resources/sqlite-worker.js`
is the AndroidX worker, vendored and bundled by webpack together with the
`@sqlite.org/sqlite-wasm` npm dependency. It uses OPFS when the page is
cross-origin isolated and an in-memory VFS otherwise.

**Note**: fetching arbitrary feeds from the browser is subject to CORS. Most
feed servers do not send `Access-Control-Allow-Origin`, so the Embedded backend
is effectively limited to CORS-enabled feeds in the browser; Miniflux (a single
CORS-enabled API origin) is the practical browser backend. The Android/JVM
hosts have no such restriction.

## Curated feeds (Awesome RSS Feeds)

The Feeds screen and the Unread empty state offer a built-in catalog that
seeds subscriptions. It is based on the CC0-1.0
[Awesome RSS Feeds](https://github.com/plenaryapp/awesome-rss-feeds) collection
but is **not an exact copy** — feeds and whole collections are reviewed and can
be dropped by hand. The catalog ships as
`ui/src/commonMain/composeResources/files/curated_feeds.json` (topic and country
collections). The data classes and parser live in `:shared`
(`org.vestifeed.curated`); the UI loads the resource lazily through
`AppState.curatedFeeds()` and renders it from
`ui/.../screens/CuratedFeedsScreen.kt`. Adding a feed in either mode goes
through `AppState.addFeedByUrl`.

The JSON is checked in and maintained by hand; there is **no generator script**
in the repository. It is based on the Awesome RSS Feeds collection but is not
an exact copy: the feeds were reviewed and some were removed. Because upstream
feeds go dead over time, verify any refreshed batch before shipping it.

## Web Target (wasmJs)

The web app is deployed at **https://app.vestifeed.org** (host `vesti`,
`172.104.164.169`).

```bash
# Build and deploy the browser bundle
./gradlew :webApp:wasmJsBrowserDistribution
tar -C webApp/build/dist/wasmJs/productionExecutable -czf - . \
  | ssh vesti 'tar -xzf - -C /srv/http/app.vestifeed.org'
```

Server layout:

- Caddy site: `/etc/caddy/conf.d/app.vestifeed.org` (imported by the main
  Caddyfile). It serves `/srv/http/app.vestifeed.org`, sets
  `Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy:
  require-corp` (required for OPFS), and reverse-proxies `/proxy*` to the feed
  proxy.
- Feed/image proxy: `/usr/local/bin/vesti-web-proxy.py`, run by
  `vesti-web-proxy.service` on `127.0.0.1:8787`. It relays `GET /proxy?url=…`
  server-side so the browser can fetch cross-origin feeds and OpenGraph images.
  It only allows http(s) to public IPs (rejecting loopback/private/link-local,
  including on redirects) and requires browser same-origin/same-site requests.

The shared `proxiedUrl` (`org.vestifeed.platform`) is the switch: it is the
identity on Android/JVM and rewrites to `https://app.vestifeed.org/proxy?url=…`
on wasm. The Embedded backend and `OgImageFetcher` route every feed/article/
image fetch through it. Without the proxy, standalone mode and OG images cannot
work in a browser because most origins do not send CORS headers.

## Desktop Target (JVM)

`./gradlew :desktopApp:run` opens the Compose Desktop window. On this machine
the session is Wayland and Compose Desktop's Skiko renderer is X11-only, so the
window is an **XWayland** client, and `run` blocks for the life of the window.

- **Never run `:desktopApp:run` in the foreground, and never `sleep`/poll
  waiting for it to start.** Launch it detached with its output in a named log,
  e.g. `(./gradlew :desktopApp:run > /tmp/opencode/vesti-desktop.log 2>&1 &)`,
  then return control.
- The running app is identified by `pgrep -f "org.vestifeed.desktop.MainK[t]"`
  (the brackets keep the pattern from matching the shell) when launched with
  `:desktopApp:run`. The packaged launcher instead shows up as
  `.../Vesti/bin/Vesti`, so match `pgrep -f "bin/Vest[i]"` for that one. Check it
  *before* launching; if it is listed, the window is already open. Stop it with
  the same pattern piped to `xargs -r kill`.
- Find the client window with `xdotool search --name "Vesti"`. The app owns
  several windows: the full-size client is the one whose geometry is the
  window's logical size × the display scale, and whose `xdotool getwindowpid
  <id>` is the app's PID (the small 400×400 ones and GNOME's `mutter-x11-frames`
  decoration are not). Capture the client area with ImageMagick:
  `import -window <id> /tmp/opencode/vesti.png` (the reliable path here; `grim`
  fails under GNOME).
- The display is 2×-scaled, so `xdotool getwindowgeometry` and `import` report
  physical pixels while the app's logical size is half that (the 1100×800
  window captures at roughly 2200×1564).

## Code Style Guidelines

- One class per file (filename matches class name); packages mirror directories.
- Fully qualified imports (no wildcard imports).
- Nullable types and safe calls; avoid `!!` except where a Ksoup lookup is
  known non-null.
- Database fields follow the ATOM spec; app extensions use an `ext_` prefix.
  Any schema change must add a migration in `Database.migrate` and bump the
  `user_version`.
- Prefer small pure functions (e.g. `OgImagePlanning`, `RelativeTimeCalculator`)
  so logic is unit-testable without platform APIs.

## Testing

- JUnit 4. `:shared`'s `jvmTest` is the primary suite (parser, db, backend,
  opml, feedsettings, og).
- Backend tests use `MockWebServer` (artifact `mockwebserver3-junit4`) with a
  Ktor CIO client.
- DB tests use `BundledSQLiteDriver` and the `testDb()` helper (it calls
  `connect()`).
- Do not add iOS or other targets without discussing it first.

## Emulator (debug device)

```bash
./devtools emulator start     # boot the AVD
./devtools emulator stop      # shut down cleanly
./devtools app run            # assemble debug APK, install it, launch it
./devtools app uninstall      # uninstall the debug APK from a running emulator
```
