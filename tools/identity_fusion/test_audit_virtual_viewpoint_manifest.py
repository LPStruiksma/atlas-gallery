#!/usr/bin/env python3
"""Anonymous, invented virtual-camera metadata regression; no photographs."""
from __future__ import annotations

import contextlib
import copy
import csv
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

from audit_virtual_viewpoint_manifest import (
    FIELDS, _has_independent_view_pair, audit_virtual_viewpoint_manifest, main,
)


def synthetic_virtual_views():
    entries = []
    for split in ("calibration", "holdout"):
        for n in range(20):
            subject = f"{split}_Known_{n}"
            entries.extend([
                dict(virtual_character_uid=subject, split=split,
                     camera_uid="Cam_1", view_index="1",
                     role="gallery_known", reviewed_visibility="front",
                     capture_component_uid=subject + "_Source_A"),
                dict(virtual_character_uid=subject, split=split,
                     camera_uid="Cam_2", view_index="19",
                     role="query_known", reviewed_visibility="back",
                     capture_component_uid=subject + "_Source_B"),
            ])
        for n in range(52):
            subject = f"{split}_Unknown_{n}"
            entries.extend([
                dict(virtual_character_uid=subject, split=split,
                     camera_uid="Cam_3", view_index="19",
                     role="query_unknown", reviewed_visibility="back+occluded",
                     capture_component_uid=subject + "_Source_C"),
                dict(virtual_character_uid=subject, split=split,
                     camera_uid="Cam_4", view_index="10",
                     role="query_unknown", reviewed_visibility="side",
                     capture_component_uid=subject + "_Source_D"),
            ])
    return entries


def synthetic_personx_canonical_views():
    """Remap fictional character tokens to canonical 0001..0144 profiles."""
    rows = copy.deepcopy(synthetic_virtual_views())
    characters = sorted({row["virtual_character_uid"] for row in rows})
    personx = {name: f"{i+1:04d}" for i, name in enumerate(characters)}
    for row in rows:
        row["virtual_character_uid"] = personx[row["virtual_character_uid"]]
    return rows


class VirtualViewpointMetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = synthetic_virtual_views()

    def test_full_fictional_metadata_study_has_rear_known_and_unknown_coverage(self):
        result = audit_virtual_viewpoint_manifest(
            self.rows, require_unknown_tags=("back", "occluded"))
        self.assertEqual(result["status"],
                         "virtual_metadata_structure_only_NOT_DATASET_ADMISSION")
        self.assertEqual(result["required_unknown_visibility_tags"],
                         ["back", "occluded"])
        for split in ("calibration", "holdout"):
            info = result["partitions"][split]
            self.assertEqual(info["distinct_virtual_known_characters"], 20)
            self.assertEqual(info["known_with_cross_camera_and_source_front_rear_pair"],
                             20)
            self.assertEqual(info["distinct_virtual_unknown_characters"], 52)
            self.assertEqual(info["unknown_with_reviewed_back_view"], 52)
            self.assertEqual(info["unknown_back_declared_capture_components"], 52)
            self.assertEqual(info["unknown_characters_by_marginal_visibility_tag"][
                             "occluded"], 52)
            self.assertFalse(info["camera_distinction_proves_independent_image_generation"])
        for key in (
            "dataset_rights_or_assets_verified",
            "remote_archive_bytes_or_origin_authenticated",
            "viewpoint_index_to_physical_front_back_verified",
            "pose_labels_independently_reviewed",
            "model_training_overlap_excluded_by_external_evidence",
            "genuine_holdout_chronology_authenticated",
            "nominal_minima_prove_statistical_error_budget",
            "auto_confirm_allowed",
            "production_approved",
        ):
            self.assertFalse(result[key], key)
        self.assertEqual(result, audit_virtual_viewpoint_manifest(
            list(reversed(self.rows)),
            require_unknown_tags=("occluded", "back")))
        serial = json.dumps(result)
        for private in ("Unknown_1", "Known_1", "Source_A", "Cam_3",
                        "virtual_character_uid", "/storage", ".jpg"):
            self.assertNotIn(private, serial)

    def test_one_missing_unknown_subject_or_missing_rear_perspective_refused(self):
        rows = copy.deepcopy(self.rows)
        rows = [r for r in rows
                if r["virtual_character_uid"] != "holdout_Unknown_51"]
        with self.assertRaisesRegex(ValueError, "Too few"):
            audit_virtual_viewpoint_manifest(rows)
        rows = copy.deepcopy(self.rows)
        for r in rows:
            if r["virtual_character_uid"] == "holdout_Unknown_0":
                r["reviewed_visibility"] = "side"
        with self.assertRaisesRegex(ValueError, "Too few"):
            audit_virtual_viewpoint_manifest(rows)

    def test_gallery_and_query_from_one_source_or_camera_cannot_establish_pair(self):
        rows = copy.deepcopy(self.rows)
        for r in rows:
            if (r["virtual_character_uid"] == "holdout_Known_0"
                    and r["role"] == "query_known"):
                r["camera_uid"] = "Cam_1"
        with self.assertRaisesRegex(ValueError, "Too few"):
            audit_virtual_viewpoint_manifest(rows)
        rows = copy.deepcopy(self.rows)
        for r in rows:
            if (r["virtual_character_uid"] == "holdout_Known_0"
                    and r["role"] == "query_known"):
                r["capture_component_uid"] = "holdout_Known_0_Source_A"
        with self.assertRaisesRegex(ValueError, "reuse a capture"):
            audit_virtual_viewpoint_manifest(rows)

    def test_same_render_cannot_be_reused_as_multiple_queries(self):
        rows = copy.deepcopy(self.rows)
        repeated = dict(rows[0], role="query_known")
        with self.assertRaisesRegex(ValueError, "assigned multiple"):
            audit_virtual_viewpoint_manifest(rows + [repeated])
        copy_row = dict(rows[0], split="holdout",
                        camera_uid="Cam_5", view_index="3",
                        virtual_character_uid="holdout_Unknown_20")
        with self.assertRaisesRegex(ValueError, "Capture source reused"):
            audit_virtual_viewpoint_manifest(rows + [copy_row])

    def test_virtual_character_or_source_cannot_cross_calibration_holdout(self):
        rows = copy.deepcopy(self.rows)
        for row in rows:
            if row["virtual_character_uid"] == "holdout_Unknown_0":
                row["virtual_character_uid"] = "calibration_Unknown_0"
        with self.assertRaisesRegex(ValueError, "reused between"):
            audit_virtual_viewpoint_manifest(rows)
        rows = copy.deepcopy(self.rows)
        rows[-1]["capture_component_uid"] = rows[0]["capture_component_uid"]
        with self.assertRaisesRegex(ValueError, "Capture source reused"):
            audit_virtual_viewpoint_manifest(rows)

    def test_unknown_person_cannot_be_in_gallery_and_known_must_have_both_roles(self):
        rows = copy.deepcopy(self.rows)
        for row in rows:
            if (row["virtual_character_uid"] == "holdout_Unknown_0"
                    and row["camera_uid"] == "Cam_4"):
                row["role"] = "gallery_known"
        with self.assertRaisesRegex(ValueError, "Unknown virtual identity mixed"):
            audit_virtual_viewpoint_manifest(rows)
        rows = copy.deepcopy(self.rows)
        for row in rows:
            if (row["virtual_character_uid"] == "holdout_Known_0"
                    and row["role"] == "gallery_known"):
                row["role"] = "query_known"
        with self.assertRaisesRegex(ValueError, "requires both"):
            audit_virtual_viewpoint_manifest(rows)

    def test_bad_viewpoint_indices_and_privacy_fields_are_refused(self):
        for bad in ("0", "37", "01", "-1", "18.0", "NaN", "999999"):
            rows = copy.deepcopy(self.rows)
            rows[0]["view_index"] = bad
            with self.subTest(index=bad), self.assertRaises(ValueError):
                audit_virtual_viewpoint_manifest(rows)
        for bad in ("unknown+back", "front+back", "back+back", "lookalike+back",
                    "unreviewed", "front/side"):
            rows = copy.deepcopy(self.rows)
            rows[0]["reviewed_visibility"] = bad
            with self.subTest(condition=bad), self.assertRaises(ValueError):
                audit_virtual_viewpoint_manifest(rows)
        rows = copy.deepcopy(self.rows)
        rows[0]["photo_path"] = "/storage/private/people.jpg"
        with self.assertRaisesRegex(ValueError, "private"):
            audit_virtual_viewpoint_manifest(rows)
        rows = copy.deepcopy(self.rows)
        rows[0]["capture_component_uid"] = "UNREVIEWED"
        with self.assertRaises(ValueError):
            audit_virtual_viewpoint_manifest(rows)

    def test_protected_visibility_requirements_require_52_subjects_both_splits(self):
        rows = copy.deepcopy(self.rows)
        for r in rows:
            if (r["split"] == "holdout"
                    and r["virtual_character_uid"] == "holdout_Unknown_51"):
                if r["reviewed_visibility"] == "back+occluded":
                    r["reviewed_visibility"] = "back"
        audit_virtual_viewpoint_manifest(rows, require_unknown_tags=("back",))
        with self.assertRaisesRegex(ValueError, "Missing preregistered"):
            audit_virtual_viewpoint_manifest(
                rows, require_unknown_tags=("occluded",))
        for bad in (("unknown",), ("side", "side"), ("back+occluded",),
                    ("back",) * 9):
            with self.subTest(tags=bad), self.assertRaises(ValueError):
                audit_virtual_viewpoint_manifest(self.rows,
                                                require_unknown_tags=bad)


    def test_known_query_reusing_gallery_source_refused_even_with_another_valid_pair(self):
        rows = copy.deepcopy(self.rows)
        first = next(row for row in rows
                     if row["virtual_character_uid"] == "holdout_Known_0"
                     and row["role"] == "gallery_known")
        independent_valid = next(row for row in rows
                                 if row["virtual_character_uid"] == "holdout_Known_0"
                                 and row["role"] == "query_known")
        self.assertNotEqual(first["camera_uid"], independent_valid["camera_uid"])
        self.assertNotEqual(first["capture_component_uid"],
                            independent_valid["capture_component_uid"])
        # Previously the existential 'any valid front/rear pair' check
        # would pass even while *another* known query duplicated the
        # gallery's capture (same photo/crop under a new camera+view).
        disguised_copy = dict(
            independent_valid,
            camera_uid="Cam_fake_other_camera", view_index="20",
            capture_component_uid=first["capture_component_uid"])
        with self.assertRaisesRegex(ValueError, "reuse a capture"):
            audit_virtual_viewpoint_manifest(rows + [disguised_copy])

    def test_known_source_cannot_cross_roles_even_when_not_a_front_rear_pair(self):
        rows = copy.deepcopy(self.rows)
        gallery = next(row for row in rows
                       if row["virtual_character_uid"] == "holdout_Known_0"
                       and row["role"] == "gallery_known")
        shadow = dict(gallery, role="query_known", camera_uid="Cam_shadow",
                      view_index="4", reviewed_visibility="side")
        with self.assertRaisesRegex(ValueError, "reuse a capture"):
            audit_virtual_viewpoint_manifest(rows + [shadow])

    def test_personx_profile_requires_canonical_0001_to_1266_and_no_id_zero(self):
        canonical = synthetic_personx_canonical_views()
        report = audit_virtual_viewpoint_manifest(
            canonical, dataset_profile="personx_v1",
            require_unknown_tags=("back", "occluded"))
        self.assertEqual(report["dataset_profile_declared_not_authenticated"],
                         "personx_v1")
        self.assertTrue(
            report["personx_duplicate_zero_and_noncanonical_ids_refused"])
        self.assertTrue(
            report["all_known_gallery_query_capture_role_overlap_refused"])
        self.assertFalse(report["remote_archive_bytes_or_origin_authenticated"])
        self.assertFalse(report["production_approved"])
        seen = {r["virtual_character_uid"] for r in canonical}
        self.assertEqual(len(seen), 144)
        for bad in ("0000", "0", "00", "000", "1", "01", "00001",
                    "1267", "9999", "000a", "NONE", "UNKNOWN"):
            malicious = copy.deepcopy(canonical)
            source = next(row["virtual_character_uid"] for row in malicious
                          if row["split"] == "holdout"
                          and row["role"] == "query_unknown")
            for row in malicious:
                if row["virtual_character_uid"] == source:
                    row["virtual_character_uid"] = bad
            with self.subTest(alias=bad), self.assertRaises(ValueError):
                audit_virtual_viewpoint_manifest(
                    malicious, dataset_profile="personx_v1")
        # Generic independent-fictional-ID mode also refuses ID zero.
        for bad in ("0", "00", "0000"):
            generic = copy.deepcopy(self.rows)
            original = "holdout_Unknown_0"
            for row in generic:
                if row["virtual_character_uid"] == original:
                    row["virtual_character_uid"] = bad
            with self.subTest(generic_zero=bad), self.assertRaisesRegex(
                    ValueError, "initialization"):
                audit_virtual_viewpoint_manifest(generic)
        with self.assertRaises(ValueError):
            audit_virtual_viewpoint_manifest(
                canonical, dataset_profile="PersonX")
        with self.assertRaises(ValueError):
            audit_virtual_viewpoint_manifest(
                canonical, dataset_profile=True)

    def test_linear_camera_source_pair_matches_independent_cartesian_oracle(self):
        # Cross-product over small arrays finds all tricky inclusion/exclusion
        # cases: same camera, same source, both equal, and independent pairs.
        import itertools
        universe = (("A", "X"), ("A", "Y"), ("B", "X"),
                    ("B", "Y"), ("C", "Z"))
        lists = [()]
        for size in (1, 2):
            lists.extend(itertools.product(universe, repeat=size))
        for fronts in lists:
            for backs in lists:
                expected = any(cam_a != cam_b and src_a != src_b
                               for cam_a, src_a in fronts
                               for cam_b, src_b in backs)
                self.assertEqual(
                    _has_independent_view_pair(fronts, backs), expected)
        # Very large invented candidate rosters should not require a
        # quadratic front×rear score-matrix allocation.
        many = [(f"Cam_{i}", f"Source_{i}") for i in range(6000)]
        self.assertTrue(_has_independent_view_pair(many, list(reversed(many))))
        self.assertFalse(_has_independent_view_pair(
            [("Camera_1", "Source_A")] * 6000,
            [("Camera_1", "Source_B")] * 6000))

    def test_cli_optional_personx_profile_accepts_canonical_metadata_only(self):
        with tempfile.TemporaryDirectory(prefix="fictional-personx-pins-") as home:
            file = Path(home) / "authored_no_images.csv"
            with file.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerows(synthetic_personx_canonical_views())
            before = file.read_bytes()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = main([str(file), "--dataset-profile", "personx_v1",
                             "--require-unknown-tag", "back"])
            self.assertEqual(code, 0)
            report = json.loads(output.getvalue())
            self.assertEqual(report["dataset_profile_declared_not_authenticated"],
                             "personx_v1")
            self.assertEqual(before, file.read_bytes())
            self.assertNotIn("0001", output.getvalue())
            self.assertNotIn(home, output.getvalue())

    def test_csv_cli_reads_anonymous_bytes_only_no_writes_or_path_echo(self):
        with tempfile.TemporaryDirectory(prefix="fictitious-personx-") as home:
            root = Path(home)
            source = root / "invented_metadata.csv"
            with source.open("w", encoding="utf-8", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerows(self.rows)
            original = source.read_bytes()
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(main([str(source),
                                       "--require-unknown-tag", "back",
                                       "--require-unknown-tag", "occluded"]), 0)
            parsed = json.loads(out.getvalue())
            self.assertEqual(parsed["partitions"]["holdout"][
                "known_with_cross_camera_and_source_front_rear_pair"], 20)
            self.assertEqual(source.read_bytes(), original)
            self.assertNotIn("holdout_Unknown", out.getvalue())
            self.assertNotIn(home, out.getvalue())
            alias = root / "linked_metadata.csv"
            alias.symlink_to(source)
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(main([str(alias)]), 2)
            self.assertNotIn(home, error.getvalue())
            self.assertNotIn("invented_metadata", error.getvalue())
            alias.unlink()
            os.link(source, alias)
            with contextlib.redirect_stderr(error):
                self.assertEqual(main([str(source)]), 2)
            alias.unlink()
            source.write_bytes(b"virtual_character_uid,photo_path\n"
                               b"Fiction,/private/photo.jpg\n")
            with contextlib.redirect_stderr(error):
                self.assertEqual(main([str(source)]), 2)
            self.assertNotIn("photo.jpg", error.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
