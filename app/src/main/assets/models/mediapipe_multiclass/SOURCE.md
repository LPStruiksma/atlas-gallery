# MediaPipe multiclass selfie segmenter, converted to ONNX (IDS-18)

- Upstream file: https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite
- Upstream size and SHA-256 (the IDS-2 pin, re-checked 2026-11-22): 16,371,837 bytes, `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0`
- Licence: Apache-2.0 ([model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf)). `LICENSE` here is MediaPipe's `LICENSE`, byte for byte, from https://github.com/google-ai-edge/mediapipe/blob/100f769b1532d5612ea5200958af7a536b804bb6/LICENSE (12,331 bytes, SHA-256 `8707eef0533987efc5b155d64761eeb6e20793f50b9bd1a68dad1cf4719d0ed8`).
- Model card scope: the card puts "surveillance or identity recognition" out of scope. ATLAS uses the model only as a face-skin mask for the blend, never to recognise anyone.

## Change notice (Apache-2.0 section 4(b))

ATLAS converted the TFLite file to ONNX on 2026-11-22. The weights and the graph's arithmetic are unchanged, only the format differs:

- File: `selfie_multiclass_256x256.onnx`, 16,454,491 bytes, SHA-256 `829b63f979ba9fe0518d78ef577129cb259ba557fd753c572dd431e8921587be`
- Converter: `tools/identity_bench/export/mediapipe_segmenter_to_onnx.py` with `tf2onnx==1.17.0`, `tensorflow-cpu==2.21.0`, opset 17, Python 3.13. (The first pin, `tflite2onnx==0.4.1`, stops at TFLite op 74 `SUM` in this model.)
- Layout: input `input_29` float32 NHWC `[1,256,256,3]`, RGB in 0..1. Output `Identity` float32 NHWC `[1,256,256,6]`, with classes 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other.
- Re-running the converter gives the same graph, but node names and order vary between runs, so the bytes and SHA-256 differ. To check a rebuild, run the parity harness on it rather than comparing bytes.

## Parity: LiteRT original vs ORT converted, measured on this exact file

Measured with `tools/identity_bench/export/mediapipe_ids18_evidence.py`, which runs `mediapipe_parity.py` unchanged (ai-edge-litert 2.4.0, onnxruntime 1.32.0, CPU). Per-class IoU is computed on the argmax label maps.

| Cohort | Samples | background | hair | body skin | face skin | clothes | other | max abs output difference |
|---|---|---|---|---|---|---|---|---|
| licensed (the 29 IDS-2 bench targets, aligned 256 px crops) | 29 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 9.7e-05 |
| synthetic (48 seeded procedural images plus 29 seeded augmentations) | 77 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 0.99998 | 8.7e-05 |

Corpus SHA-256: licensed `5f09d91da48c92542c9359f0ff6ef0981b92cf0fadecffd4b0d0fb0359fc0039`, synthetic `6763848402d095237138d7aeb54fa9db6bf1f701470428ba7c1a516f2a413e37`. The corpora are generated locally and never committed. The bench faces are generated or rendered people under the licences in `tools/identity_bench/FIXTURES.md`.

## Guard re-measure (FaceBlendMask, MIN_COVERAGE 0.5)

Coverage means face-skin pixels divided by the area of the landmark oval, as `FaceBlendMask.decide` computes it. It was measured on the HyperSwap crop (arcface_128 template, 256 px, oval 17,971 px). LiteRT and ONNX gave the same value for all 29 targets.

| Target | Coverage | Decision |
|---|---|---|
| T16 low light | 0.342 | fallback |
| T23 rendered profile | 1.076 | segmenter |
| T24 rendered profile | 0.316 | fallback |
| T25 rendered glasses | 0.492 | fallback |
| T27 rendered low light | 1.370 | segmenter |
| T17 low light (not in the IDS-2 list) | 0.017 | fallback |
| The other 23 targets | 1.342 to 2.004 | segmenter |

The threshold stays at 0.5. Every working mask scores at least 1.076 and every failure at most 0.492, so 0.5 separates them. The margin on the failure side is small, though: T25 is only 0.008 below the threshold. T23 and T27, which failed on IDS-2's crop, pass here on the HyperSwap crop.

## Not covered here

On-device inference with the real asset depends on the emulator job (label `emulator:run`). HEAD mode (hair plus skin) stays unexposed.
