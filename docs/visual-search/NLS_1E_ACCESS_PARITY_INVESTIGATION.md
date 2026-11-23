# NLS-1E tokenizer access and JVM parity investigation

Tracking: [#1006](https://github.com/LPStruiksma/atlas-gallery/issues/1006); staging integration: [#984](https://github.com/LPStruiksma/atlas-gallery/pull/984).

## Required verification

- Exact expected tokenizer: `https://storage.googleapis.com/big_vision/gemma_tokenizer.model`; 4,241,111 bytes; SHA-256 `6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef` (expected values from issue #1006, not independently verified here).
- Reproduce and record HTTP status, response headers and command, without bypassing authorization. A 403 is a blocker, not permission to mirror or redistribute.
- Identify primary-source terms for access, local use and redistribution. Do not bundle model bytes.
- Verify a concrete offline JVM SentencePiece dependency (artifact, version, licence, memory bounds, Android compatibility and security); run deterministic JVM golden parity against #217 EN/NL cases, recording commands and results. Python-only parity is insufficient.
- No Gradle, Manifest, tokenizer runtime or UI changes in this evidence unit. If access or dependency is gated, record the exact blocker and owner action rather than claiming parity.

Status: investigation pending; no JVM parity or redistribution permission claimed.
