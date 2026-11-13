"""Pure local synthetic fixtures for provenance, leakage, path and read-only gates."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from tools.photo_lineage.benchmark import textured
from tools.photo_lineage.heldout_eval import evaluate


class HeldoutEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.photos = self.root / 'photos'
        self.photos.mkdir()
        self.manifest = self.root / 'curated.json'
        self.data = {'version': 2, 'scenes': []}
        for scene_id, seeds, split in (
            ('scene1', (212, 213), 'holdout'),
            ('scene2', (420, 421), 'holdout'),
            ('scene3', (650, 651), 'development'),
        ):
            captures = []
            for index, seed in enumerate(seeds):
                name = f'{scene_id}_{index}.png'
                Image.fromarray(textured(seed, 256)).save(self.photos / name)
                captures.append(dict(id=f'{scene_id}_exposure{index}', file=name,
                                     sha256=hashlib.sha256((self.photos / name).read_bytes()).hexdigest(),
                                     provenance='independent_shutter',
                                     provenance_note='Independent exposure recorded by a test fixture generator.',
                                     license='CC-BY-4.0',
                                     license_url='https://creativecommons.org/licenses/by/4.0/',
                                     source_url=f'https://commons.wikimedia.org/wiki/File:placeholder_{scene_id}_{index}.jpg',
                                     author='Synthetic fixture test'))
            self.data['scenes'].append({'id': scene_id, 'scene_group': f'group_{scene_id}', 'split': split, 'captures': captures})
        self._write()

    def _write(self):
        self.manifest.write_text(json.dumps(self.data), encoding='utf-8')

    def test_holdout_results_are_aggregate_and_unchanged(self):
        originals = {p.name: p.read_bytes() for p in self.photos.iterdir()}
        report = evaluate(self.manifest, self.photos)
        self.assertEqual((report['scenes'], report['captures']), (2, 4))
        self.assertEqual(report['conditions']['different_exposures_same_scene']['pairs'], 2)
        self.assertEqual(report['conditions']['different_scenes']['pairs'], 1)
        for name in ('resized', 'gamma', 'cropped', 'rotated_crop', 'jpeg_reencode'):
            self.assertEqual(report['conditions']['edit_'+name]['pairs'], 4)
        self.assertTrue(report['unsafe_to_certify_exposure_identity'])
        retained = report['edit_retention']
        self.assertEqual((retained['scenes'], retained['captures_correlated']), (2, 4))
        self.assertEqual(retained['per_edit']['rotated_crop']['candidate'] +
                         retained['per_edit']['rotated_crop']['review'] +
                         retained['per_edit']['rotated_crop']['unknown'], 4)
        self.assertLessEqual(retained['captures_all_five_edits_reviewable'], 4)
        self.assertLessEqual(retained['scenes_all_captures_all_edits_reviewable'], 2)
        self.assertGreater(retained['scene_any_edit_unknown_wilson95_upper'], 0)
        self.assertIn('NOT deployment guarantees', retained['policy'])
        risk = report['scene_exposure_risk']
        self.assertEqual((risk['scenes'], risk['correlated_pairs_diagnostic_only']), (2, 2))
        self.assertEqual(risk['scenes_with_any_strong_same_scene_candidate'],
                         report['same_scene_strong_candidates_needing_review'])
        self.assertGreater(risk['scene_strong_candidate_wilson95_upper'], 0)
        self.assertIn('NOT source-exposure proof', risk['policy'])
        self.assertNotIn(str(self.photos), json.dumps(report))
        self.assertEqual(originals, {p.name:p.read_bytes() for p in self.photos.iterdir()})
        development = evaluate(self.manifest, self.photos, split='development')
        self.assertEqual((development['scenes'], development['captures']), (1, 2))
        self.assertNotIn('different_scenes', development['conditions'])
        self.assertEqual(development['scene_exposure_risk']['scenes'], 1)
        self.assertGreaterEqual(development['scene_exposure_risk']['scene_strong_candidate_wilson95_upper'], 0.79)

    def test_tamper_and_duplicate_capture_bytes_rejected(self):
        sample = self.photos / 'scene1_0.png'
        sample.write_bytes(sample.read_bytes() + b'alteration')
        with self.assertRaisesRegex(ValueError, 'integrity mismatch'):
            evaluate(self.manifest, self.photos)
        self.assertEqual(set(x.name for x in self.root.iterdir()), {'photos','curated.json'})
        sample.write_bytes(sample.read_bytes()[:-10])
        self.data['scenes'][0]['captures'][1]['sha256'] = self.data['scenes'][0]['captures'][0]['sha256']
        self._write()
        with self.assertRaisesRegex(ValueError, 'Duplicate bytes'):
            evaluate(self.manifest, self.photos)

    def test_symlinks_and_traversal_refused(self):
        self.data['scenes'][0]['captures'][0]['file'] = '../outside.jpg'
        self._write()
        with self.assertRaisesRegex(ValueError, 'relative capture path'):
            evaluate(self.manifest, self.photos)
        symlink = self.photos / 'alias.png'
        symlink.symlink_to(self.photos / 'scene1_0.png')
        self.data['scenes'][0]['captures'][0]['file'] = 'alias.png'
        self._write()
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            evaluate(self.manifest, self.photos)

    def test_metadata_and_partition_validation(self):
        self.data['scenes'][0]['captures'][0]['license'] = 'UNKNOWN'
        self._write()
        with self.assertRaisesRegex(ValueError, 'license'):
            evaluate(self.manifest, self.photos)
        self.data['scenes'][0]['captures'][0]['license'] = 'CC-BY-4.0'
        self.data['scenes'][0]['split'] = 'unverified'
        self._write()
        with self.assertRaisesRegex(ValueError, 'split'):
            evaluate(self.manifest, self.photos)
        self.data['scenes'][0]['split'] = 'holdout'
        self.data['scenes'][2]['id'] = 'scene2'
        self._write()
        with self.assertRaisesRegex(ValueError, 'unique'):
            evaluate(self.manifest, self.photos)


    def test_license_reference_mismatch_blocked_before_media_matching(self):
        original = self.data["scenes"][0]["captures"][0]
        original["license"] = "CC-BY-4.0"
        original["license_url"] = "https://creativecommons.org/licenses/by-sa/4.0/"
        self._write()
        with self.assertRaisesRegex(ValueError, "license reference"):
            evaluate(self.manifest, self.photos)
        original["license_url"] = "https://creativecommons.org/licenses/by/4.0/"
        original["license"] = "CC0-1.0"
        self._write()
        with self.assertRaisesRegex(ValueError, "license reference"):
            evaluate(self.manifest, self.photos)
        original["license_url"] = "https://creativecommons.org/publicdomain/zero/1.0/"
        self._write()
        report = evaluate(self.manifest, self.photos, split="development")
        self.assertEqual(report["scenes"], 1)

    def test_canonical_scene_group_leakage_rejected_before_matcher(self):
        # Fictional development group silently relabeled as a holdout scene.
        self.data['scenes'][2]['scene_group'] = self.data['scenes'][0]['scene_group']
        self._write()
        with self.assertRaisesRegex(ValueError, 'Duplicate scene_group'):
            evaluate(self.manifest, self.photos, split='holdout')

    def test_origin_page_alias_across_splits_rejected_before_matcher(self):
        # SHA bytes differ, but the source-page claim is identical: do not trust.
        origin = self.data['scenes'][0]['captures'][0]['source_url']
        self.data['scenes'][2]['captures'][0]['source_url'] = origin
        self._write()
        with self.assertRaisesRegex(ValueError, 'source_url'):
            evaluate(self.manifest, self.photos, split='holdout')

    def test_reused_filename_alias_and_v1_manifest_fail_closed(self):
        self.data['scenes'][2]['captures'][0]['file'] = self.data['scenes'][0]['captures'][0]['file']
        self._write()
        with self.assertRaisesRegex(ValueError, 'path reused'):
            evaluate(self.manifest, self.photos, split='holdout')
        self.data['version'] = 1
        self._write()
        with self.assertRaisesRegex(ValueError, 'v2 required'):
            evaluate(self.manifest, self.photos, split='holdout')


    def test_duplicate_manifest_properties_and_nonfinite_values_refused(self):
        """No last-wins versions or source claims, even in an unselected split."""
        baseline = json.dumps(self.data)
        variants = (
            baseline.replace('"version": 2', '"version": 1, "version": 2', 1),
            baseline.replace('"file": "scene1_0.png"',
                             '"file": "../surprise.png", "file": "scene1_0.png"', 1),
            baseline.replace('"source_url": "https://commons.wikimedia.org/wiki/File:placeholder_scene3_0.jpg"',
                             '"source_url": "https://evil.invalid/photo", "source_url": "https://commons.wikimedia.org/wiki/File:placeholder_scene3_0.jpg"', 1),
            baseline.replace('"sha256": "', '"sha256": "0", "sha256": "', 1),
        )
        for variant in variants:
            with self.subTest(variant=variant.index('", "')), self.assertRaisesRegex(
                    ValueError, 'Duplicate provenance JSON property'):
                self.manifest.write_text(variant, encoding='utf-8')
                evaluate(self.manifest, self.photos, split='holdout')
        self.manifest.write_text(baseline.replace('"version": 2', '"version": NaN', 1),
                                 encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Nonfinite provenance JSON number'):
            evaluate(self.manifest, self.photos)

    def test_ancestor_symlink_for_manifest_and_hash_is_refused(self):
        """Neither manifest nor checksum follows a swapped parent directory."""
        from tools.photo_lineage.heldout_eval import _sha256
        alias = self.root / 'alias'
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Manifest path unsafe'):
            evaluate(alias / 'curated.json', self.photos)
        with self.assertRaisesRegex(ValueError, 'Capture path became unsafe'):
            _sha256(alias / 'photos' / 'scene1_0.png')
        leaf = self.photos / 'image-alias.png'
        leaf.symlink_to(self.photos / 'scene1_0.png')
        with self.assertRaisesRegex(ValueError, 'Capture path became unsafe'):
            _sha256(leaf)

    def test_oversize_manifest_fails_before_json_parse(self):
        self.manifest.write_bytes(b' ' * 1_000_001)
        with self.assertRaisesRegex(ValueError, 'bounded regular manifest'):
            evaluate(self.manifest, self.photos)



if __name__ == '__main__':
    unittest.main()
