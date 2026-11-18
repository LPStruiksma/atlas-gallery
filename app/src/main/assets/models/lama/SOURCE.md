# LaMa inpainting (big-lama) for on-device Erase (vendored ONNX file)

- File: OpenCV Zoo `models/inpainting_lama/inpainting_lama_2025jan.onnx`, fetched over HTTPS on 2026-11-18 from the Git LFS media URL `https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/inpainting_lama/inpainting_lama_2025jan.onnx`. The LFS pointer at opencv/opencv_zoo commit `48b10fa505aeb51fbd127fc60a5c660040e823b7` names oid `sha256:7df918ac3921d3daf0aae1d219776cf0dc4e4935f035af81841b40adcf74fdf2`, size 92,591,623, which is exactly the joined file below. The bytes are unmodified.
- Licence: Apache License 2.0. The directory README (same commit) says "All files in this directory are licensed under [Apache License](./LICENSE)". `LICENSE` here is that directory's `LICENSE`, byte for byte (SHA-256 `0d02d0f518d1b068f383b33e5ee100b7e3609e5022b666f827a64135e9ad7a89`, 11,347 bytes). No NOTICE file ships upstream.
- Model: LaMa, "Resolution-robust Large Mask Inpainting with Fourier Convolutions" (Suvorov et al., WACV 2022; advimman/lama, Samsung AI Center, Apache-2.0), the `big-lama` checkpoint. OpenCV's README names its source as Carve/LaMa-ONNX `lama_fp32.onnx`; the OpenCV file is that graph with weight-only int8 blocked quantisation (opset 21 `DequantizeLinear`, block 8, int8 zero points) and the FFTs written as DFT matrix products, so it runs on stock ONNX Runtime CPU with no custom operators.
- Contract: inputs `image` `[n,3,512,512]` float32 RGB in 0..1 and `mask` `[n,1,512,512]` float32 (1 = fill); output `output` `[n,3,512,512]` float32 RGB in 0..255. RGB input order measured slightly better than BGR on synthetic ground truth (mean hole PSNR 24.8 vs 24.3 dB).

## Why this file and size

The unit's size rule was "about 60 MB or less, else stop and report". Smaller variants were tried (`docs/ai-edit/ON_DEVICE_ERASE.md`): per-channel int8 (57.3 MB) lost up to 17 dB of fill quality, block-32 int8 was still 63.4 MB, int4 broke. The size stop was reported on atlas-gallery #434; the coordinator chose to bundle the full-quality file, the same pattern as the 102.9 MB SigLIP model.

## Files

GitHub refuses files over 100 MB and this project uses no Git LFS, so the model ships as ordered parts of at most 60,000,000 bytes (the SigLIP convention). `LamaModelFile` joins them in order, once, into app-private no-backup storage and checks the whole-file SHA-256 before ONNX Runtime sees the bytes (and on every open); on a mismatch it deletes the file and refuses to run.

| File | Bytes | SHA-256 |
|---|---|---|
| `inpainting_lama_2025jan.onnx.part0` | 60,000,000 | `e85da2db31ab98efe595a4dfce527c8596e14c28bd38c8c1cd8b508effc189be` |
| `inpainting_lama_2025jan.onnx.part1` | 32,591,623 | `6820deede2df42300ef669f34ece1a29b37efa8528c1309ebd90699a77a4b517` |
| joined `inpainting_lama_2025jan.onnx` | 92,591,623 | `7df918ac3921d3daf0aae1d219776cf0dc4e4935f035af81841b40adcf74fdf2` |

Recreate the parts: `head -c 60000000 inpainting_lama_2025jan.onnx > …part0 && tail -c +60000001 inpainting_lama_2025jan.onnx > …part1`.

The model runs fully on device through ONNX Runtime; no image, mask or result leaves the phone.
