# ATLAS Native — Foundation 0.2 (instrumented-interaction candidate)

> **Current development map (October 2026):** This README describes the original
> 0.2 foundation, not the current release candidate or installed phone version.
> Start with the [unified gallery integration blueprint](docs/ATLAS_UNIFIED_GALLERY_INTEGRATION.md)
> and [live engineering handoff](docs/ATLAS_LIVE_HANDOFF.md); verify live
> GitHub main, CI and PRs for authoritative status. The existing installed
> S24+ app must **not** be uninstalled or replaced until public signer and
> private People-data recovery gates have been satisfied.


**An actual native Android/Kotlin gallery proof of concept, not a WebView.**

**0.2 release notes:** Kept exact-album browsing, native media viewing, stable random sorting and V4 label CSV import. Fixed the Compose/AGP compileSdk compatibility mismatch by pinning the stable June 2026 Compose BOM (1.11.x) with the API 36 / AGP 8.13 line. Added separate grid scroll state for Photos/Search/People, navigation Back from album to All, and protection against page swipes while zoomed. Added Android Compose UI regression tests using 64 generated colored photos, screenshot evidence, JUnit logic tests and an optional emulator CI job. Offline static source checks and pure-Kotlin smoke tests pass. **Full Android build and instrumented tests still require CI execution.**


This is the first step in moving ATLAS away from browser-driven Termux dashboards. It focuses on photographic browsing, Android navigation and the Samsung-inspired bottom filmstrip. The previous V3 sorter, V4 face index and V5 semantic index are **not replaced**. This is an early source release—not a finished ATLAS 6, not yet compiled/tested on an Android device.

## What is implemented in source

- Native Kotlin + Jetpack Compose UI with four top-level tabs: Photos, Search, People and Activity.
- Foreground MediaStore inventory of images and videos, read-only. Android 13+ permissions and Android 14+ selected-photo access are considered.
- Lazy, thumbnail-cached adaptive photo grid with album/folder-specific filtering by **actual MediaStore relative path**, not Samsung AI search index.
- Newest, oldest, **largest-first** and **stable random** sorting. Random selection persists until you explicitly reshuffle.
- Full-screen photo pager, a **LazyRow bottom thumbnail filmstrip** with drag-to-scrub and synchronized main viewer, double-tap/pinch zoom (prototype), progressive lower-/higher-resolution loading, and local Media3 video playback.
- Android Back returns from full-screen photo to its originating grid/context; People profile → photo → Back returns to that person, including saved grid state during normal composition. Top-level Back returns to Photos.
- A one-way, transactional **V4 confirmed-People CSV import**, keeping V4 and its database untouched. Named profiles become selectable subsets of actual MediaStore images when paths match exactly.
- Exact filename/album/people-name search, honestly labeled as exact search. CLIP semantic AI is **not** integrated into this first native shell.
- The app does not move, delete, upload, resize or modify your original media. No network permission is requested. It creates a private SQLite copy of imported V4 labels.

## Build the native APK (recommended: GitHub Actions)

The project contains `.github/workflows/build-apk.yml`: a reproducible debug-APK build using Gradle 8.13, Android API 36 and JDK 17. The workflow runs on pushes to `main`, pull requests, or manual dispatch. Unit tests and APK compilation run on every push/PR; **Android emulator UI tests run on PRs or when opted into via manual workflow dispatch** to avoid unnecessary emulator minutes. The instrumented test generates 64 numbered photos itself, validates thumbnail taps/Back/navigation, and saves screenshot evidence for inspection.

**Important toolchain correction:** The previous `compose-bom:2026.09.00` requires API 37 and AGP 9. This source uses `compose-bom:2026.06.01` with API 36 and AGP 8.13.2 until the first CI build is proven.
 After a successful run, download the `ATLAS-native-debug-APK` artifact from that workflow run, unzip it and install the `app-debug.apk` on Android.

For a Termux-only route, after downloading `ATLAS_Native_Foundation.zip` into Downloads:

```sh
pkg install git gh unzip
cd ~
mkdir -p atlas-native
unzip -q ~/storage/downloads/ATLAS_Native_Foundation.zip -d ~/atlas-native
cd ~/atlas-native
git init -b main
git add .
git -c user.name='ATLAS Builder' -c user.email='atlas@local.invalid' commit -m 'First native gallery foundation'
gh auth login
# Creates a NEW PRIVATE GitHub repository in your account:
gh repo create atlas-native --private --source . --remote origin --push
```

**Important:** `gh repo create` is an external action and should only be run if you intend to create that repository. Do not use your `autodev` repo as a default destination. GitHub Actions usage may count against your plan's limits; keep the repository private because future commits might involve your photo workflow. The project contains no photos, V4 database or V5 index.

You can instead open the folder in Android Studio on a computer and build with Gradle (`gradle :app:assembleDebug`). A Gradle wrapper JAR is **not** bundled: this project hasn't had an Android Gradle initialization in this restricted runtime; the GitHub workflow installs Gradle explicitly.

