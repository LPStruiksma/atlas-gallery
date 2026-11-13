#!/usr/bin/env python3
"""ATLAS offline release floor, no network, Android SDK or personal photos."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
read = lambda path: (root / path).read_text()
app = read('app/build.gradle.kts')
manifest = read('app/src/main/AndroidManifest.xml')
viewer = read('app/src/main/java/nl/atlas/gallery/GalleryViewer.kt')
activity = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt')
workflow = read('.github/workflows/build-apk.yml')
tests = read('app/src/androidTest/java/nl/atlas/gallery/ViewerInteractionTest.kt')
albumGrid = read('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt')
coverPicker = read('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumCoverChooser.kt')
checks = {
    'supported API 36 / Compose 1.11 toolchain': 'compileSdk = 36' in app and 'compose-bom:2026.04.01' in app and '8.13.2' in read('build.gradle.kts'),
    'explicit offline-only application permissions': 'android.permission.INTERNET' not in manifest and 'android.permission.WRITE_EXTERNAL_STORAGE' not in manifest and 'MANAGE_EXTERNAL_STORAGE' not in manifest,
    'filmstrip and main pager are independently testable': 'testTag("atlas-filmstrip")' in viewer and 'testTag("atlas-main-pager")' in viewer and 'atlas-photo-counter' in viewer,
    'filmstrip and zoom do not contest swipes': 'userScrollEnabled = currentZoom <= 1.01f' in viewer,
    # targetSdk 36 => edge-to-edge on Android 15+; the full-screen viewer is
    # outside the Scaffold and must inset its own chrome.
    'viewer chrome clears system bars': (
        'WindowInsets.safeDrawing.only(' in viewer and
        'WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)' in viewer),
    'back restores folder context': re.search(
        r'tab == 0 && album != null -> \{\s*album = null\s*albumVolume = null\s*'
        r'if \(albumReturnToAlbums\) tab = 1\s*albumReturnToAlbums = false\s*\}',
        activity) is not None,
    'separate browsing state': all(t in activity for t in ('photosGridState','searchGridState','personGridState')),
    # DeepBg is not a colorScheme colour; without an explicit content colour
    # the shell's untinted headings/icons fall back to black-on-black.
    'app shell sets readable content colour': (
        'containerColor = DeepBg' in activity and
        'contentColor = MaterialTheme.colorScheme.onBackground' in activity),
    'album lazy keys exclude private paths': (
        # O(1) reference-identity publication scope: I's CatalogSourceKey or
        # Lane D's equivalent AlbumPresentationListKey (never List.equals).
        # The grid may render the full list or Lane D's quick-find filtered
        # visibleAlbums, but the scope must be bound to the list it renders.
        any(any(scope in albumGrid for scope in (
                f'remember(CatalogSourceKey({grid})) {{ Any() }}',
                f'remember(AlbumPresentationListKey({grid})) {{ Any() }}')) and
            (f'itemsIndexed({grid}, key = {{ index, _ -> index }})' in albumGrid or
             # #515 drag-to-reorder: the key is the album's numeric position in
             # the published list (identity map), still never a path.
             ('itemsIndexed(shown, key = { index, album -> originalIndex[album] ?: (-1 - index) })'
                in albumGrid and
              'java.util.IdentityHashMap<AlbumSummary, Int>()' in albumGrid))
            for grid in ('albums', 'visibleAlbums')) and
        'key(gridScope)' in albumGrid and
        'items(albums, key' not in albumGrid and
        'items(visibleAlbums, key' not in albumGrid),
    'cover chooser lazy keys exclude private URIs': (
        'key(projection)' in coverPicker and
        'itemsIndexed(candidates, key = { index, _ -> index })' in coverPicker and
        'items(candidates, key' not in coverPicker),
    'android emulator test on actual viewer': 'ViewerInteractionTest' in tests and 'GalleryViewer(fakeMedia' in tests and 'performClick()' in tests,
    'synthetic images, not private photos': 'Bitmap.createBitmap' in tests and 'atlas-test-gallery' in tests,
    'CI builds and runs unit tests': ':app:testDebugUnitTest' in workflow and ':app:assembleDebug' in workflow,
    'CI has optional emulator and screenshots': 'android-emulator-runner@v2' in workflow and 'name: ATLAS-android-UI-evidence' in workflow and 'ui-evidence/*' in workflow,
    'CI uses exact head and no stored credentials': 'github.event.pull_request.head.sha || github.sha' in workflow and 'persist-credentials: false' in workflow,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + '  ' + name)
if not all(checks.values()):
    raise SystemExit('ATLAS static contract failed')
print(f'PASS: {len(checks)} of {len(checks)} static release checks')
