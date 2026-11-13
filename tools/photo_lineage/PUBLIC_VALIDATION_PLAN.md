# Public photographic validation plan — source licensing and scene-level holdout

**Lane B / issue #83, 2026-10-16. Research plan only.** No external photographs are downloaded or committed by this branch. No private phone media, cloud upload, identity processing, or native app integrations are authorized by this plan.

## Core ground-truth distinction

For a photograph \`A\`, compute edited variants \`A1\`, \`A2\`, etc. *derived from the exact same original bytes/image*. These are positives for a common source exposure. Separately, obtain \`B\` and \`C\` as **independent camera captures** of the same scene (even if the camera position, foreground, lighting and time hardly change). These are negatives for *same exposure*, although they can legitimately be *same scene* relations.

A third cohort consists of entirely different scenes, repeated architecture, texts, watermarks and unrelated pictures.

Reporting must have two different propositions:

1. **Image-content relationship** (a scene or feature overlap, useful for review).
2. **Identical originating exposure** (much stronger claim). ATLAS cannot currently certify this; never treat a \`candidate\` as duplicate removal permission.

Keep \`candidate\`, \`review\`, \`unknown\` distinct and report both retrieval rank and post-geometric decision for each condition.

## Candidate publicly obtainable datasets

| Source | Useful for | Rights check | Decision |
|---|---|---|---|
| [Google HDR+ burst dataset](https://hdrplusdata.org/dataset.html) | Real, short-interval 2–10 frame Android-camera bursts; potential same-scene **different shutter** hard negatives after human source review. | Publisher states CC BY-SA; contains images of authors' friends and family, so examine each chosen scene's subject/privacy suitability. 153-burst curated subset is 37 GiB. Raw DNG needs an audited reader; raw folder names alone do not prove shutter truth. | **Metadata-only admission tool** (`hdrplus_burst_admission.py`); no pixels downloaded/decoded; use a few independently curated non-personal bursts only after rights/ground-truth review. |
| [Natural Image Noise Dataset (NIND), Wikimedia Commons](https://commons.wikimedia.org/wiki/Natural_Image_Noise_Dataset) | Same physical scene shot at **different ISO values**, i.e., real independent captures; test difficult same-scene negatives as well as generate within-exposure positive edits | **Verify each selected Wikimedia file page.** Example [NIND shells ISO5000](https://commons.wikimedia.org/wiki/File:NIND_shells_ISO5000.jpg) is marked CC BY 4.0. The Commons category itself is **not** a universal license for every file. Preserve file-specific author, license link and attribution. | **First candidate**, selected small subset only after per-file license confirmation |
| [HPatches dataset and full sequences](https://github.com/hpatches/hpatches-dataset) | Known scene correspondences with viewpoint and illumination changes. Useful for *same scene ≠ same exposure* tests; ground-truth homographies available. | Repository contains an MIT file describing **software**, but full sequences derive from earlier image collections; README requires crediting original sources. Confirm upstream image provenance and redistribution terms before using or bundling any pictures. Full sequences ~1.3GB, patch archive ~4.2GB. | **Conditional research-only**; do not automatically download or vendor |
| [Oxford VGG affine-region image sets](https://www.robots.ox.ac.uk/~vgg/research/affine/) | Small sets for rotation, scale, viewpoint, blur, illumination and JPEG changes, with homographies | Dataset page does not establish sufficient reuse rights for bundling all original pictures into ATLAS; investigate per-image provenance. | **Do not vendor** absent explicit rights |
| [Google Landmarks v2](https://github.com/cvdfoundation/google-landmark) | Large-scale visually related but separately photographed landmark negatives | Annotations are CC BY 4.0, **individual images have different license statuses** and providers disclaim automatic image-rights clearance. | **Out of scope for first gated cohort** (large and rights-intensive) |
| [BurstSR real-world data](https://github.com/goutamgmb/NTIRE21_BURSTSR) | Independent frames of real handheld smartphone bursts | License/rights need independent validation; nearby example implementation bears CC BY-NC-SA / research-only conditions. A code license does not necessarily license the underlying photos. | **Do not redistribute/use commercially until clarified** |

Sources reviewed on 2026-10-16. This is an engineering source assessment, not a legal determination.

## Practical opt-in evaluation protocol (offline)

1. **Individual rights/provenance manifest:** for each selected image record origin URL, photographer/credit, exact license with URL, consent/redistribution constraints, captured-scene ID, exposure ID, and local content checksum. For a pair, only assert "same exposure" if one file was algorithmically derived from a known seed file or has independent capture proof. Do **not** infer this from filename similarity, EXIF closeness or visual similarity.
2. **No external data bundled in the repository** until individual media licenses and intended redistribution use are cleared. The experiment runner only reads explicitly staged files from a separate local sandbox. Use small samples; don't import multi-gigabyte archives automatically.
3. **Split by scene and source exposure**, never randomly by cropped variant: if one scene is in evaluation, none of its frames can appear in threshold development. Distinct photographers/cameras/scene categories should appear in the final heldout cohort.
4. **Evaluate both stages separately:** index recall@1/5/20, missed true-source retrieval, geometric verifier candidate/review/unknown rates, and false-strong-source-candidate per hard-negative condition. Record crop fraction, arbitrary rotation angle, color transfer, JPEG quality, subject-motion fraction, scale, timing, cache MB/image and CPU/RAM.
5. **No privacy shortcuts:** strip unnecessary GPS/identifiable metadata from any generated summary; do not publish thumbnails, faces, paths or photos without confirmed rights. Any personal-phone evaluation requires explicit owner opt-in. Test only local copies in a separate, disposable sandbox, preserve originals and their hashes.
6. **Go/no-go:** never enable unsupervised grouping or deletion based on an apparently high synthetic or public benchmark score. Require a reviewed reference set, separately calibrated thresholds, explicit unknown handling, and a human confirmation path before native integration.

## Critical interpretation

NIND and HPatches are **not same-exposure-positive datasets by themselves**: their different images are *different captures of the same scene*, precisely the hard negatives missing from the earlier synthetic benchmark. Positive edits must be derived from **one** verified image, while other frames of that scene serve as independent-capture negatives. This distinction prevents the evaluation from rewarding a matcher that simply recognizes background content.

A single licensed Middlebury stereo scene (two rectified distinct viewpoints) has been examined in a locally transcribed full-matcher reproduction, documented in `MIDDLEBURY_STEREO_SMOKE.md`. This is neither a real same-camera shutter sequence nor an official source-module test. The next desired independent-shutter cohort remains unverified; never present published dataset descriptions or correlated edits as measured ATLAS accuracy.
