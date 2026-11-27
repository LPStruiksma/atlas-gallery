# Full-head probe (IDS-3, #470)

Measured probes behind [`docs/identity-studio/FULL_HEAD.md`](../../../docs/identity-studio/FULL_HEAD.md). Full head means the hair, hairline, head shape and ears change with the face. The probe runs head-swap engines on synthetic people, times them, records peak memory, scores the output and extrapolates to the Galaxy S24+.

Nothing here ships in the app. No weights, no generated fixture images and no personal images are committed: weights are fetched from their original hosts with pinned SHA-256, and the test people are generated at run time from fixed prompts and seeds. The one committed image, a contact sheet of this probe's synthetic people, lives with the document at [`docs/identity-studio/full-head-sheet.jpg`](../../../docs/identity-studio/full-head-sheet.jpg), so no binary sits next to the code.

## Offline tests (no dependencies)

    python3 -m unittest tools/identity_bench/head/test_probe.py

Standard library only. The tests cover the classification rule (a face-only result can never be counted as full head), the pre-registered identity thresholds, the S24+ extrapolation arithmetic, the cost formula, the manifest (pinned downloads, prompts that name no real person) and the committed `results/results.json`.

## Full-head quality evaluator (IDS-12A, #772)

`quality.py` scores a locked cohort against the rubric pre-registered in [`docs/identity-studio/HEAD_ACCEPTANCE.md`](../../../docs/identity-studio/HEAD_ACCEPTANCE.md): at least 20 predeclared pairs with licence evidence and hard-case coverage, then five gates (whole head and target pose at 90% of all pairs, bit-identical pixels outside the predeclared fence, median scale error at most 0.10, two blinded judges preferring the candidate over face-only at 70% of `2 x N` for hair/hairline and for neck seam, and profile-capable identity evidence). Verdicts are `PASS`, `FAIL` or `INSUFFICIENT_EVIDENCE`; missing, malformed, non-finite, contradictory or unlicensed evidence is never a pass, and failed or absent rows stay in the denominator.

    python3 -m unittest tools.identity_bench.head.test_quality
    python3 tools/identity_bench/head/quality.py COHORT.json ROWS.json --json report.json   # exit 0 only on PASS

Input is untrusted JSON: `normalize_input()` is the single shape boundary (cohort must be an object with a `pairs` list, rows a list of objects, plain JSON only, bounded depth), `_num()` treats huge or non-finite numbers as missing, and `evaluate()` and the CLI always emit a report, `INSUFFICIENT_EVIDENCE` with a cause for malformed input, never a traceback. Standard library only. It runs no model and downloads nothing. The tests use constructed records, so they prove the evaluator, not a head-swap backend. The four-pair IDS-3 pilot in `results/results.json` does not satisfy this 20-pair gate; no backend has been evaluated against it yet (**NOT MEASURED**). The class names are the `probe.classify()` ones (`full_head`, `face_only`, `target_head_remains`, `no_swap`, `failed`).

Row fields are `{"status": "measured", "value": ..., "method": ...}` or `{"status": "not_measured", "reason": ...}`. Every row must also carry `claimed_success` (explicit boolean: was it surfaced as a whole-head success), `licence_evidence` (matching the frozen cohort licence) and `output_mask_hash` (SHA-256 plus method); absence fails closed. `test/additive-regressions/identity-head-quality-772.mjs` runs both suites in the existing hosted static job. `check_outside_fence()` builds the `outside_fence` value from raw pixels and a fence mask. Gate 5 sets no numeric identity threshold (none was pre-registered): it requires two independent profile-capable sources per pair that agree, and reports how many said preserved.

## First real 20-pair cohort run (IDS-12F, #1091)

