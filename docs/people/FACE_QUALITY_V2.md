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


## COVER-2 automatic person-cover policy (#166)

The existing version-2 quality score is used **within one person only**.
A face is eligible for the preferred tier when it has a non-null score,
`quality_version=2`, and `quality_below_size_floor=0`. Among eligible
observations, photos (`frame_ms IS NULL`) take priority over sampled video
frames; the highest score within that tier wins. If no eligible photo exists,
the highest-scoring eligible video frame wins. If no observation is eligible,
a current, authorized member with the best available known score is used;
legacy/unknown scores are still viable and are never interpreted as zero.
Equal scores use the smallest opaque face UID for deterministic first choice.

The automatic winner is a derived, app-private `people_auto_cover` hint.
A valid current incumbent remains selected until a new candidate in its
**same tier** exceeds its score by more than 0.05. A candidate in a higher
priority tier takes over immediately, and removed, rejected, invalidated or
split-away incumbents cannot be retained. This prevents flicker across
relaunches, while allowing a newly eligible photo to replace a video.
An automatic hint never replaces the separately persisted COVER-3 manual
override, which wins while it still resolves to a present named-person member.

The People home projection is updated transactionally with publication and
manual identity review. Hidden named people remain **excluded** from People
home, but their derived automatic winner is refreshed under the same rules
and is used in the Hidden list and the hidden person's header. COVER-3 manual
choices still win there. Quality-only backfill recomputes *only affected*
people (visible or hidden) in the same guarded SQLite transaction without
changing the identity review revision; the host invalidates its cached home
projection on a successful quality-only write. The live fallback path rechecks
model, source revision, processed state, membership and grant before using a cover.
Video cover crops continue to use the existing `frame_ms`-aware,
grant-scoped crop cache. No expression/aesthetics model or attractiveness
score is added.

Synthetic selector JVM tests and Android SQLite tests live in
`BestShotSelectorTest.kt` and `BestShotCoverAndroidTest.kt`, respectively.
The tests and emulator render must still run on the exact PR head; no
performance or visual measurement is claimed here.
