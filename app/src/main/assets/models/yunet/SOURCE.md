# YuNet face detector (vendored model)

- Upstream: https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_detection_yunet
- Upstream commit: `48b10fa505aeb51fbd127fc60a5c660040e823b7` (opencv_zoo `main`, fetched 2026-11-03)
- File: `face_detection_yunet_2023mar.onnx` (fixed input `[1,3,640,640]`, float32, BGR 0..255)
- SHA-256 `face_detection_yunet_2023mar.onnx`: `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` (232,589 bytes)
- SHA-256 `LICENSE`: `c83b8120c50ccbd4c4f96edf53141bdd566ebb8f8e9227e415326aa1b1aba958`
- Licence: MIT, Copyright (c) 2020 Shiqi Yu. The upstream README says "All files in this directory are licensed under MIT License". The `LICENSE` file here is the upstream file, byte for byte.

The app verifies the model's SHA-256 before loading it (`YuNetModel.SHA256`) and refuses to run on a mismatch.
The model runs fully on device through ONNX Runtime; no image, face box or embedding leaves the phone.
