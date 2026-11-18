# ATLAS identity bench (IDS-2, issue #469)

A fixed, reproducible benchmark for face replacement, and the host proof of
concept behind it. It answers one question for Identity Studio (goal #467):
which local swap pipeline makes the result genuinely look like the reference
person, and what does each one cost? Results, thresholds and the engine choice
are in `docs/identity-studio/BENCH.md`.

Nothing here ships in the app or runs in CI. Faces are synthetic only
(`FIXTURES.md`). Weights and images are downloaded at run time into a cache
outside the repository and are never committed. Several weights are
research-only or non-commercial; they are used for this offline evaluation
only, and `docs/identity-studio/LICENCES.md` (IDS-1, #468) decides what may be
integrated.

## Run it

Python 3.11+ with `numpy`, `opencv-python-headless`, `onnxruntime` and `onnx`.
Network access to `github.com` release downloads, `zenodo.org` and
`facesyntheticspubwedata.z6.web.core.windows.net`. About 6 GB of weights and
120 MB of fixtures are cached under `~/.cache/atlas-identity-bench/`
(`IDENTITY_BENCH_CACHE` moves it).

```bash
python3 -m unittest tools/identity_bench/test_identity_bench.py   # 43 tests, synthetic tensors, no downloads
python3 -m tools.identity_bench self-test                        # the same tests
python3 -m tools.identity_bench fetch                            # download and hash-check everything
python3 -m tools.identity_bench one --target T04 --identity 0213ccb4776a --candidate inswapper_128
python3 -m tools.identity_bench                                  # the full benchmark (about an hour on 4 cores)
python3 -m tools.identity_bench cost                             # time and memory, one fresh process per candidate
python3 -m tools.identity_bench screen                           # all nine swap networks on the development split
```

`one` runs a whole pipeline on one fixture and prints its numbers; it is the
quick check for a reviewer. A full run writes, under `--out` (default
`~/.cache/atlas-identity-bench/runs/run/`):

- `results.json`: one row per swap with every metric, plus the thresholds used;
- `report.md`: the tables;
- `images/<target>__<identity>/<candidate>.png` with a `.provenance.json` next
  to each one (source files and hashes, reference files, engine, weights by
  hash, mask and restoration settings);
- `sheets/*.jpg`: blinded contact sheets, with `sheets/ratings.csv` to fill in;
- `sheet-key.json`: which letter is which pipeline. Keep it away from whoever
  rates the sheets.

## The pipeline

`pipeline.py`, one function: `swap_face`.

1. **Detect** with the shipped YuNet, letterboxed to 640 px like the app.
2. **Align** the five landmarks to the network's template (similarity
   transform, no reflection).
3. **Swap** the aligned crop. The identity is the mean raw embedding of the
   reference photos from the encoder the networks were trained against
   (`arcface_w600k_r50`), converted per network.
4. **Mask**: a box that is exactly 0 at the crop border, times the face region
   from a face parser run on the swapped crop (skin, brows, eyes, nose, mouth;
   glasses, hair, ears and neck stay the target's). Optional occlusion mask.
5. **Colour match**: mean and spread per Lab channel inside the mask.
6. **Paste back** at the source resolution. Where the mask is 0 the output
   pixel is the source pixel, so the untouched area is bit-identical.
7. **Restore** (optional): GFPGAN 1.4 on the swapped face only, inside the same
   mask, blended at 0.8.

A candidate is a `Config`: swap network, mask mode, colour match on or off,
restoration on or off. Two controls calibrate the image metrics: `control-none`
pastes the unchanged crop back, and the hard-edge control pastes a swap with an
unfeathered box and no colour match.

## The measurements

`metrics.py` defines each one once.

| Metric | Meaning |
|---|---|
| judge similarity | Cosine between the swapped face and the centroid of the reference set, by a research-only judge (`glintr100`, never shipped). The judge is a different network, trained on different data, from the encoder the swappers were trained against |
| in-loop encoder | The same cosine by `arcface_w600k_r50`. Reported, never used for a verdict: the swappers optimise it directly |
| SFace | The same cosine by the shipped SFace int8 model, raw vectors |
| retention | Judge similarity as a fraction of the identity's leave-one-out ceiling (how similar a genuine photo is to the rest of the set) |
| over 0.40 | Share of swaps the judge would call the same person. 0.40 is above 99.9 % of stranger pairs in the fixture pool |
| rank-1 | The judge must pick the reference identity out of a gallery with every benchmark identity, 24 look-alikes and the target's own original face |
| to original | Similarity that is left to the person who was in the photo (lower is better) |
| changed px outside, PSNR, SSIM, max diff | Pixels where the mask is 0, against the source |
| seam gradient, seam step | Edge energy added in the feather band, and how much the colour step across the boundary changed |
| sharpness | Laplacian energy inside the edit, result over source. Below 1 means lost detail |
| landmark shift | How far the five landmarks moved, in eye distances (pose and expression drift) |
| stability | Byte-identical reruns, then spread of the judge score and of the pixels under a 1 % landmark jitter |
| cost | Load time, per-stage wall and CPU time, peak resident memory, each candidate in its own process |

A lost face (the detector no longer finds it after the swap) counts as a miss
in rank-1 and "over 0.40".

## Files

| File | Holds |
|---|---|
| `pipeline.py` | pinned model table, alignment, masks, colour match, paste back, the swap networks, restoration, stage meter |
| `fixtures.py` | fixture sources, ranged zip reads, derived hard cases, the selection rules and the pinned manifest |
| `metrics.py` | every measurement and the acceptance thresholds |
| `sheets.py` | blinded contact sheets and the rating form |
| `__main__.py` | the runs, the report and the command line |
| `test_identity_bench.py` | unit tests |
| `FIXTURES.md` | fixture licences and how the set was chosen |

## Limits

Host CPU times are not phone times. The judge is one model family (ArcFace);
people must still look at the sheets. Synthetic faces are not real photos. See
"What this does not show" in `docs/identity-studio/BENCH.md`.
