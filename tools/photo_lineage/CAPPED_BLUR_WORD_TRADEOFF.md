# Capped index-only blur fingerprint ablation

Lane B / coordination issue #83. No production fingerprint change,
database/cache migration, Android UI, source rewrite, automatic photo-family
merge or deletion is authorized. This is **research only**.

Read seven existing SHA-256-pinned installed public camera PNGs (five
public-domain/CC0 scikit-image examples and two separately attributed
Middlebury Motorcycle views). Retain all normal ORB words and optionally
select **0/64/128/192/256** *additional* words from a Gaussian sigma2-blurred
copy. Selection is numeric, deterministic and balanced across the four
24-bit ORB descriptor bands using a fixed noncryptographic 32-bit hash;
it never depends on filesystem enumeration order or public labels.

```bash
python -m tools.photo_lineage.capped_blur_word_tradeoff
```

Each source thus contains <=384+limit ORB words, at most **640** even
under the most aggressive capped profile. Evaluate unchanged exact-word
two-hit retrieval offers against a Gaussian sigma8 query and an unrelated
horizontal motion31 query. These have **different kernel parameters from
the indexed sigma2 blur**, but share underlying source photos: they are
NOT a source-heldout model evaluation. Report term counts, source eligibility,
non-self public candidate offers, and the different-camera-viewpoint stereo
hard-negative overlap. No index/SQLite files, EXIF, pixels, descriptors,
paths or original media are saved or uploaded.

## Independent public-host exploratory results, 21 October 2026

All seven source SHA-256s verified; Linux Python3.13.5,
OpenCV4.13/NumPy2.3/scikit-image0.26. Unlike a real SQLite retrieval,
this is a deterministic in-memory word-overlap ranking only.

| Extra words/photo cap | Total words, 7 public originals | Gaussian8 source eligible | Motion31 source eligible | Stereo other-view words under motion31 |
|---|---:|---:|---:|---:|
| 0 (existing baseline) | 2,600 | 1/7 | 3/7 | 1 |
| 32 (initial exploratory control) | 2,824 | 1/7 | 5/7 | 2 |
| 64 | 3,048 | 2/7 | 5/7 | 2 |
| 128 | 3,442 | 2/7 | 5/7 | 2 |
| 192 | 3,826 | 3/7 | 6/7 | 2 |
| 256 | 4,205 | 3/7 | 7/7 | 2 |
| Unlimited sigma2 union | 4,683 | 4/7 | 7/7 | 3 |

The 32/unlimited rows are observational controls, not offered CLI modes.
At cap 256, the **strict per-photo ceiling of 640 words** would imply
at most 96 million postings if a hypothetical 150,000-image index
completely filled that quota—below the present immutable builder's
100 million posting **record count** limit. This is not a practical
deployment GO: 96 million packed uint64 records alone are ~732 MiB,
with conservative scratch space several times larger, separate source
manifests, retained generations, CPU, RAM and battery extra. Original
image families and word-frequency distributions remain unknown.

**Most importantly**, selecting more blurred words also creates
more offers for separate camera exposures sharing a scene. A stereo
right-view image produced two exact shared words against the augmented
left view in the 256-word trial, enough to enter a human review shortlist
even though those are not the same photographic source. No profile
proves safe source-equivalence; never relax the two-word gate, promote
pHash to provenance, automatically merge or delete.

CI tests are purely fictional and verify a fixed per-band cap,
deterministic order-independent selection, malformed term admission,
no private data or mutation, and conservative source-exposure flags.
Before any integration: source-revision SHA-pinned independent shutter
negatives from multiple scenes, full production SQLite rank/false-review
and posting-budget distribution with realistic 150k scale, plus
owner-approved S24+ resource evidence.
