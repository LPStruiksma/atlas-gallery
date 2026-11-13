# Research-only blur-augmented ORB index word study

Lane B / issue #83. This does **not** alter production ORB extraction,
ranking, fingerprint version, SQLite snapshots, native Android files,
original photographs, or photo-family decisions.

The already pinned seven public camera PNGs (five public-domain/CC0
scikit-image examples and the independently attributed Middlebury
Motorcycle left/right viewpoints) are loaded read-only and verified
by exact source SHA-256 before feature extraction. No images, caches or
derivatives are written. The in-memory experimental profiles are
original ORB words only, original+Gaussian sigma 2, and
original+Gaussian sigma 2+sigma 4. The two query conditions are
Gaussian sigma 8 and horizontal motion blur 31 pixels: **these exact
parameters are not indexed**, although the original photos are
reused and the profile choices are exploratory, not independently
source-heldout. A score is exact ORB word intersection and a candidate
requires >=2 words. Output contains aggregate counts and public rights,
never pixel/path/hash identifiers.

```bash
python -m tools.photo_lineage.blur_augmented_word_tradeoff
```

## Verified local seven-source observations, 21 October 2026

Linux Python3.13.5, OpenCV4.13, NumPy2.3.5, scikit-image0.26.
All seven public source PNG hashes matched the repository allowlists.
The prototype uses the same ORB 96-keypoint, four 24-bit-band algorithm,
but its **in-memory ranks are not the production SQLite shortlist**.

| Profile | Total words / 7 originals | Gaussian sigma8: source eligible | Motion31: source eligible | Stereo other-view overlap, Gaussian8 / motion31 |
|---|---:|---:|---:|---:|
| Baseline | 2,600 | 1/7 | 3/7 | 0 / 1 |
| +Gaussian sigma2 | 4,683 | 4/7 | 7/7 | 1 / **3** |
| +Gaussian sigma2 and 4 | 6,656 | 6/7 | 7/7 | **7 / 10** |

Adding just sigma2 raises stored word count by ~1.80× and sigma2+4
by ~2.56× on these samples. Critically, the separate **camera viewpoint**
of the same static Motorcycle scene begins satisfying the two-word
retrieval threshold under augmentation. A rank/offer is **not** evidence
that two files derive from the same shutter exposure; review only.

This is not a randomized or independent train/test split, not
representative recall or false-source risk, and is far too small to
select a production augmentation profile. Extra postings could grow
index disk, scratch, scan CPU and hot-word query frequency dramatically
on 150k personal photos. No actual 150k index, source-scene heldout,
independent same-camera-shutter negatives, LibRaw fidelity or S24+
resource readings were performed. Never automatically group/delete
based on this experiment. A future trial needs rights-cleared
independently SHA-pinned same-camera multiple-shutter scenes,
source-heldout evaluation and full SQLite posting-budget tests.

Synthetic CI tests exercise precise profile admission, original
immutability, count/type report boundaries, 1152-term union quota,
stereo alias refusal, and conservative identity flags; no public
photo bytes are accessed in CI.
