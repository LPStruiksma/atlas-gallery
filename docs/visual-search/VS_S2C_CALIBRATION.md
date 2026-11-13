# VS-S2c: calibrated "find similar" bands

Unit VS-S2c of [#468](https://github.com/LPStruiksma/atlas-native/issues/468), from the [#481 review receipt](https://github.com/LPStruiksma/atlas-native/pull/481#issuecomment-6702194763) (finding 2: "Bands 0.75/0.50/0.97 are uncalibrated"). Run 2026-11-07 in a Claude cloud container. Re-run: `tools/visual_search_bench/calibrate_bands.py`.

## Result

| Constant (`VisualQueryBands`) | Before (VS-S2a) | Calibrated | What it gives on the benchmark |
|---|---|---|---|
| `FLOOR` | 0.50 | **0.74** | same-class precision 0.53 at and above it (target ≥ 0.50) |
| `STRONG` | 0.75 | **0.84** | same-class precision 0.81 at and above it (target ≥ 0.80) |
| `NEAR_DUPLICATE` | 0.97 | **0.95** | 94.6% of synthetic edits counted as copies; 8 of 975,106 distinct-photo pairs hidden |

`QUANTIZATION_TOLERANCE` (0.01) is unchanged; every band is more than five times wider.

## Data and model

- Images: the VS-S0 set, `tools/visual_search_bench/data/open_images_actions_v1.csv` (1,397 Open Images V4 train images with human-verified action/pose labels, 36 labels). `fetch_images.py` re-checked every licence against Open Images' own metadata (1,397 of 1,397 CC BY 2.0) and downscaled to 640 px in a cache outside the repository. Only IDs and labels are committed; no owner media.
- Model: the exact APK asset, `app/src/main/assets/models/siglip2/siglip2_b16_224_int8.onnx.part0` + `part1`, joined and checked against the shipped whole-file SHA-256 `74da3ac2b46e4292c4639affc3cca623c2d5551d012ead113b1486c22e0f5336` (`SiglipModel.MODEL_VERSION` `siglip2_b16_224.int8:74da3ac2b46e`).
- Preprocessing: the `SiglipPreprocess.kt` contract (RGB, 224×224 bilinear squash, `(x/255 − 0.5)/0.5`, NCHW).
- Storage: every vector goes through a NumPy port of `VisualVectorCodec` (per-vector symmetric int8, decoded and re-normalised), as the engine reads them from `VisualIndexStore`.
- Runtime here: Python 3, ONNX Runtime 1.29.0 and Pillow 12.3.0 (the container's; `requirements.txt` pins 1.31.1 and 13.0.0 for the full VS-S0 pipeline). CPU only, about 6 minutes for 5 × 1,397 embeddings on 4 cores.

## Method

Every unordered pair of distinct images (975,106 pairs) is scored by cosine. A pair is **same class** when the two images share at least one label, the relevance rule of `bench.py` (VS-S0). Base rate: 3.5% of pairs.

- **`STRONG` / `FLOOR`**: on a 0.01 grid from 0.30 to 0.99, the cumulative same-class precision P(same class | cosine ≥ t). Each constant is the lowest t from which on the precision stays at or above its target (0.80 for `STRONG`, 0.50 for `FLOOR`) at every higher grid step with at least 30 pairs. The targets are the ones in the work order.
- **`NEAR_DUPLICATE`**: four synthetic edits of every image (JPEG re-encode at quality 60, 90% centre crop, +15% exposure, half-scale and back up) give 5,588 copy pairs. The cutoff is the lowest grid t at which at most 1 in 100,000 distinct-photo pairs (about 10) would be hidden as copies; the recall of the edits at that cutoff is reported. The set has no true duplicates, so the few distinct pairs above it are burst-like neighbours, which the app also means to count as near-duplicates.

## Numbers

Cumulative same-class precision (pairs with cosine ≥ t):

| t | pairs | precision | recall |
|---|---|---|---|
| 0.50 (old `FLOOR`) | 841,342 | 0.039 | 0.961 |
| 0.60 | 343,345 | 0.079 | 0.788 |
| 0.70 | 49,075 | 0.329 | 0.470 |
| 0.73 | 26,719 | 0.478 | 0.372 |
| **0.74** | 22,073 | **0.530** | 0.341 |
| 0.75 (old `STRONG`) | 18,417 | 0.578 | 0.310 |
| 0.80 | 7,821 | 0.751 | 0.171 |
| 0.83 | 4,391 | 0.799 | 0.102 |
| **0.84** | 3,455 | **0.811** | 0.082 |
| 0.88 | 941 | 0.852 | 0.023 |
| 0.92 | 110 | 0.946 | 0.003 |
| 0.95 | 8 | 1.000 | 0.000 |

At the old `FLOOR` 0.50, 86% of all pairs pass and precision (0.039) is barely above the 0.035 base rate, so the old bands would have shown mostly unrelated photos as "related"; the old `STRONG` 0.75 is only 0.58 precise.

Per band with the chosen constants:

| Band | Cosine | Pairs | Precision in band |
|---|---|---|---|
| strong | [0.84, 0.95) | 3,447 | 0.81 |
| related | [0.74, 0.84) | 18,618 | 0.48 |
| dropped | < 0.74 | 953,033 | 0.02 |

As a user sees it: 1,259 of the 1,397 queries return at least one hit; the mean share of same-class photos among a query's shown hits is 0.61 (median 0.65).

Near-duplicate trade-off (distinct pairs hidden vs share of the 5,588 synthetic edits caught):

| t | distinct pairs ≥ t | edit recall |
|---|---|---|
| 0.92 | 110 | 0.995 |
| 0.93 | 52 | 0.989 |
| 0.94 | 22 | 0.975 |
| **0.95** | **8** | **0.946** |
| 0.96 | 4 | 0.876 |
| 0.97 (old) | 0 | 0.723 |
| 0.98 | 0 | 0.418 |

Edit recall at 0.95 by kind: re-encode 1,377 of 1,397, exposure 1,388, half-scale 1,333, 90% crop 1,190 (a crop changes content, so crops are the edits that most often stay visible as "very similar"). Highest distinct-pair cosine: 0.967; 99.9th percentile 0.879.

## Limits

- The labels are image-level actions ("Sitting", "Juggling"), so "same class" undercounts real visual similarity (two beach photos with different actions count as wrong). Precision here is a floor for what a user would call similar.
- The set is people-centred action photos; landscapes, documents and pets are absent. The on-device private benchmark (unit VS-B) should confirm the bands on a real library.
- Bursts are not in the set; `NEAR_DUPLICATE` is chosen from synthetic edits and the distinct-pair tail, not from real burst pairs.

## Re-run

From the repository root, with the images fetched as in `run.sh` (`fetch_images.py` into a cache) and Python with `numpy`, `onnxruntime` and `pillow`:

```
python3 -I tools/visual_search_bench/calibrate_bands.py \
    --images ~/.cache/atlas-vs/images --set ~/.cache/atlas-vs/data/licensed.csv \
    --work ~/.cache/atlas-vs/calibrate --out ~/.cache/atlas-vs/vs_s2c_calibration.json
```

It prints and writes only aggregates (the chosen constants, band precision, the curves). Options: `--strong-target`, `--related-target`, `--min-support`, `--distinct-rate`. The joined model, images and embeddings stay under `--work` and the image cache.
