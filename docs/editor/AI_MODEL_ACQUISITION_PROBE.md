# EDIT-11a1: hosted checkpoint acquisition probe — failed, not cleared

Unit [#1077](https://github.com/LPStruiksma/atlas-gallery/issues/1077), parent [#876](https://github.com/LPStruiksma/atlas-gallery/issues/876). One attempt only; no retry. Dev session pull-31dd3c25.

## Scope and acquisition permission

Original July 2024 SAM 2 Tiny, not SAM 2.1: [pinned upstream README](https://github.com/facebookresearch/sam2/blob/2b90b9f5ceec907a1c18123530e92e794ad901a4/README.md) lists the exact release path and expressly licenses the model checkpoints Apache-2.0. Original URL: https://dl.fbaipublicfiles.com/segment_anything_2/072824/sam2_hiera_tiny.pt. The source commit and release path identify the published variant, not an immutable checkpoint byte digest. [Meta's release announcement](https://ai.meta.com/blog/segment-anything-2-video/) separately licenses weights and data; [paper D.2](https://arxiv.org/html/2408.00714#A4.SS2) identifies SA-1B, SA-V manual and internally licensed video data for the released model. [SA-1B](https://ai.meta.com/datasets/segment-anything) is subject to its research data licence; [SA-V](https://ai.meta.com/datasets/segment-anything-video/) is CC BY 4.0. Internal data licence terms are not public. The acquisition-only decision relies on the explicit public checkpoint grant, not permission to acquire or use any training dataset. No production or redistribution clearance.

## Observed result

- Diagnostic head: `12de8bbf9a68aedd44bdd13bacf3ca88e0c0b8c5`; [diagnostic PR #1078](https://github.com/LPStruiksma/atlas-gallery/pull/1078).
- Existing standard ubuntu-latest [workflow run 45955898198](https://github.com/LPStruiksma/atlas-gallery/actions/runs/45955898198); non-skipped [Python job 140362269235](https://github.com/LPStruiksma/atlas-gallery/actions/runs/45955898198/job/140362269235). Job logged the exact diagnostic head at 2026-11-27T07:04:22.7751581Z.
- Step **Python scaffold unit tests**: at 2026-11-27T07:04:24.0158961Z, `AssertionError: ACQUISITION_PROBE_FAIL stage=headers unexpected binary content type`. 16 tests ran, one failed (the probe); validate and report steps skipped. The guard was reached after a 200 response without Content-Range and identity content encoding, with the original HTTPS host allowlist satisfied.
- The response MIME type was not included in the diagnostic log; cannot distinguish an unexpected type on a genuine binary from HTML/error content. No checkpoint body was streamed to a file; no full-byte receipt, final URL receipt, retrieval UTC, byte count or SHA-256. No claims about other hosts.
- Temporary probe used a 100–200 MiB size window, 180-second wall deadline, 20-second socket timeout, same original HTTPS host only, no query strings, no auth, no weights deserialization, and temporary-directory cleanup. It rejected the headers before streaming. This is a different execution path from the previous connector-only attempts, but does **not** establish original-host checkpoint acquisition.

## Disposition

Remove the one-shot test from the draft branch; do not retry or relax the guard under this unit. No workflow changes or model files. Keep #1077 open, #876 held and #877/#879 gated. Strategy must decide the next bounded acquisition route or a separately scoped header diagnostic. Do not mark this evidence as a completed acquisition or update manifest evidence fields. Local tests were not run by the Dev seat; rely on exact-head hosted CI for offline verification of any final evidence-only change.
