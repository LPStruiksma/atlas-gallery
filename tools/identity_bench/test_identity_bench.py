"""Unit tests for the identity bench: synthetic tensors only, no downloads, no models.

    python3 -m unittest tools/identity_bench/test_identity_bench.py
"""
import io
import os
import re
import tempfile
import unittest
from unittest import mock
import zipfile

import numpy as np

try:
    import cv2
except ImportError:  # pragma: no cover
    cv2 = None

if __package__:
    from . import fixtures, metrics, pipeline, sheets
    from . import __main__ as cli
else:  # run as a file path: python3 -m unittest tools/identity_bench/test_identity_bench.py
    import sys
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    from tools.identity_bench import fixtures, metrics, pipeline, sheets
    from tools.identity_bench import __main__ as cli

HERE = os.path.dirname(os.path.abspath(__file__))


class LicenceAdmission(unittest.TestCase):
    def test_every_known_model_has_an_explicit_bench_admission_decision(self):
        known = set(pipeline.MODELS) | set(pipeline.LOCAL_MODELS) | set(pipeline.SHIPPED_MODELS)
        self.assertEqual(known, pipeline.BENCH_ALLOWED | pipeline.BENCH_BLOCKED)
        self.assertFalse(pipeline.BENCH_ALLOWED & pipeline.BENCH_BLOCKED)

    def test_alphaface_is_refused_before_cache_or_download(self):
        with mock.patch.object(pipeline, "cache_dir", side_effect=AssertionError("cache accessed")), \
             mock.patch.object(pipeline, "_download", side_effect=AssertionError("network accessed")):
            with self.assertRaisesRegex(PermissionError, r"LICENCES\.md.*author permission"):
                pipeline.Config("alphaface_256")
            with self.assertRaisesRegex(PermissionError, r"LICENCES\.md.*author permission"):
                pipeline.model_path("alphaface_256")

    def test_unknown_models_fail_closed(self):
        with self.assertRaisesRegex(PermissionError, r"LICENCES\.md"):
            pipeline.require_bench_model("new_unreviewed_model")
        with self.assertRaisesRegex(PermissionError, r"LICENCES\.md"):
            pipeline.model_path("new_unreviewed_model", fetch=False)

    def test_permitted_research_paths_include_converters_and_separate_judge(self):
        for model in ("inswapper_128", "hyperswap_1a_256", "arcface_w600k_r50",
                      "crossface_simswap", "crossface_ghost", "glintr100",
                      "ghost_backbone_fp32", "gfpgan_1.4"):
            pipeline.require_bench_model(model)
        ghost = pipeline.candidate_dependencies(pipeline.Config("ghost_1_256"))
        simswap = pipeline.candidate_dependencies(pipeline.Config("simswap_256"))
        exported = pipeline.candidate_dependencies(pipeline.Config("ghost1_generator_fp32"))
        self.assertIn("crossface_ghost", ghost)
        self.assertIn("crossface_simswap", simswap)
        self.assertIn("ghost_backbone_fp32", exported)
        self.assertIn("glintr100", ghost)
        self.assertNotIn("crossface_ghost", exported)

    def test_candidate_mask_and_restoration_dependencies_are_checked(self):
        deps = pipeline.candidate_dependencies(
            pipeline.Config("inswapper_128", mask="mp", restore=True))
        self.assertIn("selfie_multiclass_256", deps)
        self.assertIn("gfpgan_1.4", deps)
        self.assertNotIn("bisenet_resnet_34", deps)
        with self.assertRaises(ValueError):
            pipeline.Config("ghost_1_256", mask="unregistered")


