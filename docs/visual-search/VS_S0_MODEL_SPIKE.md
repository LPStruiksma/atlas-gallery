# VS-S0: model spike for semantic search by image

Unit VS-S0 of goal [#468](https://github.com/LPStruiksma/atlas-native/issues/468). Measured 2026-11-07 on main 583b2c0. Tools: [`tools/visual_search_bench/`](../../tools/visual_search_bench/README.md). Raw results: [`results/vs_s0_results_20261107.json`](results/vs_s0_results_20261107.json), export sizes and hashes: [`results/vs_s0_export_20261107.json`](results/vs_s0_export_20261107.json).

## Recommendation

1. **Image embedder: SigLIP 2 B/16 at 224 px, image tower only, ONNX with dynamic int8 weights**, except one MLP layer (`blocks.5/mlp/fc2`) that stays fp32. That gives a 103 MB asset and a 768-d L2-normalised vector. It has the best retrieval of the four candidates (macro mAP 0.462, P@10 0.624; on the pose-heavy classes mAP 0.510), and int8 loses nothing measurable against fp32 (mean cosine 0.978, mAP 0.4623 vs 0.4620). Apache-2.0. It also sits in SigLIP 2's shared image-text space, so a later "search by words" feature can reuse the same index by adding the text tower.
2. **Pose: MediaPipe person detector + BlazePose (OpenCV Zoo ONNX, Apache-2.0, 12 + 5.6 MB), as an opt-in "emphasise pose" control, not as a default fusion.** It re-ranks the semantic top 50 with a mirror- and yaw-tolerant bone-direction descriptor. It helps acrobatic and inverted poses (cartwheel P@10 0.329 → 0.350 with int8, handstand 0.575 → 0.600) but hurts classes where one body's pose isn't the point (yoga 0.671 → 0.583, hug 0.546 → 0.426). Blended into every query, it lowers overall quality at every weight above 0.1.
3. **Fallback for slow devices: SigLIP 2 B/32 at 256 px**, about 2.3× faster (34.7 ms vs 80.6 ms single-thread on the host), for 4.7 mAP points less. Same licence and same vector size, but a different embedding space, so the two can't share an index.

Next unit: VS-S1 can start (image-embedding stage in the existing scanner). The facts it needs are under "Contract for VS-S1" below.

## Candidates and licences

Each licence was checked at the named upstream commit (2026-11-07). Each weight file is pinned by SHA-256 in [`models.json`](../../tools/visual_search_bench/models.json).

| Candidate | Weights | Licence (code + weights) | Verdict |
|---|---|---|---|
| OpenCLIP ViT-B/32 quickgelu, LAION-400M e32 | GitHub release `v0.2-weights` of mlfoundations/open_clip | MIT, open_clip `LICENSE` @ f9a0948 | Measured, weakest |
| SigLIP B/16 224 (WebLI en) | `gs://big_vision/siglip/webli_en_b16_224_63724782.npz` | Apache-2.0: big_vision README "License": "everything in big_vision (including models and colabs)" @ 0127fb6 | Measured, close second |
| **SigLIP 2 B/16 224** | `gs://big_vision/siglip2/siglip2_b16_224.npz` | Apache-2.0, same repository and commit | **Recommended** |
| SigLIP 2 B/32 256 | `gs://big_vision/siglip2/siglip2_b32_256.npz` | Apache-2.0, same | Fallback |
| MediaPipe person detector + BlazePose full (2023mar) | OpenCV Zoo LFS (`media.githubusercontent.com`) | Apache-2.0, OpenCV Zoo model README "License" @ 48b10fa | Recommended for the pose control |
| DINOv2 ViT-S/14 | `dl.fbaipublicfiles.com` / Hugging Face | Apache-2.0 (per plan) | **Not measured**: both hosts are blocked by this cloud environment's network policy. Self-supervised, with no text alignment; expected strong on instance and composition but weaker on actions. Not needed given the SigLIP 2 result. |
| ArcFace/InsightFace, YOLO-pose | — | Non-commercial / AGPL | Excluded (as planned) |

## Benchmark set

- **Images:** 1,397 Open Images V7 train images, 36 action and pose classes (cartwheel, handstand, somersault, yoga, juggling, hug, selfie, high jump and so on; [manifest](../../tools/visual_search_bench/data/open_images_actions_v1.csv)), taken from human-verified positive image-level labels only. Each image's licence is re-checked against Open Images' metadata at fetch time; all are CC BY 2.0. Only IDs and labels are committed: no pixels, author names or embeddings are in the repository, and nothing came from the owner's library.
- **Protocol:** each image queries all others. For a query labelled L, a result is relevant if it is also labelled L. We report the macro mean over classes of AP and P@10, for all 36 classes and for 18 pose-heavy ones (listed in the JSON).
- **Caveat:** the labels are image-level and noisy, and the classes overlap (a cartwheel photo is often labelled handstand). Read the absolute numbers as a lower bound. The ranking between models is what matters.

## Results (host CPU, ONNX Runtime 1.31.1, batch 1)

| Model (int8) | Dim | Asset MB | ms, 1 thread | ms, 4 threads | mAP all | P@10 all | mAP pose-heavy | cos to fp32 |
|---|---|---|---|---|---|---|---|---|
| OpenCLIP B/32 | 512 | 102.8 | 26.1 | 12.5 | 0.377 | 0.545 | 0.398 | 0.985 |
| SigLIP B/16 | 768 | 95.8 | 81.3 | 29.3 | 0.448 | 0.619 | 0.486 | 0.983 |
| **SigLIP 2 B/16** | 768 | 102.9 | 80.6 | 32.0 | **0.462** | **0.624** | **0.510** | 0.978 |
| SigLIP 2 B/32 | 768 | 109.6 | 34.7 | 16.8 | 0.416 | 0.584 | 0.448 | 0.972 |

The fp32 exports (352–378 MB) score within 0.015 mAP of their int8 versions, and the fp32 ONNX matches PyTorch to a cosine of 0.9999996. All four int8 graphs and both pose graphs also load and run on ONNX Runtime 1.23.2, the version the app ships.

The latency host is a 4 vCPU x86 cloud VM, not the phone. A Snapdragon 8 Gen 3 big core runs int8 GEMM in the same range, so expect roughly 60–150 ms per image for SigLIP 2 B/16 on the S24+, plus about 30 ms for the pose pipeline. VS-B measures this on the device.

**The cartwheel example:** for a cartwheel query, 82% of the top 10 are gymnastics moves (cartwheel, handstand, somersault, trampoline). Exact-label P@10 is 0.33, against a 2% base rate. The model recognises the action family well, and the remaining confusion is mostly between neighbouring moves, which is also where the labels are least reliable. The pose control lifts cartwheel and handstand by 2–3 points.

**Pose pipeline:** detector 8.0 ms + landmarks 13.1 ms (1 thread); the median end to end with pre- and post-processing is 30.9 ms. A body was found in 60% of images. Pose alone is a weak retriever (mAP 0.064), as expected: many actions are defined by objects and scenes, not body shape.

**int8 finding:** with plain dynamic int8, the SigLIP 2 exports collapse (cosine 0.78 / 0.72 to fp32, mAP 0.23 / 0.16). The cause is a single layer per model, the MLP output projection of block 5 (OpenCLIP: resblock 7), whose input has activation outliers. Keeping just that one 3072×768 MatMul in fp32 (+7 MB) restores a cosine of 0.98 at unchanged speed. [`quant_probe.py`](../../tools/visual_search_bench/quant_probe.py) finds that layer for any future export.

## Scale (150k items)

- **Index:** 150k × 768 vectors. Stored as int8 with a per-vector scale, that is about 115 MB on disk; fp16 would be about 230 MB. The pose descriptor is 36 floats × 2 (about 43 MB as fp32 for every image, less when only images with a body store one).
- **Query:** exact brute-force cosine over 150k × 768 is about 115 M multiply-adds, roughly 30–80 ms on the phone CPU. No ANN library is needed, the same conclusion as VS-F1 reached for faces.
- **Indexing:** about 0.1 s per image means 150k images take about 4–6 h of charging-only scanner time, run once and then incremental.
- **APK:** +103 MB (embedder) + 17.5 MB (pose). Because there is no INTERNET permission, the models have to ship in the APK. If size matters more than 4.7 mAP points, B/32 is the lever.

## Contract for VS-S1

- **Preprocess:** decode with EXIF orientation applied → RGB → resize to 224×224 without cropping (bilinear) → `x/255`, then `(x − 0.5) / 0.5` → NCHW float32. Input name `pixels`.
- **Output:** `embedding`, shape [n, 768], already L2-normalised, so cosine is a plain dot product.
- **Version fence:** `siglip2_b16_224.int8` plus the int8 file's SHA-256 from the export report. Produce the asset with `export_onnx.py` from the pinned weights; the graph is reproducible from `models.json`.
- **Pose (VS-S2 control):** port [`blazepose.py`](../../tools/visual_search_bench/blazepose.py) and [`pose_descriptor.py`](../../tools/visual_search_bench/pose_descriptor.py) 1:1. Re-rank the top 50 with `sem + 0.15 × (pose − 0.3)`. Without a body, keep the semantic order.
- **Video:** same keyframe sampler as VS-V1, with `frame_ms` stored per vector.
