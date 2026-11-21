# TAGS-0: automatic tags and "Things" from the existing SigLIP 2 index

Unit TAGS-0 ([#667](https://github.com/LPStruiksma/atlas-gallery/issues/667)), for goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97) (Samsung Search groups photos by things: food, pets, documents, scenery) and Vision D ([#101](https://github.com/LPStruiksma/atlas-gallery/issues/101)). No new model, no second index, no tokenizer on the phone and no UI.

## The idea

Every indexed photo already has a SigLIP 2 B/16 image embedding (`siglip2_b16_224.int8:74da3ac2b46e`) in SigLIP 2's shared image–text space. The int8 text tower ships too (NLS-1A, [`NLS_1_TEXT_ASSET.md`](NLS_1_TEXT_ASSET.md)). So a fixed list of tags is encoded **once, on the host**, with that same text tower, and shipped as a small matrix. On the phone, tagging a photo is one dot product per tag against the vector the index already stored, and a per-tag cosine threshold decides whether the tag applies.

## What ships in this unit

| Path | What |
|---|---|
| `tools/tag_vocab/vocabulary.tsv` | 159 tags in 14 categories (pets, animals, food, drinks, documents, art, scenery, places, events, sport, vehicles, people, objects). Each row has English and Dutch display names, the phrases fed to the text tower, the Open Images classes used to calibrate it and the tags it is most easily confused with. |
| `tools/tag_vocab/tag_vocab.py` | Host script: `select` (pick licensed calibration images), `encode` (text tower → tag vectors), `calibrate` (thresholds, report, assets), `verify` (standard library only). |
| `tools/tag_vocab/data/tags0_manifest.csv` | The 16,792 Open Images image IDs used, with our tag ids and labels. IDs only: no pixels, URLs, authors or titles. |
| `app/src/main/assets/models/siglip2_tags/tags.bin` | 124,951 bytes: 159 int8 rows × 768 with one scale per row, plus per-tag threshold and "on by default" flag, fenced to the image model, text model and vocabulary hash. SHA-256 `def21ed1f26f1f246c7c1bf4b10008da69338897801448db814b7ff14ca5d0d6`. |
| `app/src/main/assets/models/siglip2_tags/tags.tsv` | Display names (`id category en nl`), same order. |
| `featurevisual/tags/` | `TagMatrixCodec` (reads and writes `tags.bin`, rejects anything malformed), `TagScorer` (top-k tags per item, threshold rule, version fence), `TagVocabulary` (`tags.tsv`), `TagCostEstimate`. JVM tests on synthetic vectors plus one test over the committed asset. |
| `docs/visual-search/results/tags0_calibration.json` | Per-tag thresholds, precision, recall, AP and false-positive rates. |

Nothing loads the asset yet. Wiring it into the scanner and store is TAGS-1.

## Text side

- Text tower: the shipped `siglip2_b16_224_text_int8.onnx` (SHA-256 `6fd97706…`, the phone's own bytes), joined from the asset parts and hash-checked before use.
- Tokenizer: `gemma_tokenizer.model`, SHA-256 `6969e640…` (pin in `tools/visual_search_bench/models.json`), host only, with NLS-1A's exact contract (ASCII lowercase, no BOS, sticky EOS, pad 0, length 64).
- Templates: each tag's vector is the normalised mean over its phrases × templates. Two template sets were compared by mean AP on the calibration half: one template `a photo of {}.` 0.681, three templates (`a photo of {}.`, `this is a photo of {}.`, `{}`) **0.694**, so the three-template set is used.
- Rows are stored int8 with a per-row scale (the `VisualVectorCodec` scheme), and calibration scores the **dequantised** rows against image vectors that went through the store's int8 round trip, so the thresholds hold for the bytes the phone will have.

## Calibration (2026-11-20, Claude cloud seat, 4 vCPU x86, Python 3.13, onnxruntime 1.31.1)

**Data.** Open Images V7 human-verified image-level labels (validation, test and the boxable train subset), restricted to images whose Open Images metadata says CC BY 2.0. Images were fetched by `tools/visual_search_bench/fetch_images.py` (it re-checks the licence), downscaled to 384 px and kept only in a local cache; 16,780 of 16,792 downloaded. Per tag: up to 60 verified positives, up to 40 verified negatives (Open Images verifies machine-proposed labels, so these are the model-plausible hard negatives), plus 2,000 random validation and test images shared by all tags as background. Background images are not exhaustively labelled, so a few true positives count as false positives there: background numbers are a conservative bound.

**Split.** Every image goes to a calibration or evaluation half by the first byte of SHA-256(ImageID). Thresholds and the template choice use the calibration half only; every number below is from the evaluation half.

**Threshold rule.** Highest F0.5 on the calibration half (precision weighs more than recall for tags nobody asked for), but never below the 99th percentile of the background, so at most about 1% of unrelated photos get any one tag. A tag with fewer than 12 positives in either half is **uncalibrated**: it gets the 99.5th background percentile and stays off.

**On by default.** A tag ships on only if it is calibrated and its evaluation precision is at least 0.6 and recall at least 0.15. The rest stay in the matrix, off, so TAGS-1 or a later vocabulary can revisit them without a new text-tower run.

### Results

| | Value |
|---|---|
| Tags | 159 |
| Calibrated | 142 |
| On by default | **117** |
| On-by-default median precision / recall | **0.80 / 0.62** |
| All calibrated: median precision / recall / AP | 0.77 / 0.54 / 0.73 |
| Median background false-positive rate per on-by-default tag | 0.21% (max 1.67%, `airplane`) |
| Median hard-negative false-positive rate per on-by-default tag | 0.24 |
| Median confuser false-positive rate per on-by-default tag | 0.04 |
| Background photos: mean number of on-by-default tags | 0.71; 47% get none |

Precision is measured at the evaluation pool's mix (about 30–60 positives against about 1,000 background and 20 hard negatives per tag). A real library has a different mix per tag, so the background false-positive rate is the number that transfers: at 0.2%, a library of 150k photos sees about 300 wrong photos per tag before any user feedback.

**Strong** (evaluation precision ≥ 0.9 and recall ≥ 0.6): sheep, pizza, moon, lighthouse, christmas, fireworks, football, tennis, yoga, bicycle, boat, candle.

**Hard-negative and confuser check.** The verified negatives are deliberately hard and the median on-by-default tag still lets about a quarter of them through, so treat any single tag as a suggestion, never a fact. Confusers are measured as positives of the named neighbour tags: the worst on-by-default pairs are tea vs coffee (0.66: a cup is a cup), child vs baby (0.56), insect vs butterfly (0.55), camping vs hiking (0.50), cartoon vs drawing (0.36) and bicycle vs cycling or motorcycle (0.35). Most others are below 0.10, for example dog vs cat/horse/sheep 0.007, cat vs dog/rabbit 0.007, pizza 0.0.

**Off, calibrated but below the gate** (25): salad, soup, seafood, cheese, text, document, poster, ID documents, diagram, river, trees, plants, field, grass, building, house, architecture, road, castle, fountain, restaurant, party, sport, car, shoes. Most fail on recall, not precision: Open Images marks a tree, car or building as present when it is a small part of the frame, while a global image embedding mostly encodes the main subject. That is the right behaviour for "Things" (a photo of a car, not a photo with a car in the corner). `text`, `document` and `sport` are too broad for one vector.

**Off, uncalibrated** (17, too few licensed labels): receipt, handwriting, night, city, bridge, museum, birthday, halloween, festival, cycling, running, skiing, surfing, sailing, golf, hiking, selfie. Receipts and screenshots matter most for a phone library: `screenshot` did calibrate (P 0.86, R 0.74, on), `receipt` did not (3 evaluation positives). A licensed or synthetic receipt set is the first thing to add.

## Cost

- **Indexing:** none. Tags reuse the stored vectors.
- **Scoring:** 159 × 768 = 122k multiply-adds per photo; 150k photos = 18.3 G. Measured on the host JVM (this unit's `TagScorer`, single thread, synthetic vectors, 4 vCPU x86): **134 µs per photo, 0.91 G multiply-adds/s**, so 150k photos take about 20 s single-threaded. The phone is a different CPU; TAGS-1 measures it on the S24+ and should run it with the charging-only scanner, in batches.
- **Memory:** the decoded matrix is 159 × 768 floats = 488 KB; the asset is 125 KB.
- **Storage (TAGS-1):** up to 5 tag ids per photo.

## Version fence

`TagMatrix.fence` = `image model | text model | vocabulary hash` (`siglip2_b16_224.int8:74da3ac2b46e|siglip2_b16_224.text.int8:6fd97706a619|68d01394cf88`). `TagScorer` refuses vectors whose model version is not the matrix's image model (`TagModelMismatchException`); a new index model or a new text tower means re-running `encode` and `calibrate`. TAGS-1 stores the fence next to each tagged row and re-tags when it changes.

## Reproduce

From the repository root, with `onnxruntime==1.31.1 sentencepiece==0.2.3 numpy pillow` and the Open Images CSVs in `$OI` (`oidv7-class-descriptions.csv`, `oidv7-{val,test,train}-annotations-human-imagelabels.csv`, `{validation,test}-images-with-rotation.csv`, `train-images-boxable-with-rotation.csv`, all from `https://storage.googleapis.com/openimages/`):

```
python -I tools/tag_vocab/tag_vocab.py select --classes $OI/oidv7-class-descriptions.csv \
  --labels $OI/oidv7-val-annotations-human-imagelabels.csv --labels $OI/oidv7-test-annotations-human-imagelabels.csv \
  --labels $OI/oidv7-train-annotations-human-imagelabels.csv \
  --images validation $OI/validation-images-with-rotation.csv --images test $OI/test-images-with-rotation.csv \
  --images train $OI/train-images-boxable-with-rotation.csv
python -I tools/tag_vocab/tag_vocab.py encode --tokenizer $C/gemma_tokenizer.model --work $W
python -I tools/visual_search_bench/fetch_images.py --manifest <unique ImageID,Split of the manifest> \
  --metadata <the three image lists> --cache $CACHE --max-side 384 --out $W/licensed.csv
python -I tools/tag_vocab/tag_vocab.py calibrate --cache $CACHE --work $W
python -I tools/tag_vocab/tag_vocab.py verify
```

The train label file is 2.7 GB; filtering it to the vocabulary's classes while streaming is enough. Embedding 16.8k images with the int8 image tower took 9 minutes on 4 vCPU; `calibrate` saves progress and resumes.

## TAGS-1: scanner hook and store ([#732](https://github.com/LPStruiksma/atlas-gallery/issues/732))

| Path | What |
|---|---|
| `featurevisual/tags/store/VisualTagStore.kt` | App-private SQLite `atlas_visual_tags.db` (the app sets `allowBackup=false`). `items(id, media_key, frame_ms, revision)` per scored photo (frame -1) or video moment, `hits(item, tag, margin)` by item rowid and a `tags` dictionary id (a hit costs a few bytes, not a repeated media key), cascade-deleted with the item, index `hits(tag, margin)`, `meta` with the fence and sweep position. |
| `featurevisual/tags/store/VisualTagger.kt` | `tagPublished` (incremental), `sweepStep` (backfill and reconcile), `TaggingIndexPort` (the scan hook), the `VisualTagStorePort` contract with `tagsFor(mediaKey, revision?)` and `mediaForTag(tagId, scope)`. |
| `featurevisual/tags/store/VisualTagHook.kt` | What the worker holds: opens the matrix asset and the database lazily on the worker thread, never at app start; a failure turns tagging off for the process and never touches the visual index. |

**One call site.** `PeopleDiscoveryHost`'s visual step builds its `VisualScanStage` on `visualTags.wrap(store)`: after the visual index *accepts* a batch, the same vectors (int8 round trip, so exactly the bytes the store keeps) are scored and written. A refused batch tags nothing; a tagging error never fails the index write.

**Backfill and reconcile.** When the visual step finds nothing to claim, the same worker step runs one bounded tag sweep: pages of 256 stored vectors from `loadActiveVectors` for the current epoch (so the grant's epoch gates it), at most 8 pages or 1.5 s, the host guard (charging, thermal, grant, stop) before every page. Rows whose stored revision matches are skipped without scoring, new or changed rows are scored, rows whose vectors are gone are deleted. While the sweep has more to do the step reports `VisualScanResult.tagged`, so the worker continues now under its usual guards; a finished sweep runs again after 24 h to catch drift. Videos are tagged per moment; `tagsFor` takes each tag's best margin over the moments.

**Invalidation.** The fence (`image model | text model | vocabulary`) is stored; a different fence deletes every row and restarts the sweep. If the visual index runs on another model than the matrix, the sweep deletes all tags and stops. Deleting the visual index deletes the tags.

**Privacy.** Nothing leaves the device and no image is decoded for tags. The store is not authority: a reader (TAGS-2) resolves each returned media key through the live grant, as Search does for vectors.

**Policy.** TAGS-0 thresholds, on-by-default tags only, at most 5 per row. Scored rows with no tag are kept, so a re-scan does not score them again.

**Proof.** JVM: `VisualTaggerTest` (thresholds and off-by-default, incremental replace, video moments, 2,000-item bounded backfill with a stable re-scan, revision change and pruning, fence and model invalidation, guard and time budget, hook only on accepted batches). Emulator: `VisualTagBackfillAndroidTest` (real SQLite, the shipped matrix, 2,000 synthetic items, half through the hook and half pre-existing; logs the 250k estimate under `AtlasTags1`).

## Follow-ups

- **TAGS-2** (UI, design gate): a Search "Things" entry with category tiles and tag chips, Dutch and English names from `tags.tsv`. Needs a Design brief under BRAND-0 #628 / Lapis #654 and coordination with COLLECT-1 #576 and BRAND-L5 #658.
- **Vocabulary growth:** licensed or synthetic receipts, handwriting and night scenes; split `text`/`document` into narrower tags; user corrections as feedback rows (Vision rule "corrections teach").
