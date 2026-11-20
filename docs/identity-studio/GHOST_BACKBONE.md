# Where GHOST 1's identity backbone comes from

Unit [#505](https://github.com/LPStruiksma/atlas-gallery/issues/505) (IDS-14), goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Tool: [`tools/identity_bench/provenance/compare_backbone.py`](../../tools/identity_bench/provenance/compare_backbone.py). Run of 2026-11-18. Companion: [LICENCES.md](LICENCES.md), [BENCH.md](BENCH.md).

## Verdict

**`InsightFace-derived`.** GHOST 1's `arcface_model/backbone.pth` is InsightFace's own arcface_torch model zoo checkpoint `ms1mv3_arcface_r100_fp16`, trained by InsightFace in March 2021. InsightFace releases those models "for non-commercial research purposes only". So the backbone is research-only (tier C), not Apache-2.0.

What this changes: GHOST 1 was the one swap network with a possible permissive path to shipping (BENCH.md, "First engine choice"). It has none as published. The generator was trained to read this backbone's embeddings, and the only other way to produce them (FaceFusion's CrossFace converter) starts from InsightFace's `w600k_r50`, which is research-only too. No shipping decision is made here.

How sure: the file was not compared byte for byte with InsightFace's official download, because that is on OneDrive and Baidu, which this environment cannot reach. The verdict rests on the five independent pieces of evidence below. Each one alone would be weak; together they leave no other plausible source.

## Evidence, strongest first

### 1. The file is byte-identical to a public arcface_torch training folder

