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

## Build Commands

```bash
./gradlew :app:assembleDebug                 # Android debug APK
./gradlew :shared:jvmTest                    # shared-core unit tests (the bulk)
./gradlew :ui:compileKotlinJvm :ui:compileKotlinWasmJs
./gradlew :webApp:wasmJsBrowserDistribution  # browser bundle in webApp/build/dist
./gradlew :shared:compileKotlinWasmJs        # wasm compile check for the core
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
