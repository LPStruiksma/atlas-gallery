# Held-out photographic source-page alias admission

Lane B / issue #83. A valid provenance manifest needs two safeguards:
source image bytes must be SHA-256 pinned separately, and the **curator's
source URLs must not be ambiguously aliased across scenes or splits**.
Neither measure cryptographically proves a separate shutter activation.

Before this change, a percent-escaped page such as
`File%253Aphoto.jpg` was decoded once to `File%3Aphoto.jpg`
and counted as a different source from `File:photo.jpg`.
Some HTTP/proxy stacks may decode nested escapes again; the evaluator
must not grant a new independent source unit to such ambiguous paths.

`_canonical_source_page` now rejects any residual `%` after one
URL percent-decode, suspicious DNS leading/trailing/repeated dots,
control characters (including raw `\n` and `\t` before
`urlsplit` normalizes them away), backslash separators and duplicate
slashes. Unicode path labels are normalized to **NFC** so equivalent
composed/decomposed spellings share a canonical key. Single-escaped
Wikimedia `File%3A...` and `%20` spaces remain accepted. A
genuine Wikimedia title containing an encoded literal `%25` is
conservatively refused until a curator supplies independently verified
canonical revision/path information.

Pure synthetic tests check double-percent rejection, canonical Unicode
collision across development/holdout splits, malformed paths/hosts and
normal Wikimedia names. No files, image bytes, network access, EXIF,
private Gallery, People database or original photographs are read or
changed. Do not infer licensing, page permanence, independent camera
shutters, geographic location, family identity or photo ownership
from valid URLs. Future real-camera evaluation still needs verified
independently captured frames, exact original-byte hashes, rights
and scene-level holdout provenance.