| | SHA-256 | Size |
|---|---|---|
| GHOST release asset [`arcface/backbone.pth`](https://github.com/ai-forever/ghost/releases/download/arcface/backbone.pth) (uploaded 2021-12-14) | `a566a623…8109b` | 261,223,796 bytes |
| [`marcelohaps/arcface-torch/backbone.pth`](https://huggingface.co/marcelohaps/arcface-torch/tree/main) on Hugging Face | `a566a623…8109b` | 261,223,796 bytes |

All 104 convolution and linear weight tensors are equal, and the embeddings of 56 synthetic faces are identical (largest difference 0.0).

The mirror is a third party's upload (2026), labelled "ArcFace R100 MS1MV3 FP16" by the project that links it. A label on a mirror proves nothing. What the mirror folder also contains does: eight files `rank_0_softmax_weight.pt` … `rank_7_softmax_weight.pt` and their `_mom.pt` twins. That is exactly the layout of an arcface_torch training output, as InsightFace's own [ICCV21-MFR tutorial](https://github.com/deepinsight/insightface/blob/master/challenges/iccv21-mfr/tutorial_pytorch_cn.md) describes it: `backbone.pth` plus, "because of model parallelism, each GPU saves its own softmax parameters", `rank_N_softmax_weight.pt` and `rank_N_softmax_weight_mom.pt`. GHOST never published such files. They are the leftovers of the original training run, not of someone copying GHOST.

### 2. The class shards match an InsightFace dataset

Shard 0 holds 11,398 class centres of 512 values; shards 4 to 7 are 2,048 bytes smaller (one centre fewer). arcface_torch gives the first `classes mod 8` ranks one extra centre, so the run had 4 × 11,398 + 4 × 11,397 = **91,180 classes**. That is the class count of `ms1m-retinaface-t2` in early arcface_torch configs, for example [this copy](https://github.com/dominhhieu1019/arcface_torch/blob/HEAD/config.py): `config.num_classes = 91180`, `config.num_epoch = 25`. MS1M-RetinaFace is InsightFace's cleaned MS-Celeb-1M.

### 3. The checkpoint's step counter matches InsightFace's published training log

A PyTorch batch-norm layer counts the training steps it has seen. In GHOST's file, `bn1`, `bn2` and `features` all read **124,550**.

InsightFace's model zoo links a training log for each model. The log of [`ms1mv3_arcface_r100_fp16`](https://raw.githubusercontent.com/anxiangsir/insightface_arcface_log/master/ms1mv3_arcface_r100_fp16/training.log) starts with `2021-03-15 09:30:55 … Total Step is: 124550` and ends at `2021-03-16 05:32:33 … Epoch: 24   Global Step: 124550`. Same number, 25 epochs, as in piece 2. A model trained independently by another team would only hit that exact count by coincidence.

(124,550 steps over 25 epochs at a global batch of 1,024 is about 5.10 million images per epoch. That fits MS1M-RetinaFace, though slightly below the 5,179,510 images the `t1` variant lists, consistent with the `t2` class count. Either way it is InsightFace's dataset and training run.)

### 4. GHOST's model code is InsightFace's file

GHOST loads the backbone with `iresnet.py`, published next to `backbone.pth` in the same release. Compared with InsightFace's [`recognition/arcface_torch/backbones/iresnet.py`](https://github.com/deepinsight/insightface/blob/master/recognition/arcface_torch/backbones/iresnet.py), ignoring blank lines, the only differences are InsightFace's later additions (gradient checkpointing, an `extra_gflops` field). It is an earlier revision of the same file. GHOST's [`train.py`](https://github.com/ai-forever/ghost/blob/main/train.py) loads the backbone and calls `netArc.eval()`; GHOST does not train it.

### 5. GHOST never says it trained the backbone, and has not answered when asked

- [README](https://github.com/ai-forever/ghost/blob/main/README.md) and [`download_models.sh`](https://github.com/ai-forever/ghost/blob/main/download_models.sh) (commit `44e58aa`): the backbone appears only as a download under `# load arcface`. No source, dataset or licence is named. Nothing in the repository's 37 commits trains an iresnet backbone. `models/models.py` (added 2021-12-20 with the super-resolution code) has an `ArcMarginModel` head next to SimSwap-style `fsModel` code, but nothing imports it with an iresnet, and its network has a different layer layout.
- [Issue #25](https://github.com/ai-forever/ghost/issues/25) (2022-02-27): "I want to know which dataset is used to train the arcface model." No reply.
- [Issue #88](https://github.com/ai-forever/ghost/issues/88) (2023-12-23), "what is a model license?". Reply by AlexanderGroshev (first author of the GHOST paper), 2024-01-15: "The model license is Apache 2.0. Commercial use is permitted under the Apache 2.0 license." A later comment (2024-10-24) asks: "wouldn't it be difficult to use the ArcFace model for commercial purposes?", and another (2026-10-24) asks for "permission specifically for the ArcFace weight, rather than the GHOST generator". Neither has an answer. The 2024 reply speaks for GHOST's own model and does not address the backbone. It cannot relicense InsightFace's weights either way.
- The paper ([IEEE Access, doi 10.1109/ACCESS.2022.3196668](https://ieeexplore.ieee.org/document/9851423)) could not be read here: `ieeexplore.ieee.org` and `habr.com` (the companion article) are outside this environment's network allowlist. Nothing in pieces 1 to 4 depends on it.

The issue comments were read through a page fetch, because the GitHub API is not enabled for that repository in this session. The quotes are as that fetch returned them.

## Comparison with other public r100 checkpoints

Tensors are paired in network order and compared per output channel. Folding a batch norm into a convolution scales each channel, so a converted copy of a model still scores 1.0 by this measure. Unrelated models score about 0.02.

| Checkpoint | What its host says it is | Same file | Equal tensors | Channel cosine |
|---|---|---|---|---|
| [marcelohaps/arcface-torch](https://huggingface.co/marcelohaps/arcface-torch) `backbone.pth` | arcface_torch R100 MS1MV3 FP16 (mirror) | **yes** | **104 / 104** | **1.000** |
| InsightFace `antelopev2/glintr100.onnx` ([v0.7 release](https://github.com/deepinsight/insightface/releases/tag/v0.7)) | r100, Glint360K | no | 0 / 104 | 0.016 |
| [ONNX model zoo `arcfaceresnet100-8`](https://huggingface.co/onnxmodelzoo/arcfaceresnet100-8) | InsightFace LResNet100E-IR (MXNet), MS1M | no | 0 / 104 | 0.017 |
| [Idiap ArtFace IResNet100-Tuned](https://huggingface.co/Idiap/ArtFace-IResNet100-Tuned) | fine-tuned from antelopev2 | no | 0 / 104 | 0.016 |
| [CVLFace ArcFace IR101 WebFace4M](https://huggingface.co/minchul/cvlface_arcface_ir101_webface4m) | independent re-implementation | no | 0 / 104 | 0.016 |

**Controls**, to show the method finds a derivative when there is one: Idiap's fine-tuned model against `glintr100`, the model its card says it was tuned from, gives **103 of 104 tensors equal** and channel cosine 1.000. Idiap against CVLFace (unrelated) gives 0 and 0.016.

**Embeddings** of 56 aligned synthetic faces (32 FLUXSynID identities from the bench, CC BY-NC 4.0):

| Model | Same person (mean cosine) | Different people | Difference from GHOST's backbone | Similarity structure vs GHOST's (r) |
|---|---|---|---|---|
| GHOST backbone | 0.761 | 0.157 | – | – |
| mirror | 0.761 | 0.157 | 0.0 (identical) | 1.000 |
| InsightFace glintr100 | 0.750 | 0.156 | different space | 0.957 |
| ONNX zoo r100 | 0.769 | 0.186 | different space | 0.947 |

GHOST's backbone is a working face recogniser, and the ONNX graph built from it is correct. Its outputs are identical to the mirror's and differ from the other models. The high structure agreement with glintr100 and the zoo model (0.95) is what any two good face recognisers show on the same faces. It is not evidence of a shared origin.

**Not compared:** InsightFace's official `ms1mv3_arcface_r100_fp16/backbone.pth` itself (OneDrive and Baidu links, not reachable from here), the MXNet `arcface_r100_v1` params, and SimSwap's ArcFace checkpoint (different architecture, Google Drive).

## What follows for the licence register

- `arcface_model/backbone.pth` moves from tier D ("origin unstated") to **tier C, research-only**, under InsightFace's terms ([README, License](https://github.com/deepinsight/insightface/blob/master/README.md#license): "The training data containing the annotation (and the models trained with these data) are available for non-commercial research purposes only"; [arcface_torch model zoo](https://github.com/deepinsight/insightface/tree/master/recognition/arcface_torch#model-zoo): "The models are available for non-commercial research purposes only").
- **The GHOST 1 generators stay Apache-2.0 as files**, but they are no longer a ship candidate on their own. They need this backbone's embedding at run time. Whether an Apache generator trained against a research-only encoder is itself restricted is a legal question this register does not answer.
- The register's headline stands, now with evidence for GHOST too: no open face swapper read so far has a permissive chain from weights to identity encoder.

Options for an owner decision, not decided here: ask InsightFace for a licence (which would also cover InSwapper and HyperSwap's encoder); retrain GHOST's generator against a permissively licensed encoder (SFace is Apache-2.0 and already shipped, but much weaker); or keep Identity Studio's swap as private, non-commercial use and accept the research-only terms knowingly. Each of these is Lukas's call.

## Reproduce

```bash
python3 -m unittest tools/identity_bench/provenance/test_compare_backbone.py      # offline, synthetic tensors
python3 -m tools.identity_bench.provenance.compare_backbone --only mirror_arcface_torch --no-embeddings
python3 -m tools.identity_bench.provenance.compare_backbone                       # everything, about 1.6 GB of downloads
```

PyTorch is not needed. Checkpoints are fetched at run time into `~/.cache/atlas-identity-bench/provenance/` and pinned by SHA-256. None is committed. The full result is written to `compare_backbone.json` in the same folder.
