# People bench: real-face numbers for grouping

Measures the shipped face pair (YuNet 2023mar + SFace 2021dec int8) and the app's own
grouping code on real, licensed photos. Results and method: `docs/people/PEOPLE_BENCH.md`.

Nothing here runs in CI or ships in the app, except the generated
`SFaceMetricData.kt` (averages over many people, see `fit_metric.py`).
Photos, crops, vectors and labels stay in a cache outside the repository; scripts print
aggregates only. No owner media is involved.

## What you need

- Python 3.11+ with `numpy`, `onnxruntime`, `opencv-python-headless`, `pillow`, `scipy`.
- Network access to `storage.googleapis.com` and `open-images-dataset.s3.amazonaws.com`.
- A JDK and `kotlinc` for the JVM bench.
- For recall numbers only: a stronger reference face model in ONNX form with the ArcFace
  112x112 input (for example InsightFace `w600k_r50`, research licence). It labels the bench
  photos on the bench machine and is never shipped.

## Steps

```bash
C=~/.cache/atlas-people; T=tools/people_bench; OI=https://storage.googleapis.com/openimages
mkdir -p $C/data

# 1. Stranger sets: Open Images validation and test photos with a "Human face" box,
#    fetched with the licence check VS-S2c uses (CC BY 2.0 only).
python3 -I $T/make_manifests.py faces --bbox $C/data/validation-annotations-bbox.csv \
    --out $C/data/val_manifest.csv
python3 -I tools/visual_search_bench/fetch_images.py --manifest $C/data/val_manifest.csv \
    --metadata $C/data/validation-images-with-rotation.csv --split validation \
    --cache $C/images_val --max-side 960 --out $C/data/licensed_val.csv

# 2. Detect, align and embed like the app (960 px decode, YuNet 0.9/0.3, SFace int8, int8 codec).
python3 -I $T/embed_faces.py --images $C/images_val --set $C/data/licensed_val.csv \
    --models app/src/main/assets/models --work $C/val

# 3. Stranger pair statistics (different Flickr authors = different people).
python3 -I $T/impostor_stats.py --part $C/val $C/data/licensed_val.csv \
    $C/data/validation-images-with-rotation.csv --out $C/val/stats.json

# 4. Fit the metric constants on the stranger sets and regenerate the Kotlin data file.
python3 -I $T/fit_metric.py --part $C/val ... --part $C/test ... \
    --kotlin app/src/main/java/nl/atlas/gallery/featurepeople/cluster/SFaceMetricData.kt

# 5. Library sets: the "Human face" photos of 48 Flickr authors (train split), as stand-ins
#    for personal libraries. --offset 48 gives the second, held-out set.
python3 -I $T/make_manifests.py authors --bbox $C/data/train-annotations-bbox.csv \
    --meta $C/data/train-meta.csv --count 48 --offset 0 --out $C/data/auth_manifest.csv
#    Fetch and embed as above with --save-crops, then label with the reference model:
python3 -I $T/silver_embed.py --work $C/auth --model /path/to/reference.onnx
python3 -I $T/silver_labels.py $C/auth $C/data/licensed_auth.csv $C/data/train-meta.csv

# 6. Run the app's grouping code on the vectors and judge the groups.
python3 $T/export_vectors.py $C/auth/vec.bin $C/auth $C/data/licensed_auth.csv $C/data/train-meta.csv
A=app/src/main/java/nl/atlas/gallery
kotlinc $A/featurepeople/cluster $A/featurepersistence/ClusterPublication.kt \
    $A/featureface/NativeFaceIds.kt $A/featureface/FaceDiscoveryContract.kt \
    $T/jvm/PeopleBench.kt -include-runtime -d $C/bench.jar
java -Xmx6g -Ddump=$C/dump -cp $C/bench.jar nl.atlas.gallery.tools.peoplebench.PeopleBenchKt \
    $C/auth/vec.bin conservative gallery measured
python3 -I $T/judge_groups.py $C/dump $C/auth
```

`-Dhypotheses=DIR` with `judge_hypotheses.py` judges the "same person?" questions; `-Dautoadd=DIR` (no policy)
with `judge_autoadd.py` judges adding later faces to named people without asking.
`-Dphase1=DIR` (no policy, `-Dfloors=0.45,0.40` to sweep) with `phase1.py DIR WORK` plays the owner's
Phase 1 path: name each recurring person, let sure faces join, count what stays loose and what is asked.

`PeopleBench.kt` also takes ad-hoc policies (`p:0.45,0.45,1,0.35,3,1`, see its header) for tuning.
