# COVER-0 face-quality v1

This signal ranks **observations of the same person** for an opt-in best-shot
cover; it must never become an identity score or an attractiveness comparison.

The existing YuNet detection and SFace 112x112 aligned RGB crop supply all
signals; no extra model, photo decode, frame sampler or network request.
The score is clamped to [0,1] and stored per face alongside its existing
photo/video `frame_ms`, with `quality_version=1`.

| Feature | Weight | Calculation |
| --- | ---: | --- |
| YuNet confidence | 0.10 | clamped detector score |
| Face size | 0.20 | shorter face-box side / 112, capped at one |
| Sharpness | 0.25 | log-scaled variance of a four-neighbour Laplacian, normalized to 900 |
| Exposure and contrast | 0.20 | mean-luminance balance × unclipped-pixel fraction × standard deviation / 48 |
| Frontalness and roll | 0.20 | 70% nose/mouth/eye symmetry plus 30% eye-line roll |
| Truncation | 0.05 | zero when the face touches the two-pixel decoded-frame border |

A face below the 112 px floor also has a separate `belowSizeFloor` flag.
**Important limitation:** the floor is measured in the bounded,
EXIF-oriented decoded scan frame (or an explicitly supplied source scale),
not original camera resolution. The current media reader does not pass the
original dimensions. Therefore this signal must not be advertised as a
source-pixel floor until that metadata is connected.

Migration is additive and nullable, retaining SQLite schema version 3 for
rollback compatibility. Legacy faces remain readable with null quality. New
embedding completions write score/version/flag atomically with their boxes,
vectors and frame times; rescans replace them under the existing epoch and
revision fences. Existing processed faces are **not yet backfilled**: a
charging-only, permission-fenced backfill scheduling change is still needed
before the issue's full acceptance criteria are met. The cover picker must
treat null quality as unknown, not as zero.

COVER-0 synthetic JVM tests exercise blur, dark/clipped exposure, asymmetric
landmarks, size floor and truncation. The Android SQLite upgrade and
before/after scanner-throughput (within 5%) require separate instrumented
proof. A GPU/emulator or local Gradle run was not performed by this seat.
