# GHOST 1 export (IDS-15, #506)

Exports GHOST 1's generator and its identity backbone from ai-forever's official release to ONNX (opset 17), in FP32, FP16-weight and int8-weight variants. Results and the recommendation are in [`docs/identity-studio/GHOST_EXPORT.md`](../../../docs/identity-studio/GHOST_EXPORT.md).

```bash
python3 -m unittest tools/identity_bench/export/test_ghost1_export.py    # offline; the PyTorch round trip skips without torch
python3 -m tools.identity_bench.export.ghost1_export --blocks 1           # needs PyTorch (CPU)
python3 -m tools.identity_bench one --candidate ghost1_generator_int8     # bench one exported variant
```

- `ghost1_export.py`: download (pinned SHA-256) → export with PyTorch → FP16 and int8 weight variants → parity against PyTorch and a check that every operator is standard ai.onnx.
- `iresnet_torch.py`: a minimal PyTorch IResNet in the arcface_torch layout, enough to load `backbone.pth`.
- The bench (`pipeline.LOCAL_MODELS`) finds the exports in `~/.cache/atlas-identity-bench/export/`. Each exported generator takes its source vector from the same-variant backbone (`pipeline.SOURCE_ENCODERS`).

The backbone is InsightFace's research-only `ms1mv3_arcface_r100_fp16` (#505): it may be measured here and never shipped. Nothing this tool writes is committed.
