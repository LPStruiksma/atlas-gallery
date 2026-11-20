# SigLIP 2 tag matrix (TAGS-0)

Unit TAGS-0 ([#667](https://github.com/LPStruiksma/atlas-gallery/issues/667)). Method, calibration numbers and reproduction: [`docs/visual-search/TAGS_0.md`](../../../../../../docs/visual-search/TAGS_0.md).

- `tags.bin`: 159 tag vectors, each the SigLIP 2 text embedding of the tag's phrases in three templates, produced by the shipped text tower `../siglip2_text` (SHA-256 `6fd97706a61981eb1a8d2444777209f0768162d3172c74a73134fbc1f78c0da6`) with `tools/tag_vocab/tag_vocab.py`, stored int8 with one scale per row, plus per-tag cosine thresholds calibrated on CC BY 2.0 Open Images V7 images (IDs in `tools/tag_vocab/data/tags0_manifest.csv`; no pixels committed). Fenced to `siglip2_b16_224.int8:74da3ac2b46e`. 124,951 bytes, SHA-256 `def21ed1f26f1f246c7c1bf4b10008da69338897801448db814b7ff14ca5d0d6`.
- `tags.tsv`: English and Dutch display names, same order. SHA-256 `bd3fbe3e0a88c926be8ee7bb8ed30e311ace7d2f614846c8431830c6cef88259`.
- Licence: derived from the Apache-2.0 SigLIP 2 checkpoint (see `../siglip2_text/SOURCE.md` and `LICENSE` there). No personal data.
- Check a checkout: `python -I tools/tag_vocab/tag_vocab.py verify`.
