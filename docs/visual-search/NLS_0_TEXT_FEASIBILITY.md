# NLS-0: search by description in the shipped SigLIP 2 space

Unit NLS-0 ([#104](https://github.com/LPStruiksma/atlas-gallery/issues/104)) of goal [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94). Measured 2026-11-14 on main a9ec251, host CPU (4 vCPU x86 cloud VM, ONNX Runtime 1.31.1). Tools: [`tools/visual_search_bench/`](../../tools/visual_search_bench/README.md) (`text_tower.py`, `build_relational.py`, `nls_bench.py`, `run_nls0.sh`). Raw results: [`results/nls0_results_20261114.json`](results/nls0_results_20261114.json); text export sizes, hashes and parity: [`results/nls0_text_export_20261114.json`](results/nls0_text_export_20261114.json).

## Answer

1. **Yes, text queries work against the index ATLAS already builds.** The SigLIP 2 B/16 text tower from the same pinned checkpoint puts English and Dutch descriptions into the shipped int8 image space. On a licensed relational benchmark (33 queries, 4,824 photos), plain cosine against the shipped int8 image vectors gives English P@10 0.48, nDCG@10 0.52, R@50 0.59, against a chance P@10 of 0.04 and a shuffled-query control of 0.03. No re-indexing is needed: the int8 image vectors score the same as fp32 image vectors (AP 0.421 vs 0.420).
2. **The int8 text tower is good enough.** With one layer kept in fp32 (below), int8 text vectors have cosine ≥ 0.996 to fp32 and score the same (AP 0.421 vs 0.420). Quantising the 256k-token embedding table as well gives a **290 MB** graph (from 1,129 MB fp32) with the same retrieval (AP 0.420), 13 ms per query on 4 host threads.
3. **Dutch is weaker but usable**: P@10 0.43 vs 0.48, AP 0.35 vs 0.42, hard-negative error 0.32 vs 0.27. The drop is concentrated in a few words (below), so it calls for query-side fixes first, not a second model and index.
4. **Plain similarity does not understand the hard parts of a description.** One-difference hard negatives outrank positives 27% of the time (EN), and for some kinds the model is at or below chance: depth order ("one far behind the other", 0.73 error: it prefers people side by side), "without people" (0.46–0.57), and man + woman in a church (P@10 0.0). Your concern ("a high similarity score is not sufficient") is confirmed by the numbers.
5. **Cheap on-device re-ranking helps on the parts it addresses.** Re-scoring the top 100 with negation (subtract the negated phrase) and a people count from YuNet + the MediaPipe person detector lifts English nDCG@10 from 0.519 to 0.547 and P@10 from 0.485 to 0.491–0.509. "Without people" queries go from P@10 0.2–0.3 to 0.6, "three people looking out over the sea" from 0.4 to 0.8. Pose (BlazePose standing/sitting) does nothing measurable, and re-embedding person crops makes things worse.

## Recommended NLS-1 design

- **Query path:** Gemma SentencePiece tokenizer (4.2 MB, ASCII-lowercase, EOS sticky, pad to 64) → SigLIP 2 text tower, int8 with int8 embedding table (290 MB asset, 768-d L2-normalised output) → brute-force dot product over the existing `siglip2_b16_224.int8` image and keyframe vectors (0.03 ms per 1k vectors on the host, so 150k is a few ms on the host and tens of ms on the phone, as VS-S0 estimated) → take the top 100 → re-rank.
- **Re-rank v1 (ship):** negation (`without/no/zonder/geen X`: subtract `cos(X)`) and people count (`max(YuNet faces, person detector bodies)` against the count parsed from the query, weight 1 standard deviation of the top-100 cosines). Both use models already in the APK. Leave out pose and crop re-embedding as measured here.
- **Re-rank v2 (next experiments, each with a bar to beat on this benchmark):** per-person attribute probes (embed each detected person crop and score it against "a man"/"a woman"/"a child" text vectors) for gender composition; SigLIP 2 dense patch features for phrase grounding ("in a doorway", "behind"); a depth-order cue from relative body size for "behind/in front of". Promote only what lowers the hard-negative error of its own kind without lowering overall nDCG@10.
- **Dutch:** add a small Dutch query normaliser (synonyms and colloquial verbs, for example `zoenen → kussen`; measure the variants) and test averaging the Dutch vector with a template variant, before considering a second multilingual model, which would need a second image index.
- **Asset size:** +290 MB of APK on top of the 103 MB image tower. Lukas accepted this on 2026-11-14 (project thread message `cmsg_019fpwdUqfs3YPXgjhfPFHRe39GreNXQvdTDtogmtDcsr9`), so NLS-1 ships the int8 + int8-table graph as is. Vocabulary pruning (the embedding table is 197M of the 282M parameters) is no longer needed.
- **Not in scope here:** conversational refinement, person/date/place filters, video timestamps and feedback. These sit on top of the same vector and are NLS-2 work.

## Text tower

The shipped image tower comes from timm, which ports only the image half of the checkpoint. `text_tower.py` ports the text half from big_vision (`models/proj/image_text/text_transformer.py`, `models/vit.py` at the commit pinned in `models.json`): token embedding 256,000 × 768 plus learned positions, 12 pre-LayerNorm blocks (12 heads, MLP 3072, tanh-GELU, LayerNorm ε 1e-6), final norm, the **last** position, a 768 × 768 head and L2 normalisation. Tokenisation copies the SigLIP 2 demo colab: `lower | tok(length=64, model="gemma", bos="no", eos="sticky")`.

| Check | Result |
|---|---|
| PyTorch port vs an independent float64 numpy forward of the checkpoint | min cosine 0.99999994 |
| ONNX fp32 vs numpy | min cosine 0.9999997 |
| Plain dynamic int8 (MatMul) vs fp32 | min 0.857, mean 0.935: **broken** |
| int8, block 0 MLP `fc2` kept fp32 (`text_int8_keep_fp32`) | min 0.996, mean 0.998 (66 benchmark queries) |
| …plus int8 embedding table (`int8_gather`) | min 0.972, mean 0.997 |

As with the image tower in VS-S0, a single MLP output projection has activation outliers that per-tensor int8 cannot represent; probing each block's `fc2` found block 0 (min cosine 0.82 → 0.996 when it stays fp32).

| Variant | Size | ms, 1 thread | ms, 4 threads | EN AP | NL AP |
|---|---|---|---|---|---|
| fp32 | 1,129 MB | 101.9 | 34.3 | 0.420 | 0.349 |
| int8 (table fp32) | 880 MB | 27.0 | 15.9 | 0.421 | 0.350 |
| **int8 + int8 table** | **290 MB** | 25.5 | 12.9 | 0.420 | 0.352 |

Latency is one query, batch 1. The Python tokenizer takes 0.02 ms; on the device it needs a SentencePiece implementation (a Kotlin port, or ONNX Runtime Extensions' tokenizer op), which is NLS-1 work. The checkpoint's logit parameters are t = 4.72 (scale 112.7) and b = −16.77; a per-image probability is `sigmoid(112.7 · cos − 16.77)`, which NLS-1 can use as an absolute "nothing matches" threshold.

**Licence:** weights as VS-S0 (Apache-2.0, big_vision). The Gemma tokenizer file is the one big_vision's own SigLIP 2 and PaliGemma code loads from the same bucket (`gs://big_vision/gemma_tokenizer.model`, pinned by SHA-256 in `models.json`). The big_vision README says everything in big_vision, including models, is Apache-2.0, and `README_siglip2.md` names the Gemma tokenizer as part of the SigLIP 2 release. Review should confirm that this is enough before the tokenizer ships in the APK.

## Benchmark

- **Images:** 4,824 Open Images V7 photos (train 4,250, validation 153, test 421), each with a CC BY 2.0 licence checked against Open Images' per-image metadata when the manifest is built and again when the image is fetched. Only IDs, splits and judgments are committed ([manifest](../../tools/visual_search_bench/data/nls0_manifest.csv), [judgments](../../tools/visual_search_bench/data/nls0_judgments.csv)). No pixels, authors, URLs or embeddings are committed, and nothing comes from the owner's library.
- **Queries:** 33 descriptions, each in English and Dutch ([queries](../../tools/visual_search_bench/data/nls0_queries.json)), covering counts, gender composition, attributes (red dress), actions (kissing, riding, handstand), poses, settings (church, beach, kitchen, snow), light (night), events (wedding, party, concert), viewpoint (from behind), spatial order (beside vs behind) and negation (without people). The Dutch was written by the Dev seat; a native check is welcome. Queries were picked for compositional difficulty only. There is no content-category filtering.
- **Ground truth:** each query is a set of constraints over Open Images' human annotations. These are verified image-level labels (both yes and no), exhaustive person boxes (Person/Man/Woman/Boy/Girl; depictions such as statues excluded, background figures under 1.5% of the frame make an exact count unknown), and face boxes for the from-behind proxy (people present, faces verified absent). For a query, an image is **pos** if every constraint holds, a **hard negative** if exactly one fails and the rest hold (the failed one is its *crux*: two people vs one, church vs another interior, man + woman vs two women, sitting vs standing, beside vs behind), **neg** if at least one fails, and is left out if it cannot be decided. Every judged image is therefore right by construction, and unannotated images neither help nor hurt (condensed lists). 1,093 positive, 3,858 hard-negative and 60,147 negative judgments in total, with 5–133 positives per query.
- **Metrics:** per query, then macro mean: P@10, R@50, nDCG@10 (binary gain), AP, and **hard-negative error** = the share of (positive, hard negative) pairs in which the hard negative scores at least as high (1 − AUC; chance is 0.5). The ranking is over the whole gallery.
- **Dropped owner examples:** "someone doing a cartwheel on grass" (1 licensed positive), "a dog running on grass" (2) and "two people embracing in a dimly lit room" (0) have too few verified joint annotations. The on-device private benchmark (VS-B style) can cover them.

## Results

Shipped int8 image vectors unless noted; English / Dutch.

| Method | P@10 | R@50 | nDCG@10 | AP | HN error | HN in top 10 |
|---|---|---|---|---|---|---|
| Chance / shuffled queries (EN) | 0.043 / 0.033 | 0.12 | 0.03 | 0.06 | 0.52 | 0.09 |
| Cosine, fp32 text + **fp32** image | 0.473 / 0.415 | 0.586 / 0.506 | 0.523 / 0.434 | 0.420 / 0.335 | 0.276 / 0.324 | 0.32 / 0.32 |
| Cosine, fp32 text | 0.473 / 0.421 | 0.593 / 0.508 | 0.518 / 0.450 | 0.420 / 0.349 | 0.274 / 0.315 | 0.31 / 0.31 |
| Cosine, int8 text | 0.485 / 0.427 | 0.592 / 0.502 | 0.519 / 0.455 | 0.421 / 0.350 | 0.273 / 0.318 | 0.29 / 0.30 |
| Cosine, int8 text + int8 table | 0.473 / 0.433 | 0.595 / 0.507 | 0.514 / 0.460 | 0.420 / 0.352 | 0.272 / 0.314 | 0.31 / 0.31 |
| + count re-rank | 0.500 / 0.421 | 0.599 / 0.518 | 0.537 / 0.443 | 0.423 / 0.351 | 0.276 / 0.317 | 0.29 / 0.32 |
| + negation re-rank | **0.509** / **0.449** | 0.595 / 0.503 | **0.550** / **0.476** | **0.428** / 0.354 | 0.271 / 0.317 | **0.27** / 0.28 |
| + pose re-rank | 0.482 / 0.439 | 0.592 / 0.500 | 0.516 / 0.464 | 0.423 / 0.353 | 0.276 / 0.319 | 0.31 / 0.31 |
| + crop re-embedding | 0.455 / 0.415 | 0.590 / 0.509 | 0.501 / 0.443 | 0.414 / 0.346 | 0.276 / 0.319 | 0.32 / 0.31 |
| + all four | 0.491 / 0.446 | 0.596 / 0.517 | 0.547 / 0.474 | 0.420 / 0.357 | 0.280 / 0.317 | 0.30 / 0.28 |

The re-rank rows use int8 text and re-score only the top 100, so they mostly move the head of the list (P@10, nDCG@10, hard negatives in the top 10). The pairwise hard-negative error over the whole list barely changes by construction. Weights were fixed before the run (1 standard deviation of the top-100 cosines per signal); doubling the count weight made things worse (EN nDCG@10 0.518), so it was not tuned further. Two query-parser bugs found in the first run were fixed before the numbers above: the first number in "two people, one behind the other" now wins, and "a woman … beside a man" counts as 2.

**Hard-negative error by the kind of difference** (cosine, int8 text; EN / NL; chance 0.5):

| Crux | Queries | EN | NL |
|---|---|---|---|
| Gender composition (man + woman vs two women) | 6 | 0.23 | 0.24 |
| Object / action / scene label (church vs other, kissing vs not) | 30 | 0.28 | 0.34 |
| Viewpoint (from behind vs facing) | 2 | 0.32 | 0.43 |
| People count (two vs one or three, none vs some) | 12 | 0.34 | 0.39 |
| Spatial order (beside vs one behind the other) | 2 | 0.36 | 0.36 |

**Where it works** (EN P@10 ≥ 0.8): handstand on a beach, snow walking, cooking in a kitchen, bride in a church, cycling on a street, a selfie of two people. **Where it fails:** one person far behind another (P@10 0.1, error 0.73), man + woman inside a church (0.0), man + woman standing side by side (0.0), an empty church or beach (0.2–0.3 before the negation re-rank), two women standing (0.2), a person from behind in a crowd (0.3).

**Dutch:** the English–Dutch query vectors have a mean cosine of 0.88 (min 0.74). Most queries score about the same in both languages, and a few colloquial phrasings collapse: "twee mensen die zoenen" P@10 0.0 vs 0.5 for "two people kissing", "van achteren gezien" 0.3 vs 0.6, "elkaar knuffelen" 0.2 vs 0.4. That pattern points at vocabulary, not at the model's Dutch as a whole. A multilingual alternative (option b) was not benchmarked: Hugging Face is blocked by this environment's network policy (as in VS-S0), and any other model would need its own image index next to the shipped one.

## Caveats

- The labels are Open Images human verifications, which are image-level and not always about the same person as the box (for example "Sitting" may refer to someone other than the woman in the red dress). Read absolute numbers as a lower bound and compare methods with each other.
- Small per-query sets (5–133 positives) make single-query differences noisy; macro means over 33 queries are the numbers to use.
- The from-behind proxy (people present, faces verified absent) and the 1.5% figure threshold are approximations; both are in `build_relational.py`.
- The latency host is a cloud VM, not the S24+.

## Reproduce

`tools/visual_search_bench/run_nls0.sh` (about 20 GB of cache, mostly Open Images train annotations). It verifies the checkpoint and tokenizer hashes, exports the text tower, fetches the licensed images and writes the results JSON. `NLS0_REBUILD=1` rebuilds the manifest and judgments from the public annotations; on 2026-11-14 that run produced the committed files. Offline tests: `python3 -m unittest discover -s tools/visual_search_bench -p 'test_*.py'`.
