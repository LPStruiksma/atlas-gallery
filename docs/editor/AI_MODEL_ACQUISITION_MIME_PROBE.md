# EDIT-11a2: hosted checkpoint response classification (failed acquisition)

Unit [#1079](https://github.com/LPStruiksma/atlas-gallery/issues/1079), parent [#876](https://github.com/LPStruiksma/atlas-gallery/issues/876). Predecessor [#1077](https://github.com/LPStruiksma/atlas-gallery/issues/1077) stopped at an unlogged unexpected MIME before reading the body.

## Original diagnostic evidence

- Exact tested head: `62a4ff4a28be9fe2a3e213a0f9e66d666237346c`.
- [Hosted Python run 45956912703, attempt 1](https://github.com/LPStruiksma/atlas-gallery/actions/runs/45956912703/job/140365553769), pull_request opened event; Python 3.12.16; checkout SHA explicitly verified. Classification logged 2026-11-27T07:13:36.1286506Z (observation time, not a completed retrieval timestamp).
- Original HTTPS host/path: `https://dl.fbaipublicfiles.com/segment_anything_2/072824/sam2_hiera_tiny.pt`. The pinned URL check passed; no redirects allowed.
- HTTP status: 200. Declared length: 155906050 bytes (within the 100–200 MiB guard; not an actual byte count).
- Normalized MIME: `application/vnd.snesdev-page-table`.
- Prefix classification: `ZIP_LOCAL_FILE_MAGIC`, 512 bytes inspected. No raw prefix printed.
- Terminal assertion: `MIME_PROBE_FAIL stage=prefix MIME not in explicit binary allowlist`.
- 16 tests ran, 15 passed and the diagnostic failed; catalogue validation/report steps were not reached. No local execution.

## Interpretation and disposition

The response reached the expected host and has a prefix compatible with a ZIP local header. This is not proof of a complete ZIP, checkpoint identity, safe deserialization or an upstream integrity match. The reported media type is not in the independently chosen binary allowlist. The diagnostic stopped before the full-stream stage, so there is no completed acquisition, actual full byte count or SHA-256 to record; no checkpoint file was written by that stage. The temporary directory and response were context-managed. No weight was loaded, extracted, committed or uploaded. Do not populate the provenance receipt.

Source context checked in the original diagnostic handoff: [pinned upstream README](https://github.com/facebookresearch/sam2/blob/2b90b9f5ceec907a1c18123530e92e794ad901a4/README.md) (original Tiny URL and checkpoint grant), [pinned training writer](https://github.com/facebookresearch/sam2/blob/2b90b9f5ceec907a1c18123530e92e794ad901a4/training/trainer.py), [PyTorch serialization documentation](https://docs.pytorch.org/docs/stable/notes/serialization.html) (default ZIP64), [SA-V terms context](https://ai.meta.com/blog/segment-anything-2/), [SA-1B data terms](https://ai.meta.com/datasets/segment-anything), and [training-source paper](https://arxiv.org/html/2408.00714#A4.SS2). Data terms and weight grants are separate; no training data acquired or production/redistribution clearance inferred.

One network attempt only. No rerun, no relaxed allowlist, no second network head. The temporary diagnostic is removed in the evidence-only cleanup. The failed unit remains open for Strategy disposition; #876 retains its all-candidate grant/byte evidence gate, and #877/#879 stay gated. This document records failure, not successful acquisition.
