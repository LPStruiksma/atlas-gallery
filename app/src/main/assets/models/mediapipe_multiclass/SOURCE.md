# IDS-18 MediaPipe multiclass — staging record (NOT SHIPPED)

Original Google source: https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite

- Source size: 16,371,837 bytes.
- Source SHA-256: c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0 (existing IDS-2 pin).
- Source licence: MediaPipe Apache-2.0; see docs/identity-studio/LICENCES.md and Google's model card.
- Converter: tflite2onnx==0.4.1, tools/identity_bench/export/mediapipe_segmenter_to_onnx.py.
- Change notice: converted to ONNX by ATLAS — PLANNED, not yet independently demonstrated.
- ONNX SHA-256: NOT AVAILABLE. ONNX binary: NOT PRESENT.
- Synthetic and licensed-bench per-class IoU / maximum output difference: NOT MEASURED.
- IDS-2 T16/T23/T24/T25/T27 threshold recalibration: NOT MEASURED.
- Actual Android emulator / model integration proof: NOT RUN.

No model weight or redistributed LICENSE is included in this staging step.
Before shipping, build and independently inspect the ONNX binary, add Google's
full Apache-2.0 LICENSE and accurate conversion notice, record the real output
SHA-256 and both parity cohorts, remeasure the guard, and run exact-head CI +
Android emulator. Without the asset the runtime chooses mask=fallback.
