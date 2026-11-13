"""Pure fictional provenance fixtures: no images or downloads."""
import unittest

from tools.photo_lineage.provenance_guard import _canonical_source_page, validate_provenance_groups


def fake_manifest():
    return [{
        'id': f'scene_{scene}', 'scene_group': f'canonical_group_{scene}',
        'split': 'development' if scene == 'A' else 'holdout',
        'captures': [{
            'id': f'exposure_{scene}_{i}', 'file': f'{scene}_{i}.jpg',
            'source_url': f'https://commons.wikimedia.org/wiki/File:fictional_{scene}_{i}.jpg'
        } for i in range(2)]
    } for scene in ('A', 'B')]


class ProvenanceGuardTests(unittest.TestCase):
    def test_valid_two_partitions_and_independent_sources(self):
        self.assertIsNone(validate_provenance_groups(2, fake_manifest()))

    def test_alias_group_in_other_split_blocked(self):
        scenes = fake_manifest()
        scenes[1]['scene_group'] = scenes[0]['scene_group']
        with self.assertRaisesRegex(ValueError, 'scene_group'):
            validate_provenance_groups(2, scenes)

    def test_duplicate_source_page_cross_partition_even_distinct_bytes(self):
        scenes = fake_manifest()
        scenes[1]['captures'][0]['source_url'] = scenes[0]['captures'][0]['source_url']
        with self.assertRaisesRegex(ValueError, 'source_url'):
            validate_provenance_groups(2, scenes)

    def test_canonical_percent_encoded_source_aliases_blocked(self):
        scenes = fake_manifest()
        scenes[1]['captures'][0]['source_url'] = 'https://COMMONS.WIKIMEDIA.ORG/wiki/File%3Afictional_A_0.jpg'
        self.assertEqual(_canonical_source_page(scenes[0]['captures'][0]['source_url']),
                         _canonical_source_page(scenes[1]['captures'][0]['source_url']))
        with self.assertRaisesRegex(ValueError, 'source_url'):
            validate_provenance_groups(2, scenes)

    def test_double_encoded_source_page_cannot_bypass_source_split(self):
        original = "https://commons.wikimedia.org/wiki/File:fictional_A_0.jpg"
        variants = (
            "https://commons.wikimedia.org/wiki/File%253Afictional_A_0.jpg",
            "https://commons.wikimedia.org/wiki/File%25253Afictional_A_0.jpg",
            "https://commons.wikimedia.org/wiki/File%25ZZfictional_A_0.jpg",
        )
        self.assertTrue(_canonical_source_page(original).endswith("/wiki/File:fictional_A_0.jpg"))
        for alias in variants:
            with self.subTest(alias=alias), self.assertRaisesRegex(ValueError, "source URL"):
                scenes = fake_manifest()
                scenes[1]["captures"][0]["source_url"] = alias
                validate_provenance_groups(2, scenes)

    def test_nfc_unicode_equivalent_source_pages_are_not_independent(self):
        composed = "https://commons.wikimedia.org/wiki/File:caf%C3%A9_A.jpg"
        decomposed = "https://commons.wikimedia.org/wiki/File:cafe%CC%81_A.jpg"
        self.assertEqual(_canonical_source_page(composed),
                         _canonical_source_page(decomposed))
        scenes = fake_manifest()
        scenes[0]["captures"][0]["source_url"] = composed
        scenes[1]["captures"][0]["source_url"] = decomposed
        with self.assertRaisesRegex(ValueError, "source_url"):
            validate_provenance_groups(2, scenes)

    def test_malformed_host_and_path_aliases_fail_closed(self):
        urls = (
            "https://commons.wikimedia.org./wiki/File:photo.jpg",
            "https://.commons.wikimedia.org/wiki/File:photo.jpg",
            "https://commons..wikimedia.org/wiki/File:photo.jpg",
            "https://commons.wikimedia.org/wiki//File:photo.jpg",
            "https://commons.wikimedia.org/wiki/File%5Cname.jpg",
            "https://commons.wikimedia.org/wiki/File%0Aname.jpg",
            "https://commons.wikimedia.org/wiki/File%7Fname.jpg",
            "https://commons.wikimedia.org/wiki/File%ZZname.jpg",
            "https://commons.wikimedia.org/wiki/File:photo.jpg\n",
            "https://commons.wikimedia.org/wiki/File:photo.jpg\t",
        )
        for url in urls:
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, "URL"):
                _canonical_source_page(url)

    def test_valid_single_encoded_wikimedia_filename_remains_usable(self):
        unescaped = "https://commons.wikimedia.org/wiki/File:NIND_droid_ISO200.jpg"
        encoded = "https://COMMONS.WIKIMEDIA.ORG/wiki/File%3ANIND_droid_ISO200.jpg"
        self.assertEqual(_canonical_source_page(unescaped),
                         _canonical_source_page(encoded))
        self.assertEqual(_canonical_source_page(
            "https://commons.wikimedia.org/wiki/File%3AA%20B.jpg"),
            _canonical_source_page(
            "https://commons.wikimedia.org/wiki/File:A B.jpg"))

    def test_reused_local_file_even_different_claimed_sha_blocked(self):
        scenes = fake_manifest()
        scenes[1]['captures'][0]['file'] = scenes[0]['captures'][1]['file']
        with self.assertRaisesRegex(ValueError, 'path reused'):
            validate_provenance_groups(2, scenes)

    def test_old_v1_and_boolean_version_refused(self):
        for version in (1, True, 0, '2', None):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'v2 required'):
                validate_provenance_groups(version, fake_manifest())

    def test_missing_group_or_ambiguous_group_refused(self):
        for bad in (None, '', '../bad', 'a b', 'bad\x00group'):
            scenes = fake_manifest()
            scenes[0]['scene_group'] = bad
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'scene_group'):
                validate_provenance_groups(2, scenes)

    def test_unstable_untrusted_source_urls_refused(self):
        bad_urls = (
            'http://commons.wikimedia.org/wiki/X',
            'https://example.com/wiki/X?variant=1',
            'https://example.com/wiki/X#fragment',
            'https://user@example.com/wiki/X',
            'https://example.com:8080/wiki/X',
            'https://example.com/wiki/%2e%2e/secret',
            'https://example.com/',
            'https://example.com/wiki/X\x00',
        )
        for url in bad_urls:
            scenes = fake_manifest()
            scenes[0]['captures'][0]['source_url'] = url
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, 'URL'):
                validate_provenance_groups(2, scenes)

    def test_malformed_paths_rejected_in_unselected_split(self):
        for path in ('../escape.png', '/absolute.png', 'x/./a.png', 'x\\a.png', 'x\x00a.png'):
            scenes = fake_manifest()
            scenes[0]['captures'][0]['file'] = path
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'path'):
                validate_provenance_groups(2, scenes)

    def test_overlarge_manifest_and_capture_bounds(self):
        with self.assertRaisesRegex(ValueError, '1..20'):
            validate_provenance_groups(2, fake_manifest() * 11)
        scenes = fake_manifest()
        scenes[0]['captures'] *= 5
        with self.assertRaisesRegex(ValueError, '2..8'):
            validate_provenance_groups(2, scenes)


if __name__ == '__main__':
    unittest.main()