**Debug APK signing:** GitHub-hosted builds produce debug-signed APKs. Debug signing keys may differ between workflow runs, so updating may require uninstall/reinstall and losing the app's locally imported labels. Do not treat this as the long-term update/release mechanism. Configure stable application signing before using ATLAS as your main gallery.

## V4 People import (no database mutation)

In Termux, export the latest confirmed labels:

```sh
python ~/monochrome_studio_v4.py export \
  --output "$HOME/storage/downloads/atlas_v4_people.csv"
```

In the native app, open **People → Import V4**, select `atlas_v4_people.csv` from Downloads. The import requires columns `person_id,person_name,image_path,face_id,confidence`, validates the CSV, and transactionally replaces the *previous native import*; it does not read or change V4's SQLite file. It uses exact paths to link names to Android MediaStore records. If a V4-indexed file was moved/renamed after export, its match will be missing until you export again or ATLAS gains durable file-lineage mapping.

This first release imports *confirmed* named assignments, not tentative 578 face groups or SFace embeddings. The People tab displays the whole image as the profile cover rather than a cropped face; a real face-portrait tile requires the next integration step.

## Architecture and exclusions

- `ArchiveLogic.kt`: deterministic filtering, stable random ordering, CSV parser, pure-JVM testable functions.
- `GalleryRepository.kt`: off-main-thread MediaStore loading, native `GalleryViewModel`.
- `PeopleRepository.kt`: SQLite copy of confirmed person-to-image assignments.
- `GalleryViewer.kt`: photo/video viewer and synchronized filmstrip.
- `MainActivity.kt`: permissions, bottom navigation, grid, sorting, search, people, Back.
- `AtlasApplication.kt`: shared Coil image loader with video frame decoder.

Not included yet: full image subsampling (Telephoto/ZoomImage evaluation pending), semantic CLIP inference, facial inference, face overlays, nested photo-lineage linking, edit/move actions, accessibility audit, background ML jobs, Play Store publishing, fast date scrubber across 67k items, biometric search, and comprehensive device performance instrumentation. **The main filmstrip UX is implemented but its Samsung-level speed is not proven without device benchmarking.**

## Test and validation status

- Pure-JVM checks of 1,000 synthetic images for album filtering, filename search, person membership, random stability, sorting, item selection and CSV quoting were executed and passed. An additional JUnit suite covers exact albums, stable random shuffle, size sort, coarse scrub bounds and CSV/person search.
- Code was inspected for read-only MediaStore usage, correct V4 export header, transactional import and native Back handling.
- Android dependency versions were cross-checked with official release documentation.
- **Not run in this environment:** Android Gradle build, Android emulator tests, Android 16 permission-dialog tests, Samsung S24+ gesture tests, FPS/jank/memory benchmarks. The new workflows are written but not yet executed against a repository. Automated UI snapshots will be available when the workflow runs. There is no Android SDK nor external Maven access in the build environment used to create this archive. Treat initial compilation and runtime behavior as unverified.

## Expected first-device benchmark

On the S24+, test: (1) open Black and White album; (2) open a photo halfway down and scrub 30 thumbnails continuously; (3) swipe the main image and verify the strip tracks it; (4) double-tap, pinch and return; (5) alternate video and photo; (6) Back twice from People → Photo; (7) keep a V4 scan running and observe grid/filmstrip responsiveness; (8) try folders with >10,000 images; (9) compare frame timing against Samsung Gallery. See `BENCHMARK_PLAN.md`.

## Original data safety

- No DELETE/UPDATE MediaStore, file move or write permissions.
- No `MANAGE_EXTERNAL_STORAGE` and no `INTERNET` permission.
- CSV import changes only this native app's private `atlas_native_people.db`.
- Images are opened through their MediaStore content URIs and the media permissions you grant.

## Dependency policy

Uses Jetpack Compose, Coil 3, AndroidX Lifecycle/Activity, and Media3. Third-party UI libraries are not vendored; the repository reuses their binary Maven artifacts under their upstream licenses. No existing full gallery was copied, which avoids large unreviewed forks and keeps the custom filmstrip easy to benchmark and replace.

## Fastest path to autonomous UI testing

A GitHub repository is required to run the prepared CI (none has been created for ATLAS yet). Do **not** push it into `LPStruiksma/autodev`, which has its own authority and release rules. After unzipping this archive into `~/atlas-native`, use:

```sh
cd ~/atlas-native
bash scripts/bootstrap_github.sh
```

The script requires `git` and `gh` (`pkg install git gh`) and guides sign-in if necessary; it asks `CREATE` before creating a private repo. On push, GitHub Actions attempts a real Android build. On PR or a manual Actions run with `run_ui_tests` enabled, it runs emulator interactions and captures synthetic-viewer screenshots. A future connected assistant session can examine these CI results and artifacts without asking you to reproduce every UI bug manually.

For an offline source/test sanity check:

```sh
python scripts/verify_contract.py
```

See `QUALITY_EVIDENCE.md` for exact executed evidence and known gaps.
