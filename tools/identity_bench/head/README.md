# Full-head probe (IDS-3, #470)

Measured probes behind [`docs/identity-studio/FULL_HEAD.md`](../../../docs/identity-studio/FULL_HEAD.md). Full head means the hair, hairline, head shape and ears change with the face. The probe runs head-swap engines on synthetic people, times them, records peak memory, scores the output and extrapolates to the Galaxy S24+.

Nothing here ships in the app. No weights, no generated fixture images and no personal images are committed: weights are fetched from their original hosts with pinned SHA-256, and the test people are generated at run time from fixed prompts and seeds. The one committed image is `results/sheet.jpg`, a contact sheet of this probe's synthetic people.

## Offline tests (no dependencies)

    python3 -m unittest tools/identity_bench/head/test_probe.py

Standard library only. The tests cover the classification rule (a face-only result can never be counted as full head), the pre-registered identity thresholds, the S24+ extrapolation arithmetic, the cost formula, the manifest (pinned downloads, prompts that name no real person) and the committed `results/results.json`.

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
- `results/results.json`: every measured record from the run reported in the document, with host details.
- `results/sheet.jpg`: contact sheet of that run (synthetic people only; generated by this probe, depicts no real person).
