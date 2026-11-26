# EDIT-11a: checkpoint provenance (partial inventory)

Issue [#876](https://github.com/LPStruiksma/atlas-gallery/issues/876). **No candidate is cleared or measured.** The source commit pins are from the [prior evidence receipt](https://github.com/LPStruiksma/atlas-gallery/issues/876#issuecomment-7204156576); they are not checkpoint identities or byte digests. The licence notes are starting points from the scaffold catalogue, not independently verified legal conclusions. This turn re-read the pinned Depth Anything V2, SAM 2, CodeFormer and MobileSAM READMEs.

| Candidate | Current triage |
| --- | --- |
| realesrgan_x4plus | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| swinir_sr | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| gfpgan_14 | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| codeformer | Restricted project licence; checkpoint grant, training-data restrictions and bytes not established. |
| depth_anything_v2_small | Small grant identified; exact model-card/artifact revision, training-data restrictions and bytes missing. |
| midas_35 | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| shadowformer | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| dc_shadownet | Original project redirects; canonical repository and revision unresolved. Checkpoint grant and bytes also missing. |
| errnet | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| ibcln | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| mobile_sam | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |
| sam2_tiny | Original Tiny release URL and grant identified; immutable artifact identity, training-data restrictions and actual bytes not verified. |
| mediapipe_interactive | Exact checkpoint identity, grant, training-data restrictions and actual bytes not established. |

The machine-readable [manifest](../../tools/editor_models/provenance/manifest.json) separates repository revision, licence note, checkpoint grant, training-data restrictions, artifact identity and retrieved-byte evidence. All byte fields are null. No model binaries belong in this public repository.

## Collection and validation

1. Inspect the pinned upstream licence, exact checkpoint grant and training-data terms. A project licence alone does not clear a checkpoint. Mark an evidenced prohibition as blocked; leave unresolved candidates missing.
2. Identify the exact artifact/revision (including variant); resolve the original direct download URL. Never substitute a different checkpoint or a repository commit for artifact evidence.
3. Only for a legally usable checkpoint, download outside the checkout; record original and actual final HTTPS URLs (no credentials or signed/private query strings), UTC retrieval time, and compute actual bytes and SHA-256 with the existing `benchmark.py artifact --candidate ID --model /path/outside/repo/model` command. Do not copy its local path into the manifest.
4. Run from repository root:

```sh
python3 tools/editor_models/provenance/validate.py
python3 tools/editor_models/provenance/validate.py --require-complete
python3 -m unittest discover -s tools/editor_models/provenance -p 'test_*.py' -v
```

The first command validates structure; the second intentionally fails for missing candidates. Structural validation cannot prove legal clearance, source authenticity, immutable checkpoint identity or byte acquisition; independent Review must inspect evidence. The existing scaffold CI does not establish that these new commands ran. Do not release #877/#879 or update the model licence register on the strength of this partial inventory.
