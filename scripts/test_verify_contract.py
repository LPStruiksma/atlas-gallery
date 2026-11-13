#!/usr/bin/env python3
"""Fictional read-only fixtures for ATLAS's static Android release floor."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

# Run the live UI-evidence host suite as part of the existing exact-head static CI
# discovery command, without editing protected workflow files.
try:
    from .test_ci_android_ui_evidence import EvidenceCollectionTest  # noqa: F401
except ImportError:
    from test_ci_android_ui_evidence import EvidenceCollectionTest  # noqa: F401

SCRIPT = Path(__file__).with_name('verify_contract.py')

FIXTURES = {
    'app/build.gradle.kts': 'compileSdk = 36\nimplementation("androidx.compose:compose-bom:2026.04.01")\n',
    'build.gradle.kts': '8.13.2\n',
    'app/src/main/AndroidManifest.xml': '<manifest package="nl.atlas.gallery" />\n',
    'app/src/main/java/nl/atlas/gallery/GalleryViewer.kt': '''
        testTag("atlas-filmstrip")
        testTag("atlas-main-pager")
        atlas-photo-counter
        userScrollEnabled = currentZoom <= 1.01f
        .windowInsetsPadding(WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    ''',
    'app/src/main/java/nl/atlas/gallery/MainActivity.kt': '''
        photosGridState
        searchGridState
        personGridState
        Scaffold(
            containerColor = DeepBg,
            contentColor = MaterialTheme.colorScheme.onBackground,
        )
        BackHandler(enabled = album != null || tab != 0) {
            when {
                tab == 0 && album != null -> {
                    album = null
                    albumVolume = null
                    if (albumReturnToAlbums) tab = 1
                    albumReturnToAlbums = false
                }
            }
        }
    ''',
    '.github/workflows/build-apk.yml': '''
        ref: ${{ github.event.pull_request.head.sha || github.sha }}
        persist-credentials: false
        run: gradle :app:testDebugUnitTest :app:assembleDebug
        uses: reactivecircus/android-emulator-runner@v2
        uses: actions/upload-artifact@v4
        with:
          name: ATLAS-android-UI-evidence
          path: |
            ui-evidence/*
    ''',
    'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt': '''
        val gridScope = remember(AlbumPresentationListKey(albums)) { Any() }
        key(gridScope) {
          LazyVerticalGrid {
            itemsIndexed(albums, key = { index, _ -> index }) { _, album -> }
          }
        }
    ''',
    'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumCoverChooser.kt': '''
        key(projection) {
          LazyVerticalGrid {
            itemsIndexed(candidates, key = { index, _ -> index }) { _, candidate -> }
          }
        }
    ''',
    'app/src/androidTest/java/nl/atlas/gallery/ViewerInteractionTest.kt': '''
        class ViewerInteractionTest
        GalleryViewer(fakeMedia
        performClick()
        Bitmap.createBitmap
        atlas-test-gallery
    ''',
}


class StaticReleaseFloorRegression(unittest.TestCase):
    def run_fixture(self, changes=None):
        with tempfile.TemporaryDirectory(prefix='atlas-source-floor-') as tmp:
            root = Path(tmp)
            for name, content in FIXTURES.items():
                dest = root / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(changes.get(name, content) if changes else content)
            dest = root / 'scripts/verify_contract.py'
            dest.parent.mkdir(exist_ok=True)
            dest.write_bytes(SCRIPT.read_bytes())
            result = subprocess.run([sys.executable, str(dest)], capture_output=True,
                                    text=True, timeout=10, check=False)
            return result.returncode, result.stdout + result.stderr

    def test_current_album_back_and_real_emulator_evidence_pass(self):
        code, report = self.run_fixture()
        self.assertEqual(code, 0, report)
        self.assertIn('15 of 15 static release checks', report)

    def test_viewer_without_system_bar_insets_fails(self):
        path = 'app/src/main/java/nl/atlas/gallery/GalleryViewer.kt'
        original = FIXTURES[path]
        changed = original.replace('WindowInsets.safeDrawing.only(', 'Modifier.padding(')
        self.assertNotEqual(changed, original)
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  viewer chrome clears system bars', report)

    def test_shell_without_explicit_content_colour_fails(self):
        path = 'app/src/main/java/nl/atlas/gallery/MainActivity.kt'
        original = FIXTURES[path]
        changed = original.replace(
            'contentColor = MaterialTheme.colorScheme.onBackground,', '')
        self.assertNotEqual(changed, original)
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  app shell sets readable content colour', report)

    def test_back_without_volume_clear_fails(self):
        old = FIXTURES['app/src/main/java/nl/atlas/gallery/MainActivity.kt']
        code, report = self.run_fixture({'app/src/main/java/nl/atlas/gallery/MainActivity.kt': old.replace('albumVolume = null', 'albumVolume = oldVolume')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  back restores folder context', report)

    def test_back_without_return_state_reset_fails(self):
        old = FIXTURES['app/src/main/java/nl/atlas/gallery/MainActivity.kt']
        code, report = self.run_fixture({'app/src/main/java/nl/atlas/gallery/MainActivity.kt': old.replace('albumReturnToAlbums = false', 'albumReturnToAlbums = true')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  back restores folder context', report)

    def test_missing_screenshot_artifact_destination_fails(self):
        old = FIXTURES['.github/workflows/build-apk.yml']
        code, report = self.run_fixture({'.github/workflows/build-apk.yml': old.replace('name: ATLAS-android-UI-evidence', 'name: old-UI-evidence')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  CI has optional emulator and screenshots', report)

    def test_missing_screenshot_upload_path_fails(self):
        old = FIXTURES['.github/workflows/build-apk.yml']
        code, report = self.run_fixture({'.github/workflows/build-apk.yml': old.replace('ui-evidence/*', 'unused-elsewhere/*')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  CI has optional emulator and screenshots', report)

    def test_missing_emulator_runner_fails(self):
        old = FIXTURES['.github/workflows/build-apk.yml']
        code, report = self.run_fixture({'.github/workflows/build-apk.yml': old.replace('reactivecircus/android-emulator-runner@v2', 'some-unrelated-action@v2')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  CI has optional emulator and screenshots', report)

    def test_old_private_album_path_key_rejected(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        changed = original.replace('itemsIndexed(albums, key = { index, _ -> index })',
                                   'items(albums, key = { it.volumeName + it.relativePath })')
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  album lazy keys exclude private paths', report)

    def test_missing_album_catalogue_identity_key_rejected(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        changed = original.replace(
            'remember(AlbumPresentationListKey(albums))', 'remember(albums)')
        self.assertNotEqual(changed, original)
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  album lazy keys exclude private paths', report)

    def test_equivalent_catalog_source_identity_scope_still_accepted(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        changed = original.replace(
            'remember(AlbumPresentationListKey(albums))',
            'remember(CatalogSourceKey(albums))')
        self.assertNotEqual(changed, original)
        code, report = self.run_fixture({path: changed})
        self.assertEqual(code, 0, report)

    def test_quick_find_filtered_album_list_accepted(self):
        # Lane D quick-find renders a filtered visibleAlbums list with the
        # same index keys and a scope bound to that same filtered list.
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        changed = original.replace('AlbumPresentationListKey(albums)',
                                   'AlbumPresentationListKey(visibleAlbums)').replace(
            'itemsIndexed(albums,', 'itemsIndexed(visibleAlbums,')
        self.assertNotEqual(changed, original)
        code, report = self.run_fixture({path: changed})
        self.assertEqual(code, 0, report)

    def test_filtered_album_scope_must_match_rendered_list(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        changed = original.replace('itemsIndexed(albums,', 'itemsIndexed(visibleAlbums,')
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  album lazy keys exclude private paths', report)

    def test_private_key_on_filtered_album_list_rejected(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        changed = original.replace('AlbumPresentationListKey(albums)',
                                   'AlbumPresentationListKey(visibleAlbums)').replace(
            'itemsIndexed(albums, key = { index, _ -> index })',
            'items(visibleAlbums, key = { it.relativePath })')
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  album lazy keys exclude private paths', report)

    def test_missing_album_grid_generation_scope_rejected(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt'
        original = FIXTURES[path]
        code, report = self.run_fixture({path: original.replace('key(gridScope)', 'key(0)')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  album lazy keys exclude private paths', report)

    def test_old_private_cover_uri_key_rejected(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumCoverChooser.kt'
        original = FIXTURES[path]
        changed = original.replace('itemsIndexed(candidates, key = { index, _ -> index })',
                                   'items(candidates, key = { it.uri })')
        code, report = self.run_fixture({path: changed})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  cover chooser lazy keys exclude private URIs', report)

    def test_cover_page_without_projection_scope_rejected(self):
        path = 'app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumCoverChooser.kt'
        original = FIXTURES[path]
        code, report = self.run_fixture({path: original.replace('key(projection)', 'key(0)')})
        self.assertNotEqual(code, 0)
        self.assertIn('FAIL  cover chooser lazy keys exclude private URIs', report)

if __name__ == '__main__':
    unittest.main()