`run_cohort.py` runs the first real full-head measurement under the frozen IDS-12A rubric: 20 distinct synthetic target/reference pairs, the `klein-lora` candidate against the paired face-only baseline, on the same free 4-core CPU cloud machine class IDS-3 used. No GPU, no Actions run of the model, no spend. It reuses `probe.py`'s engines and judges and `quality.py`'s row format.

    P="python3 tools/identity_bench/head/run_cohort.py"; M=/tmp/headprobe/models; W=/tmp/ids12f   # W outside git
    $P fixtures --models $M --work $W                 # 13 synthetic people, about 11 min each
    $P freeze   --models $M --work $W                 # writes cohort.json: commit it before any pair runs
    $P run      --models $M --work $W --stage baseline    # face-only baseline, seconds per pair
    $P run      --models $M --work $W --stage candidate   # about 15 min per pair; resumable
    $P export   --models $M --work $W                 # rows, run log, blinded sheets, evaluator report

The baseline needs the identity bench's weights too (`ghost_1_256`, `crossface_ghost`, `arcface_w600k_r50`, `selfie_multiclass_256`, `glintr100`; fetched on first use into `~/.cache/atlas-identity-bench/`, hash-checked) and `ai-edge-litert` for the MediaPipe mask.

- **Cohort (`cohort.json`)**: frozen before any result. Stable pair IDs `f01` to `f20`, the people's prompts and seeds (the six IDS-3 people keep their recipe; seven are new), licence, pose tags (measured from each target's yaw proxy at freeze time; the prompts intended 3 frontal, 3 three-quarter and 2 profile targets), reference hair shapes (long curly, bald, afro, short straight, long straight), hairline and hard-boundary flags, per-pair seed, the locked engine with model and adapter SHA-256, each fixture's SHA-256 from this run, and the edit-fence hash. `pairs_digest` makes any later edit detectable. Generated pixels are not bit-reproducible across machines, so the fixture and fence hashes bind the cohort to this run's images, which stay outside the repository.
- **Fence**: computed from the target alone (`FENCE_RECIPE` in the code: head-parse mask dilated by a quarter face width plus an ellipse 1.1 x 1.3 face boxes around the head, inside the engine's square crop). The candidate is pasted back through its edit mask intersected with the fence, so every pixel outside the fence is the target's byte for byte; the same fence is enforced on the baseline.
- **Resume**: each pair and stage writes one checkpoint file atomically when it finishes; a restart reruns only the pair in flight. A failed pair keeps its checkpoint with the error and stays in `N`.
- **Rows (`results/ids12f/rows.json`)**: the `quality.py` format. Measured automatically: class, target pose, head span (eye midpoint to mouth midpoint) and its relative error, outside-fence equality, output-mask hash, identity by two methods (SFace as the frontal method, glintr100 as the profile-capable one), and the SHA-256 of the model and adapter files actually loaded. Judge votes are `not_measured`: the two-judge step and the final verdict are the follow-up unit.
- **Sheets (`results/ids12f/sheets/`)**: one JPEG per pair, `target | reference | A | B` cropped to the head region, with the candidate's side from a frozen random order (`blind_seed`). The key is `results/ids12f/sheet-key.json`; keep it from the judges. `votes-template.csv` is the empty vote form. The JPEGs carry no metadata (the test checks).
- **Also committed**: `run-log.json` (host, runtime versions, wall time per pair) and `evaluator-report.json` (`quality.evaluate` over the rows; not a PASS while votes are missing).

    python3 -m unittest tools/identity_bench/head/test_run_cohort.py -v   # standard library only

## Re-running a probe

Needs about 12 GB of free RAM, 10 GB of disk and network access to `huggingface.co`, `us.aws.cdn.hf.co` and `media.githubusercontent.com`. No GPU, no account, no key.

    python3 -m venv /tmp/headprobe && . /tmp/headprobe/bin/activate
    pip install numpy pillow opencv-python-headless onnxruntime stable-diffusion-cpp-python
    P="python3 tools/identity_bench/head/probe.py"; M=/tmp/headprobe/models; W=/tmp/headprobe/work
    $P fetch --models $M                          # pinned downloads, about 9.5 GB
    $P fixtures --models $M --out $W              # six synthetic people, 768 px
    $P swap --models $M --out $W --engine composite
    $P swap --models $M --out $W --engine klein-lora --pairs p4   # one pair
    $P anchor --models $M --out $W                # host step time for the phone estimate
    $P report --out $W
    $P sheet --out $W --sheet /tmp/headprobe/sheet.jpg

The cheapest check for a reviewer is the `composite` engine: it needs only the three judge models (`fetch --only judge/yunet.onnx judge/sface.onnx judge/faceparse.onnx`) plus the fixtures, and finishes in seconds. One `klein-lora` pair takes about 15 minutes on four CPU cores, after about 11 minutes per fixture. Generated pixels are not bit-reproducible across machines or thread counts, so compare classes and scores, not hashes.

Keep `--out` outside the repository.

## Engines

| Engine | What it is | Why it is here |
|---|---|---|
| `klein-lora` | FLUX.2 klein 4B (4-bit GGUF, 4 steps) through stable-diffusion.cpp with the BFS head-swap LoRA. Edits a square crop around the target head, then pastes back through a head mask | The strongest open full-head route that runs at all without a GPU; candidate for the optional backend |
| `klein-prompt` | The same model and prompt without the LoRA | Control: how much the adapter matters |
| `composite` | Cut the reference head out, similarity-align it on the target face, feather | The floor: what no generative model at all gives on the phone |

GHOST 2.0 and UniBioTransfer are compared in the document from their papers and repositories; neither was run here (see the document, "What was not measured").

## Judges (research only, never shipped)

- **Identity:** YuNet + SFace fp32 from opencv_zoo (the models ATLAS already ships, Apache-2.0). Cosine similarity of the result to the reference, to the target, and reference to target (the impostor floor of that pair).
- **Hair and head masks:** `jonathandinu/face-parsing` (SegFormer; licence "non-commercial research and educational purposes", CelebAMask-HQ labels). Used for hair amount (hair pixels over face-box area), hair colour distance in Lab, face-to-neck colour step and the edit mask.
- **Outside the edit region:** PSNR and changed-pixel fraction between target and result outside the dilated union of both head masks.

When the IDS-2 harness (#469) lands, its fixtures and judges replace these; the record format (`atlas.identity_bench.head/1`) carries plain scores so nothing else has to change.

## How a result is named

`probe.classify()`:

- `full_head`: identity moved to the reference (cosine at least 0.363 and at least 0.15 above the target), the hair became the reference's, **and** at most 25 % of the target's own head pixels are left unchanged. The hair test compares hair colour (Lab distance) when target, reference and result all have hair, and hair amount (hair pixels over face-box area) when one of them is bald.
- `face_only`: identity moved, hair stayed the target's. Reported under this name, never added to full head.
- `target_head_remains`: a new head arrived but the old one is still largely visible.
- `no_swap`: identity did not clearly move.
- `failed`: no face in the output.

`convincing` additionally needs identity at least 0.60, at least 0.20 above the pair's impostor floor, and the head still turned the way the target's was (landmark yaw proxy within 0.30).

The identity numbers were fixed before the first generative run, after the judge self-test showed two different synthetic people already score 0.29 to 0.43. The colour-based hair test, `target_head_remains` and the pose check were added after the first run exposed the gaps; the document records that (section 2).

## Other commands

- `repaste`: rebuild the pasted-back results from the saved raw model outputs (after a change to the paste mask) without generating again.
- `rescore`: recompute every score from the saved PNGs.
- `export --json <file>`: write the `results.json` that is committed here.

Records name the engines `klein4b-Q4_0-headlora` (`--engine klein-lora`), `klein4b-Q4_0-prompt-only` (`klein-prompt`) and `composite-floor` (`composite`).

## Files

- `probe.py`: the tool (pure helpers at the top, engines and judges below).
- `test_probe.py`: offline tests.
- `quality.py`, `test_quality.py`: the IDS-12A evaluator and its synthetic tests.
- `run_cohort.py`, `test_run_cohort.py`, `cohort.json`, `results/ids12f/`: the IDS-12F cohort run (above).
- `results/results.json`: every measured record from the run reported in the document, with host details.
- [`docs/identity-studio/full-head-sheet.jpg`](../../../docs/identity-studio/full-head-sheet.jpg): contact sheet of that run (synthetic people only; generated by this probe, depicts no real person).