def photo(seed=1, h=240, w=320):
    """A smooth random colour field: synthetic, but with gradients like a photo."""
    rng = np.random.default_rng(seed)
    coarse = rng.integers(0, 255, (h // 16 + 1, w // 16 + 1, 3)).astype(np.uint8)
    return cv2.resize(coarse, (w, h), interpolation=cv2.INTER_CUBIC)


MARKS = np.array([[130.0, 100.0], [190.0, 101.0], [160.0, 135.0], [136.0, 165.0], [184.0, 166.0]])


class Invert:
    """Stand-in swap network: no weights, flips the colours of the crop."""
    template, side = "arcface_128", 128

    def source_vector(self, raw):
        return None

    def swap_crop(self, crop, source):
        return 255 - crop


@unittest.skipIf(cv2 is None, "OpenCV not installed")
class Geometry(unittest.TestCase):
    def test_similarity_transform_recovers_a_known_motion(self):
        angle, scale, shift = 0.3, 1.7, np.array([12.0, -5.0])
        rot = scale * np.array([[np.cos(angle), -np.sin(angle)], [np.sin(angle), np.cos(angle)]])
        m = pipeline.similarity_transform(MARKS, MARKS @ rot.T + shift)
        np.testing.assert_allclose(m[:, :2], rot, atol=1e-9)
        np.testing.assert_allclose(m[:, 2], shift, atol=1e-9)

    def test_similarity_transform_never_mirrors(self):
        mirrored = MARKS * [-1, 1]
        m = pipeline.similarity_transform(MARKS, mirrored)
        self.assertGreater(np.linalg.det(m[:, :2]), 0)

    def test_align_puts_the_landmarks_on_the_template(self):
        _, m = pipeline.align(photo(), MARKS, "arcface_128", 128)
        placed = MARKS @ m[:, :2].T + m[:, 2]
        self.assertLess(np.abs(placed - pipeline.template_points("arcface_128", 128)).max(), 3.0)

    def test_box_mask_is_zero_on_the_crop_border_and_one_inside(self):
        for blur in (0.0, 0.04):
            mask = pipeline.box_mask(128, blur=blur)
            self.assertEqual(float(mask[0].max()), 0.0)
            self.assertEqual(float(mask[:, -1].max()), 0.0)
            self.assertAlmostEqual(float(mask[64, 64]), 1.0, places=5)

    def test_feather_only_shrinks(self):
        binary = np.zeros((64, 64), bool)
        binary[20:44, 20:44] = True
        soft = pipeline.feather(binary, 2.0)
        self.assertEqual(float(soft[~binary].max()), 0.0)
        self.assertAlmostEqual(float(soft[32, 32]), 1.0, places=5)

    def test_colour_match_moves_the_crop_towards_the_original(self):
        original = np.full((32, 32, 3), (60, 90, 150), np.uint8) + photo(2, 32, 32) // 8
        shifted = np.clip(original.astype(int) + 50, 0, 255).astype(np.uint8)
        fixed = pipeline.colour_match(shifted, original, np.ones((32, 32), np.float32))
        before = np.abs(shifted.astype(float) - original).mean()
        self.assertLess(np.abs(fixed.astype(float) - original).mean(), before / 3)


@unittest.skipIf(cv2 is None, "OpenCV not installed")
class PasteBack(unittest.TestCase):
    def swap(self, config, **kw):
        return pipeline.swap_face(photo(), MARKS, None, config, pipeline.Toolbox(), swapper=Invert(), **kw)

    def test_pixels_outside_the_mask_are_bit_identical(self):
        source = photo()
        result = self.swap(pipeline.Config("fake", mask="box", colour=False))
        outside = result.mask == 0
        self.assertGreater(int(outside.sum()), 1000)
        self.assertTrue(np.array_equal(result.image[outside], source[outside]))
        change = metrics.outside_change(source, result.image, result.mask)
        self.assertEqual(change["outside_changed_px"], 0)
        self.assertEqual(change["outside_psnr"], float("inf"))

    def test_the_face_region_really_changes(self):
        source = photo()
        result = self.swap(pipeline.Config("fake", mask="box", colour=False))
        solid = result.mask >= 0.98
        self.assertGreater(int(solid.sum()), 500)
        self.assertGreater(np.abs(result.image[solid].astype(int) - source[solid]).mean(), 20)

    def test_the_source_image_is_not_modified(self):
        source = photo()
        before = source.copy()
        pipeline.swap_face(source, MARKS, None, pipeline.Config("fake", mask="box", colour=False),
                           pipeline.Toolbox(), swapper=Invert())
        self.assertTrue(np.array_equal(source, before))

    def test_same_input_gives_the_same_bytes_and_jitter_moves_it(self):
        config = pipeline.Config("fake", mask="box", colour=False)
        a, b = self.swap(config), self.swap(config)
        self.assertTrue(np.array_equal(a.image, b.image))
        moved = self.swap(config, jitter=np.full((5, 2), 2.0))
        self.assertFalse(np.array_equal(a.image, moved.image))

    def test_a_face_at_the_frame_edge_does_not_crash_or_leak(self):
        source = photo()
        marks = MARKS - [120, 90]
        result = pipeline.swap_face(source, marks, None, pipeline.Config("fake", mask="box", colour=False),
                                    pipeline.Toolbox(), swapper=Invert())
        outside = result.mask == 0
        self.assertTrue(np.array_equal(result.image[outside], source[outside]))

    def test_hard_edge_control_has_a_worse_seam_than_the_feathered_mask(self):
        source = photo()
        soft = self.swap(pipeline.Config("fake", mask="box", colour=False))
        hard = self.swap(pipeline.Config("fake", mask="hard-box", colour=False))
        self.assertGreater(metrics.seam(source, hard.image, hard.mask)["seam_gradient"],
                           2 * metrics.seam(source, soft.image, soft.mask)["seam_gradient"])

    def test_pixel_boost_swaps_a_larger_crop_and_keeps_the_outside(self):
        source = photo()
        plain = self.swap(pipeline.Config("fake", mask="box", colour=False))
        boosted = self.swap(pipeline.Config("fake", mask="box", colour=False, boost=2))
        self.assertEqual(boosted.crop_after.shape[0], 2 * plain.crop_after.shape[0])
        self.assertTrue(np.array_equal(boosted.crop_after, 255 - boosted.crop_before))
        outside = boosted.mask == 0
        self.assertTrue(np.array_equal(boosted.image[outside], source[outside]))
        self.assertEqual(pipeline.Config("x", boost=2, restore=True).label, "x+boost2+gfpgan")

    def test_no_swap_control_changes_almost_nothing(self):
        source = photo()
        result = pipeline.swap_face(source, MARKS, None, pipeline.Config("control-none", mask="box", colour=False),
                                    pipeline.Toolbox())
        self.assertLess(np.abs(result.image.astype(int) - source).mean(), 2.0)

    def test_stage_meter_counts_every_stage(self):
        tools = pipeline.Toolbox()
        pipeline.swap_face(photo(), MARKS, None, pipeline.Config("fake", mask="box", colour=False), tools, swapper=Invert())
        for stage in ("align", "swap", "mask", "paste"):
            self.assertEqual(tools.meter.rows[stage]["calls"], 1)
            self.assertGreater(tools.meter.rows[stage]["peak_rss_mb"], 0)

    def test_candidate_labels(self):
        self.assertEqual(pipeline.Config("x").label, "x")
        self.assertEqual(pipeline.Config("x", restore=True).label, "x+gfpgan")
        self.assertEqual(pipeline.Config("x", mask="box", colour=False).label, "x+mask-box+no-colour")


class Identity(unittest.TestCase):
    def test_cosine_and_centroid(self):
        a, b = np.array([1.0, 0, 0]), np.array([0, 2.0, 0])
        self.assertAlmostEqual(metrics.cosine(a, 3 * a), 1.0)
        self.assertAlmostEqual(metrics.cosine(a, b), 0.0)
        self.assertAlmostEqual(float(np.linalg.norm(metrics.centroid([a, b]))), 1.0)

    def test_leave_one_out_is_high_for_a_tight_set_and_low_for_a_loose_one(self):
        rng = np.random.default_rng(3)
        centre = rng.normal(size=64)
        tight = [centre + 0.1 * rng.normal(size=64) for _ in range(4)]
        loose = [rng.normal(size=64) for _ in range(4)]
        self.assertGreater(metrics.leave_one_out(tight), 0.9)
        self.assertLess(metrics.leave_one_out(loose), 0.5)
        self.assertTrue(np.isnan(metrics.leave_one_out(tight[:1])))

    def test_rank_one_and_a_look_alike_confusion(self):
        rng = np.random.default_rng(4)
        me = rng.normal(size=64)
        gallery = {"me": me, "look-alike": me + 0.8 * rng.normal(size=64), "stranger": rng.normal(size=64)}
        rank, wrong, margin = metrics.rank_of(me + 0.1 * rng.normal(size=64), gallery, "me")
        self.assertEqual((rank, wrong), (1, "look-alike"))
        self.assertGreater(margin, 0)
        rank, wrong, margin = metrics.rank_of(gallery["look-alike"], gallery, "me")
        self.assertEqual((rank, wrong), (2, "look-alike"))
        self.assertLess(margin, 0)

    def test_paired_bootstrap_brackets_a_real_difference(self):
        rng = np.random.default_rng(5)
        base = rng.normal(0.5, 0.1, 60)
        d = metrics.paired_bootstrap(base + 0.05 + rng.normal(0, 0.01, 60), base)
        self.assertTrue(d["low"] < 0.05 < d["high"] and d["low"] > 0)

    def test_summary_ignores_missing_values(self):
        s = metrics.summary([0.2, None, float("nan"), 0.4])
        self.assertEqual(s["n"], 2)
        self.assertAlmostEqual(s["mean"], 0.3)
        self.assertEqual(metrics.summary([])["n"], 0)

    def test_verdict_applies_every_threshold(self):
        good = dict(judge_mean=0.6, verified_share=0.9, rank1=0.95, retention=0.8, outside_changed_px=0, seam_gradient=1.0)
        self.assertTrue(metrics.verdict(good, hard_edge_seam=10.0)["all"])
        self.assertIsNone(metrics.verdict(good, hard_edge_seam=float("nan"))["A5 seam"])
        self.assertTrue(metrics.verdict(good, hard_edge_seam=float("nan"))["all"])
        for key, bad in (("judge_mean", 0.4), ("verified_share", 0.5), ("rank1", 0.8), ("retention", 0.6),
                         ("outside_changed_px", 1), ("seam_gradient", 5.0)):
            self.assertFalse(metrics.verdict(dict(good, **{key: bad}), hard_edge_seam=10.0)["all"], key)


@unittest.skipIf(cv2 is None, "OpenCV not installed")
class Pixels(unittest.TestCase):
    def test_one_changed_pixel_outside_the_mask_is_caught(self):
        source = photo()
        result = source.copy()
        result[5, 7, 1] ^= 1
        change = metrics.outside_change(source, result, np.zeros(source.shape[:2], np.float32))
        self.assertEqual((change["outside_changed_px"], change["outside_max_diff"]), (1, 1))
        self.assertLess(change["outside_psnr"], float("inf"))

    def test_a_change_inside_the_mask_is_not_counted_outside(self):
        source = photo()
        result = source.copy()
        result[100:140, 100:140] = 0
        mask = np.zeros(source.shape[:2], np.float32)
        mask[100:140, 100:140] = 1
        self.assertEqual(metrics.outside_change(source, result, mask)["outside_changed_px"], 0)

    def test_ssim_is_one_for_identical_and_lower_for_noise(self):
        source = photo()
        noisy = np.clip(source.astype(int) + np.random.default_rng(6).normal(0, 25, source.shape), 0, 255).astype(np.uint8)
        self.assertEqual(metrics.ssim(source, source), 1.0)
        self.assertLess(metrics.ssim(source, noisy), 0.9)

    def test_sharpness_ratio_drops_when_the_inside_is_blurred(self):
        rng = np.random.default_rng(7)
        source = rng.integers(0, 255, (120, 120, 3)).astype(np.uint8)
        mask = np.zeros((120, 120), np.float32)
        mask[30:90, 30:90] = 1
        blurred = cv2.GaussianBlur(source, (0, 0), 2.0)
        self.assertLess(metrics.sharpness_ratio(source, blurred, mask), 0.2)
        self.assertAlmostEqual(metrics.sharpness_ratio(source, source, mask), 1.0)

    def test_landmark_shift_is_in_eye_distances(self):
        self.assertAlmostEqual(metrics.landmark_shift(MARKS, MARKS + [6.0, 0.0]), 6.0 / np.linalg.norm(MARKS[1] - MARKS[0]))

    def test_inside_difference(self):
        a = np.zeros((10, 10, 3), np.uint8)
        b = a.copy()
        b[2:4, 2:4] = 10
        mask = np.zeros((10, 10), np.float32)
        mask[2:4, 2:4] = 1
        self.assertAlmostEqual(metrics.inside_difference(a, b, mask), 10.0)
        self.assertEqual(metrics.inside_difference(a, b, np.zeros((10, 10), np.float32)), 0.0)


@unittest.skipIf(cv2 is None, "OpenCV not installed")
class Sheets(unittest.TestCase):
    def test_blind_order_is_a_stable_permutation_that_depends_on_the_sheet(self):
        labels = ["inswapper", "hyperswap", "ghost", "alphaface", "simswap", "other"]
        first = sheets.blind_order(labels, "T01__a")
        self.assertEqual(first, sheets.blind_order(list(reversed(labels)), "T01__a"))
        self.assertEqual(sorted(first), sorted(labels))
        orders = {tuple(sheets.blind_order(labels, f"T{n:02d}__a")) for n in range(20)}
        self.assertGreater(len(orders), 5)

    def test_contact_sheet_has_a_tile_per_candidate_and_a_separate_key(self):
        candidates = {"alpha": photo(1), "beta": photo(2), "gamma": photo(3)}
        sheet, key = sheets.contact_sheet("T01", photo(4), [photo(5)] * 4, candidates, (100, 60, 90, 110), "T01__a")
        self.assertEqual(sheet.shape[1], sheets.TILE * (2 + len(candidates)))
        self.assertEqual(sorted(key.values()), sorted(candidates))
        self.assertEqual(sorted(key), ["A", "B", "C"])

    def test_rating_form_lists_every_sheet(self):
        form = sheets.rating_form(["T01__a", "T02__b"])
        self.assertEqual(len(form.strip().splitlines()), 3)
        self.assertIn("looks_most_like_reference", form)


class Fixtures(unittest.TestCase):
    def test_zip_member_decodes_from_its_local_header(self):
        blob = io.BytesIO()
        payload = {"a/stored.bin": os.urandom(300), "a/deflated.txt": b"identity " * 400}
        with zipfile.ZipFile(blob, "w") as archive:
            archive.writestr("a/stored.bin", payload["a/stored.bin"], zipfile.ZIP_STORED)
            archive.writestr("a/deflated.txt", payload["a/deflated.txt"], zipfile.ZIP_DEFLATED)
        raw = blob.getvalue()
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            for info in archive.infolist():
                cut = raw[info.header_offset:info.header_offset + fixtures._span(info.file_size)]
                self.assertEqual(fixtures.member_bytes(cut, info.file_size), payload[info.filename])
        with self.assertRaises(ValueError):
            fixtures.member_bytes(b"\0" * 64, 10)

    def test_fetch_refuses_a_file_whose_hash_differs(self):
        with tempfile.TemporaryDirectory() as cache:
            old = os.environ.get("IDENTITY_BENCH_CACHE")
            os.environ["IDENTITY_BENCH_CACHE"] = cache
            try:
                os.makedirs(os.path.join(cache, "fixtures", "flux"))
                with open(os.path.join(cache, "fixtures", "flux", "x.jpg"), "wb") as f:
                    f.write(b"tampered")
                good = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"   # sha256("test")
                with self.assertRaises(RuntimeError):
                    fixtures.fetch("x", {"x": ("flux", "x.jpg", 0, 8, good)})
                with open(os.path.join(cache, "fixtures", "flux", "x.jpg"), "wb") as f:
                    f.write(b"test")
                self.assertTrue(fixtures.fetch("x", {"x": ("flux", "x.jpg", 0, 4, good)}).endswith("x.jpg"))
            finally:
                if old is None:
                    del os.environ["IDENTITY_BENCH_CACHE"]
                else:
                    os.environ["IDENTITY_BENCH_CACHE"] = old

    def test_manifest_meets_the_unit_minimums(self):
        identities = list(fixtures.IDENTITIES) + list(fixtures.RENDERED)
        self.assertGreaterEqual(len(identities), 8)
        for identity in identities:
            refs = fixtures.references(identity)
            self.assertGreaterEqual(len(refs), 4, identity)
            for file_id in refs:
                self.assertIn(file_id, fixtures.FILES)
        self.assertGreaterEqual(len(fixtures.TARGETS), 20)
        tags = {tag for t in fixtures.TARGETS for tag in t.tags}
        for hard in ("profile", "occlusion", "glasses", "beard", "low-light", "small-face", "two-people"):
            self.assertIn(hard, tags)
        self.assertGreaterEqual(len(fixtures.DISTRACTORS), 8)

    def test_manifest_is_consistent_and_pinned(self):
        for file_id, (source, member, offset, size, digest) in fixtures.FILES.items():
            self.assertIn(source, fixtures.SOURCES)
            self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", digest), file_id)
            self.assertGreater(size, 0)
            self.assertGreaterEqual(offset, 0)
        ids = [t.id for t in fixtures.TARGETS]
        self.assertEqual(len(ids), len(set(ids)))
        for target in fixtures.TARGETS + fixtures.DEV_TARGETS:
            for file_id in target.files:
                self.assertIn(file_id, fixtures.FILES)
            self.assertEqual(len(target.files), 2 if target.recipe == "pair" else 1)
        for identity in fixtures.DISTRACTORS:
            self.assertIn(f"flux/{identity}/doc", fixtures.FILES)

    def test_no_person_plays_two_roles(self):
        sources = set(fixtures.IDENTITIES)
        distractors = set(fixtures.DISTRACTORS)
        in_targets = {f.split("/")[1] for t in fixtures.TARGETS for f in t.files if f.startswith("flux/")}
        development = set(fixtures.DEV_IDENTITIES) | {t.identity for t in fixtures.DEV_TARGETS}
        for a, b in ((sources, distractors), (sources, in_targets), (distractors, in_targets),
                     (development, sources | distractors | in_targets)):
            self.assertFalse(a & b)

    def test_pairs_are_fixed_and_cover_every_target_and_identity(self):
        pairs = fixtures.pairs()
        self.assertEqual(pairs, fixtures.pairs())
        self.assertEqual(len(pairs), 2 * len(fixtures.TARGETS) + 3 * len(fixtures.RENDERED))
        self.assertEqual({p[0] for p in pairs}, {t.id for t in fixtures.TARGETS})
        self.assertEqual({p[1] for p in pairs}, set(fixtures.IDENTITIES) | set(fixtures.RENDERED))
        self.assertEqual(len(pairs), len(set(pairs)))

    @unittest.skipIf(cv2 is None, "OpenCV not installed")
    def test_derived_targets_are_deterministic(self):
        image = photo(8, 256, 256)
        self.assertTrue(np.array_equal(fixtures.low_light(image), fixtures.low_light(image)))
        self.assertLess(fixtures.low_light(image).mean(), image.mean() / 3)
        small = fixtures.small_face(image, 0.25)
        self.assertEqual(small.shape, image.shape)
        self.assertTrue(np.array_equal(small[96:160, 96:160], cv2.resize(image, (64, 64), interpolation=cv2.INTER_AREA)))
        self.assertEqual(fixtures.side_by_side(image, photo(9, 128, 128)).shape, (128, 256, 3))
        marks = MARKS * 0.8
        poled = fixtures.pole(image, marks)
        self.assertTrue((poled[:, int(marks[0, 0])] == (96, 104, 88)).all())
        self.assertTrue(np.array_equal(poled[:, 200:], image[:, 200:]))

    def test_pick_face_takes_the_left_of_a_pair_and_the_largest_otherwise(self):
        big = pipeline.Face(np.array([300.0, 10, 90, 90]), MARKS, 0.9)
        left = pipeline.Face(np.array([20.0, 10, 80, 80]), MARKS, 0.9)
        pair = fixtures.Target("X", ("a", "b"), "pair", (), "")
        single = fixtures.Target("Y", ("a",), "none", (), "")
        self.assertIs(fixtures.pick_face(pair, [big, left]), left)
        self.assertIs(fixtures.pick_face(single, [big, left]), big)
        self.assertIsNone(fixtures.pick_face(single, []))

    def test_nothing_binary_is_committed_next_to_the_code(self):
        banned = (".png", ".jpg", ".jpeg", ".webp", ".onnx", ".npz", ".npy", ".bin", ".zip", ".pkl")
        for root, _, names in os.walk(HERE):
            for name in names:
                self.assertFalse(name.lower().endswith(banned), os.path.join(root, name))

    def test_every_model_is_pinned_with_a_licence_note(self):
        for name, spec in pipeline.MODELS.items():
            self.assertTrue(spec["url"].startswith(("https://github.com/", "https://storage.googleapis.com/")), name)
            self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", spec["sha256"]), name)
            self.assertTrue(spec["licence"] and spec["role"], name)
        for name in cli.FINALISTS + cli.ALL_SWAPPERS:
            self.assertIn(name, pipeline.MODELS)
        for name, spec in pipeline.LOCAL_MODELS.items():     # exported here (#506), never downloaded
            self.assertTrue(spec["file"].endswith(".onnx") and spec["licence"] and spec["role"], name)
            self.assertNotIn(name, pipeline.MODELS)
        for swapper, encoder in pipeline.SOURCE_ENCODERS.items():
            self.assertIn(swapper, pipeline.SWAPPERS)
            self.assertIn(encoder, pipeline.LOCAL_MODELS)
            self.assertIn("research-only", pipeline.LOCAL_MODELS[encoder]["licence"])
        self.assertEqual(pipeline.source_encoder("ghost_1_256"), "arcface_w600k_r50")


class Report(unittest.TestCase):
    def rows(self):
        out = []
        for label, score in (("good", 0.7), ("good+gfpgan", 0.66), ("weak", 0.3), ("control-none+mask-box+no-colour", 0.05),
                             ("good+mask-hard-box+no-colour", 0.7)):
            for n in range(10):
                out.append(dict(
                    target=f"T{n:02d}", identity="i%d" % (n % 2), candidate=label, tags=["glasses" if n < 3 else "scene"],
                    rendered_identity=False, status="ok", seconds=1.0, notes=[], edit_share=0.2,
                    outside_px=1000, outside_changed_px=0, outside_max_diff=0, outside_psnr=float("inf"), outside_ssim=1.0,
                    seam_gradient=8.0 if "hard" in label else 0.5, seam_step=1.0, sharpness=0.5, landmark_shift=0.02,
                    judge_ref=score + 0.01 * n, judge_orig=0.1, judge_ceiling=0.75, judge_rank=1 if score > 0.4 else 2,
                    judge_confused_with="original person", judge_margin=0.3,
                    inloop_ref=score, inloop_orig=0.1, inloop_ceiling=0.7, inloop_rank=1,
                    sface_ref=score, sface_orig=0.2, sface_ceiling=0.7, sface_rank=1))
        out[0]["status"] = "face-lost-after-swap"
        for key in [k for k in out[0] if k.startswith(("judge_", "inloop_", "sface_"))]:
            del out[0][key]
        return out

    def test_aggregate_counts_a_lost_face_as_a_miss(self):
        good = cli.aggregate([r for r in self.rows() if r["candidate"] == "good"])
        self.assertEqual((good["n"], good["lost"]), (10, 1))
        self.assertAlmostEqual(good["rank1"], 0.9)
        self.assertAlmostEqual(good["verified_share"], 0.9)
        weak = cli.aggregate([r for r in self.rows() if r["candidate"] == "weak"])
        self.assertEqual(weak["rank1"], 0.0)

    def test_report_prints_every_candidate_a_verdict_and_the_failures(self):
        rows = self.rows()
        text = cli.report(dict(version="t", candidates=sorted({r["candidate"] for r in rows}, key=str),
                               pairs=[(r["target"], r["identity"]) for r in rows[:10]], rows=rows, stability=[], gallery_size=5))
        for needle in ("| good |", "| weak |", "FAIL", "face-lost-after-swap", "confused with original person",
                       "Hard-edge control seam gradient: 8.00", "| glasses |"):
            self.assertIn(needle, text)
        verdict_good = next(line for line in text.splitlines() if line.startswith("| good | pass"))
        self.assertTrue(verdict_good.rstrip().endswith("pass |"))

    def test_labels_round_trip(self):
        for config in [pipeline.Config(name) for name in cli.FINALISTS if name != "alphaface_256"] + [pipeline.Config("inswapper_128", boost=2, mask="mp", restore=True)]:
            self.assertEqual(cli.parse_label(config.label), config)
        with self.assertRaises(ValueError):
            cli.parse_label("inswapper_128+turbo")

    def test_iou(self):
        self.assertAlmostEqual(cli.iou((0, 0, 10, 10), (0, 0, 10, 10)), 1.0)
        self.assertAlmostEqual(cli.iou((0, 0, 10, 10), (5, 0, 10, 10)), 1 / 3)
        self.assertEqual(cli.iou((0, 0, 10, 10), (20, 20, 5, 5)), 0.0)


if __name__ == "__main__":
    unittest.main()
