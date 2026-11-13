# pHash fallback hazard: fixed public-camera edit / collision ablation

Lane B / issue #83. **Research only, not a fallback implementation or threshold calibration.** The existing `matcher.cardinal_hash_distance` uses 64-bit DCT pHash and selects the closest of four cardinal rotations; this diagnostic **only compares** that advisory hash to the same previously SHA-256-pinned seven real sample images used in `PINNED_CAMERA_MATRIX.md`. No originals, source media, hashes or private paths are written/exported. Five CC0/public-domain example images and the separately attributed Middlebury Motorcycle stereo views are optional package assets; no network download or copyrighted binaries committed.

```bash
python -m tools.photo_lineage.hash_fallback_ablation
```

This invokes the existing `_read_pinned_photo` and existing five in-memory derived edits; prints distances for 35 correlated within-source edits, 10 cross-scene pairs, and the **single different-camera same-scene stereo pair**. For fixed illustrative distances 0,4,8,12,16,24 it reports the number of pairs at/below threshold, expressly marks these as **not classification gates** and never changes a matcher decision, queues a review, groups or deletes media.

## Independently measured host-only pinned-source results (20 October 2026)

Python3.13.5/OpenCV4.13/NumPy2.3/scikit-image0.26, source file SHA-256s confirmed, repository matcher Git blob `ee2e4b5d365ca6667a4f340ff45d37e45a148f70` verified. The new optional ablation CLI was run on the locally bundled sample bytes (never in synthetic CI). Source `clock_motion.png` returned **pHash distance 0** for resized, gamma and JPEG-Q40 edits, **20** for rotated+crop and **24** for center crop. The real **different-camera** Middlebury stereo pair returned distance **4** despite its separate physical viewpoint. Thus a pHash fallback at distance <=4 would recover some clock same-source edits but also put this different-camera same-scene pair inside the same apparent-distance bucket.

| Exploratory maximum pHash distance | Of 35 within-source edit pairs | Of 5 clock edits | Different-scene public pairs | Different-camera same-scene stereo pair |
|---|---:|---:|---:|---|
| 0 | 17 | 3 | 0/10 | Not included |
| 4 | 21 | 3 | 0/10 | **Included** |
| 8 | 21 | 3 | 0/10 | **Included** |
| 12 | 21 | 3 | 0/10 | **Included** |
| 16 | 21 | 3 | 0/10 | **Included** |
| 24 | 24 | 5 | 1/10 | **Included** |

This demonstrates a **concrete pHash evidence-correlation / scene-confusion failure**, not a representative false-positive rate, and is a reason to resist a naive pHash-to-`candidate` promotion. At threshold 24, even an unrelated cross-scene pair passes. Cropping arbitrary shapes remains hard despite cardinal rotation checks; the hash is not calibrated for 150k images and has synthetic patterned-scene collisions elsewhere in the project.

Only 7 original images / 6 scene labels are available; the 35 edits are not independent draws, and the Middlebury pair is not separate-time same-camera shutter evidence. No approved automatic review policy emerges from this. If low-texture recovery is pursued, it needs a separate bounded review-queue protocol, high-specificity distractor stress, independent-shutter real hard negatives, exposure-uncertainty safeguards and on-device cost measurements. Retain the strong geometric gate and manual verification until those checks exist.

**CI:** three synthetic-only tests mock public sample reads and the distance function; no optional real camera assets needed. All output avoids user photo paths/descriptors, and the module never writes indexes or originals.
