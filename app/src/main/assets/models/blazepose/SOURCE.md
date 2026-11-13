# MediaPipe person detector + BlazePose full (vendored models)

Used by the opt-in "emphasise pose" control of visual search (goal #468, VS-S2p): `nl.atlas.gallery.featurevisual.pose.OnnxPoseEstimator`.

- Upstream: OpenCV Zoo at commit `48b10fa505aeb51fbd127fc60a5c660040e823b7`, directories [`models/person_detection_mediapipe`](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/person_detection_mediapipe) and [`models/pose_estimation_mediapipe`](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/pose_estimation_mediapipe). ONNX conversions of Google's MediaPipe Pose models (2023mar).
- Fetched 2026-11-07 over HTTPS from the pinned Git LFS URLs in `tools/visual_search_bench/models.json` (`media.githubusercontent.com/media/opencv/opencv_zoo/48b10fa…/models/<dir>/<file>`). Both SHA-256 values match the pins there, which are the files the VS-S0 benchmark measured.
- Licence: Apache License 2.0. Each upstream model README, section "License", at that commit: "All files in this directory are licensed under [Apache 2.0 License](LICENSE)." The `LICENSE` file here is `models/person_detection_mediapipe/LICENSE` at that commit, byte for byte (SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`); the pose directory's `LICENSE` is the same text without the final newline. Upstream ships no NOTICE file in either directory.

| File | Bytes | SHA-256 | Input → outputs |
|---|---|---|---|
| `person_detection_mediapipe_2023mar.onnx` | 11,990,159 | `47fd5599d6fa17608f03e0eb0ae230baa6e597d7e8a2c8199fe00abea55a701f` | `[1,3,224,224]` RGB in [-1, 1] → 2254 boxes × 12 (box + 4 keypoints), 2254 logits |
| `pose_estimation_mediapipe_2023mar.onnx` | 5,557,238 | `9d89c599319a18fb7d2e28451a883476164543182bafca5f09eb2cf767ed2f3f` | `[1,256,256,3]` RGB in [0, 1] → 39 image landmarks × 5, presence, mask, heatmap, 39 world landmarks × 3 |

The app checks each file's SHA-256 before ONNX Runtime opens it (`PoseModel`) and refuses to run on a mismatch. Version fence stored with every descriptor: `PoseModel.POSE_VERSION` (`blazepose-2023mar:47fd5599-9d89c599:d1`).

Pipeline (a 1:1 port of `tools/visual_search_bench/blazepose.py` and `pose_descriptor.py`; contract in `docs/visual-search/VS_S0_MODEL_SPIKE.md`): letterbox the upright image to 224 × 224 → best person box above 0.5 → square region centred on the hip, rotated so the body points up → 256 × 256 crop → landmark model, presence ≥ 0.8 → 33 metric world landmarks, rotation undone → 12 de-yawed bone directions plus their mirror (72 floats). Golden values for the tests come from `tools/visual_search_bench/pose_golden.py`. The models run fully on device; no image, landmark or descriptor leaves the phone.
