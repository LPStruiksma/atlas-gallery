# Visible Bayer metadata bridge — no RAW decoder execution

Lane B / issue #83. This independent follow-up to #289 converts an **already open** rawpy-like decoder's sensor metadata to the exact `pattern`, `black_levels`, `white_level` arguments required by `bayer_to_grayscale`. It imports neither rawpy nor LibRaw, opens no DNG or photo files, reads no pixels or EXIF/GPS, calls no network, and does not touch phone/People data, original photos, caches or the native UI. **The future external decoder remains an unresolved security/resource/license boundary.**

## Correctness pitfall: visible sensor phase is not full-RAW origin

[rawpy's documented API](https://letmaik.github.io/rawpy/api/rawpy.RawPy.html) defines `raw_color(row,column)` relative to the **full RAW image**, `raw_image_visible` excluding hidden margins, and `black_level_per_channel` keyed by **decoder channel index**, not row-major CFA position. Cropping an odd top or left margin changes the visible 2×2 CFA ordering; using `raw_pattern` naively as the visible origin or passing black levels unchanged can produce incorrect per-color normalization.

`visible_bayer_calibration(raw)` requires 2×2 uint8 decoder `raw_pattern`, 4-byte `color_desc`, four black-level entries, scalar 1..65535 `white_level`, nonnegative bounded top/left margins, and consistent `raw_color` results at exactly four **visible-origin** positions `(top_margin+row, left_margin+col)`. It requires one R, two G and one B, no X-Trans, Foveon, unusual CFA, unsupported color labels or untrusted phase. When per-channel camera saturation levels are present, they must all equal the scalar white level; other metadata is rejected rather than silently ignored. It returns **only three small primitive values**, never filenames, pixel arrays, raw metadata blobs or identities.

Example *conceptual* safe use after a **separate** audited, rights-vetted, resource-restricted decode has already succeeded:

```python
calibration = visible_bayer_calibration(already_open_rawpy)
gray = bayer_to_grayscale(already_open_rawpy.raw_image_visible, **calibration)
```

This is **not** a recommended rawpy loader snippet and does not provide a `rawpy.imread` wrapper. Real decoder memory use/RAW decompression may occur before this function can inspect an ndarray. Sensor orientation, black/white accuracy, demosaicing, ICC/camera color profiles, multi-exposure RAW, parity with photographic JPEGs, calibrated source similarity, independently verified shutters and S24+ performance are untested. Numeric conversion remains the intentionally limited #289 luma proxy, not colorimetrically validated output.

## Synthetic test and next gate

```bash
python -m unittest tools.photo_lineage.tests.test_rawpy_metadata_bridge -v
```

Four purely mocked decoder tests: all four visible-margin parities, map color-indexed black levels into correctly ordered sensor positions, ensure the pixel buffer is **never accessed**, reject unsupported mosaic/color indexes, mismatched CFA phase, nonuniform camera whites and invalid/incomplete metadata. No rawpy installed in CI, no DNG images, no photo/People DB, no remote access.

Next research gate: independently rights-verified small burst source, pinned hashes and curator-proven frame/scene provenance, an isolated LibRaw/rawpy **actual decoder** with hard memory/CPU cap applied *before untrusted decompression*, and a separate source/scene-grouped false-review vs edit-retention evaluation. Neither metadata nor matching can certify source exposure identity; no automatic family merge or deletion.
