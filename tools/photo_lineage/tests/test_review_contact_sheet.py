"""Synthetic-only opt-in contact-sheet regression. Never reads user media."""
from __future__ import annotations

import os
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from tools.photo_lineage.review_contact_sheet import (
    MAX_EDGES, _preview, render_contact_sheet, write_new,
)


def evidence(a: str, b: str, verdict: str = 'candidate') -> dict:
    return {
        'image_a': a, 'image_b': b, 'verdict': verdict, 'algorithm': 'orb',
        'reason': 'Distributed geometric inliers with compatible image structure; review required',
        'inliers': 34, 'local_consistency': 0.92,
    }


def invented_photo(path: Path, *, exif=False):
    image = Image.new('RGB', (120, 80), (101, 17, 220))
    if exif:
        metadata = Image.Exif()
        metadata[274] = 6  # rotate on presentation
        metadata[271] = 'Synthetic Camera'
        image.save(path, exif=metadata)
    else:
        image.save(path)


class ContactSheetTest(unittest.TestCase):
    def test_opt_in_visual_pairs_keep_source_bytes_and_scrub_exif(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b = root / 'imaginary-left.jpg', root / 'imaginary-right.jpg'
            invented_photo(a, exif=True)
            invented_photo(b)
            originals = (a.read_bytes(), b.read_bytes())
            page, stats = render_contact_sheet(evidence(str(a), str(b)))
            self.assertEqual(stats['preview_available'], 2)
            self.assertIn('data:image/jpeg;base64,', page)
            self.assertEqual(page.count('data:image/jpeg;base64,'), 2)
            self.assertIn('Content-Security-Policy', page)
            self.assertIn("default-src 'none'", page)
            self.assertIn('A↔B and B↔C do not establish A↔C', page)
            self.assertNotIn(str(a), page)
            self.assertNotIn('Synthetic Camera', page)
            self.assertNotIn('imaginary-left', page)
            self.assertNotIn('<script', page)
            self.assertEqual((a.read_bytes(), b.read_bytes()), originals)
            from io import BytesIO
            with Image.open(BytesIO(_preview(str(a)))) as preview:
                self.assertEqual(preview.size, (80, 120))
                self.assertFalse(preview.getexif())
            out = root / 'sheet.html'
            write_new(out, page)
            self.assertEqual(out.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                write_new(out, page)
            self.assertEqual(out.read_text(), page)
            shown, _ = render_contact_sheet(evidence(str(a), str(b)), show_names=True)
            self.assertIn(str(a), shown)

    def test_missing_preview_is_not_falsely_suggested_as_negative(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            good = root / 'good.png'
            invented_photo(good)
            absent = root / 'absent.png'
            html, stats = render_contact_sheet(evidence(str(good), str(absent), 'review'))
            self.assertEqual(stats['preview_available'], 1)
            self.assertEqual(stats['preview_unavailable'], 1)
            self.assertIn('Preview unavailable', html)
            self.assertIn('REVIEW', html)
            self.assertNotIn(str(absent), html)
            with self.assertRaisesRegex(ValueError, 'No photo previews'):
                render_contact_sheet(evidence(str(absent), str(root / 'gone.png')))

    def test_disallowed_symlink_parent_and_leaf_fail_closed_without_crash(self):
        if not hasattr(os, 'O_NOFOLLOW'):
            self.skipTest('POSIX nofollow required')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            real = root / 'real'
            real.mkdir()
            a = real / 'one.png'
            invented_photo(a)
            target = root / 'linked-parent'
            target.symlink_to(real, target_is_directory=True)
            with self.assertRaises((ValueError, OSError)):
                _preview(str(target / 'one.png'))
            link = real / 'linked-leaf.png'
            link.symlink_to(a)
            with self.assertRaises((ValueError, OSError)):
                _preview(str(link))

    def test_duplicate_sources_loaded_once_and_sorted(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            sources = [root / f'{i}.png' for i in range(3)]
            for source in sources:
                invented_photo(source)
            payload = {'candidate_edges': [evidence(str(sources[0]), str(sources[1]), 'review'),
                                           evidence(str(sources[1]), str(sources[2]), 'candidate')]}
            html, stats = render_contact_sheet(payload)
            self.assertEqual(stats['preview_available'], 3)
            self.assertEqual(stats['edges'], 2)
            self.assertEqual(html.count('data:image/jpeg;base64,'), 4)
            self.assertLess(html.index('Pair 1 · CANDIDATE'), html.index('Pair 2 · REVIEW'))

    def test_reject_invalid_and_excess_evidence_early(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a = root / 'a.png'
            b = root / 'b.png'
            invented_photo(a)
            invented_photo(b)
            payload = evidence(str(a), str(b))
            with self.assertRaises(ValueError):
                render_contact_sheet(payload, max_edges=MAX_EDGES + 1)
            with self.assertRaises(ValueError):
                render_contact_sheet(payload, show_names='yes')
            with self.assertRaises(ValueError):
                render_contact_sheet({'candidate_edges': [payload, payload]})
            with self.assertRaises(ValueError):
                render_contact_sheet({'candidate_edges': [payload] * (MAX_EDGES + 1)})
            with self.assertRaises(ValueError):
                render_contact_sheet({'candidate_edges': []})

    def test_injected_paths_are_escaped_even_when_explicitly_shown(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a = root / '<script onload=bad>.png'
            b = root / 'bob & alice.png'
            invented_photo(a)
            invented_photo(b)
            report, _ = render_contact_sheet(evidence(str(a), str(b)), show_names=True)
            self.assertNotIn('<script onload', report)
            self.assertIn('&lt;script', report)
            self.assertIn('bob &amp; alice', report)

    def test_oversize_encoded_source_skips_preview(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b = root / 'good.png', root / 'bad.png'
            invented_photo(a)
            b.write_bytes(b'not an image')
            report, stats = render_contact_sheet(evidence(str(a), str(b)))
            self.assertEqual(stats['preview_unavailable'], 1)
            self.assertNotIn('not an image', report)


if __name__ == '__main__':
    unittest.main()
