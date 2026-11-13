# SFace face embedder (vendored model)

- Upstream: https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_recognition_sface
- Upstream commit: `48b10fa505aeb51fbd127fc60a5c660040e823b7` (opencv_zoo `main`, fetched 2026-11-03)
- File: `face_recognition_sface_2021dec_int8.onnx` (int8-quantised MobileFaceNet trained with the SFace loss; input `data` `[1,3,112,112]` float32 RGB 0..255, output `fc1` `[1,128]`)
- SHA-256 `face_recognition_sface_2021dec_int8.onnx`: `2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a` (9,896,933 bytes). This equals the Git LFS pointer's `oid sha256` in the upstream tree at that commit, so the bytes are the ones upstream published.
- SHA-256 `LICENSE`: `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`
- Licence: Apache License 2.0. The upstream README says "All files in this directory are licensed under Apache 2.0 License". The `LICENSE` file here is the upstream file, byte for byte. Upstream ships no NOTICE file for this model. Model by Yaoyao Zhong (SFace), ONNX conversion by Chengrui Wang, per the upstream README.
- Why int8: about 10 MB instead of 39 MB for the fp32 export; upstream reports LFW accuracy 0.9932 (int8) vs 0.9940 (fp32).

Alignment and preprocessing follow OpenCV `FaceRecognizerSF` (`alignCrop` + `feature`): a similarity transform from YuNet's five landmarks to the 112x112 ArcFace template, bilinear warp, out-of-frame pixels black (zero padding, `BORDER_CONSTANT`), RGB order. See `SFaceAlignment.kt`. Landmarks come from `OnnxFaceDetector.detectFaces`, which already removes YuNet's top-left letterbox (scale only, no offset). A local cross-check against OpenCV 4.10 gave crop differences of at most 2 levels on a drawn face (mean 0.02) and cosine 0.997 between the OpenCV and the ported embedding.

The app verifies the model's SHA-256 before loading it (`SFaceModel.SHA256`) and refuses to run on a mismatch.
The model runs fully on device through ONNX Runtime; no image, face crop or embedding leaves the phone.
