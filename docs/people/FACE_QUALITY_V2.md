# COVER-0 face-quality v2

This signal ranks **observations of the same person** for an opt-in best-shot
cover; it must never become an identity score or an attractiveness comparison.

The existing YuNet detection and SFace 112x112 aligned RGB crop supply all
signals; no extra model, photo decode, frame sampler or network request.
The score is clamped to [0,1] and stored per face alongside its existing
photo/video `frame_ms`, with `quality_version=2`.

| Feature | Weight | Calculation |
| --- | ---: | --- |
| YuNet confidence | 0.10 | clamped detector score |
| Face size | 0.20 | shorter face-box side / 112, capped at one |
| Sharpness | 0.25 | four-neighbour Laplacian variance / (variance + 900), preserving ordering without a hard cap |
| Exposure and contrast | 0.20 | mean-luminance balance × unclipped-pixel fraction × standard deviation / 48 |
| Frontalness and roll | 0.20 | 70% nose/mouth/eye symmetry plus 30% eye-line roll |
| Truncation | 0.05 | zero when the face touches the two-pixel decoded-frame border |

A face below the 112 px floor also has a separate `belowSizeFloor` flag.
**COVER-0b source-pixel floor:** the real MediaStore photo reader captures
ImageDecoder's header dimensions before downsampling, then transfers the
original/decoded long-edge ratio on each short-lived frame. The embedder
passes that `sourceScale` into `FaceQualityScorer`; video frames use the
authorized media row's source dimensions and the actual sampled bitmap.
The ratio uses the long edge, so EXIF rotation does not invert it. A
face 80 px wide in a 5× downsample is 400 source pixels and is above the
112 px floor. This is source-*size* provenance, not reconstructed detail:
sharpness, exposure and pose still use the aligned decoded crop.

Migration is additive and nullable, retaining SQLite schema version 3 for
rollback compatibility. Legacy faces remain readable with null quality. New
embedding completions write score/version/flag atomically with their boxes,
vectors and frame times; rescans replace them under the existing epoch and
revision fences. Existing processed faces with NULL quality are backfilled by a quality-only
step in the existing charging-constrained People worker, once its ordinary face
queue drains. It decodes at most one authorized source per step, runs existing
YuNet and SFace *alignment* but **does not run SFace embedding**; a face only
receives a score after its stored normalized box matches the detected box
(IoU ≥ 0.70) in the same frame. It checks charging, battery, thermal, pause,
stop, grant and source revision before work and before committing. An atomic
SQLite transaction checks the epoch, detector, revision, embedder model, and
`quality_score IS NULL`. Already-scored observations, vectors, cluster
membership and reviews are untouched. Unmatched/undecodable legacy rows
stay NULL and can be retried by the periodic charging re-arm. The cover picker
must treat null quality as unknown, not as zero.

COVER-0 and COVER-0b synthetic JVM tests exercise blur, exposure,
asymmetric landmarks, source-pixel floor (80 decoded/400 source), NULL-only
backfill, geometry matching and a charging-stop guard. See
[QUALITY_THROUGHPUT_392.md](QUALITY_THROUGHPUT_392.md) for the paired PERF-4
measurement recipe. An Android instrumented SQLite/throughput run is still
required to establish the measured ≤5% acceptance target; no numerical
result is claimed without a completed run.
