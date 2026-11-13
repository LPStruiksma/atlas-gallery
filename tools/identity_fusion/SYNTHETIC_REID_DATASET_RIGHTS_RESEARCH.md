# Candidate synthetic person-ReID evidence — source/rights gate (21 October 2026)

**Status: NO EXTERNAL IMAGE DATASET APPROVED OR DOWNLOADED.**
This is source-based due diligence for a *possible future* reproducible,
local-first V5 rear/partial/Unknown evaluation, not approval of any image
archive, 3D character pack or pretrained model checkpoint. We have
inspected official repositories' **public text metadata only**.

## Decision matrix — distinguish repository software licenses from image rights

Every repository, source commit and LICENSE/README Git object below was
retrieved from the upstream public GitHub repository, not inferred from
unofficial mirrors or a paper's generic code-license badge. A Git blob
is a source-byte fingerprint, **not** a publisher signature or legal
grant for remote Google/Baidu Drive assets.

| Candidate and purpose | Publisher-observed evidence | Rights and quality caveats | ATLAS decision |
| --- | --- | --- | --- |
| **PersonX: controlled viewpoint ablation** | [Source commit `9dc31bac`](https://github.com/sxzrt/Instructions-of-the-PersonX-dataset/tree/9dc31bacb42e2670bce7feb5c7ca025f7bcfa794), [README blob `17cd4801`](https://github.com/sxzrt/Instructions-of-the-PersonX-dataset/blob/9dc31bacb42e2670bce7feb5c7ca025f7bcfa794/README.md), [MIT LICENCE blob `f49ede00`](https://github.com/sxzrt/Instructions-of-the-PersonX-dataset/blob/9dc31bacb42e2670bce7feb5c7ca025f7bcfa794/LICENCE). README describes **1,266** virtual characters × **36** nominal viewpoints at **10°** increments under six background types | MIT text grants rights to specified **software and documentation**, not explicitly independently authenticated copyright/redistribution rights for remote image archives, 3D character meshes, Unity Asset Store backgrounds or animations. View-index to actual *front/back pose* mapping and camera occlusion distribution must be manually validated, not guessed | **First candidate for authorization inquiry**, no image use yet |
| **RandPerson: scene/camera/occlusion robustness** | [Source commit `def54926`](https://github.com/VideoObjectSearch/RandPerson/tree/def54926428bedcd2ecd49521b3eec33b23a5a37), [README blob `8a4ad39c`](https://github.com/VideoObjectSearch/RandPerson/blob/def54926428bedcd2ecd49521b3eec33b23a5a37/README.md), [Apache-2.0 LICENSE blob `261eeb9e`](https://github.com/VideoObjectSearch/RandPerson/blob/def54926428bedcd2ecd49521b3eec33b23a5a37/LICENSE). README describes **8,000** generated identities, **1,801,816** total images, multiple people/cameras, various occlusions/poses/lighting; smaller **132,145**-image experimental archive advertised | Apache repository license does not itself independently clear external Google/Baidu Drive dataset copyright, MakeHuman/Unity assets, character models and downstream commercial derivative use. Images and videos are large; original filename `scene/camera/frame` encodes burst/session dependencies | **Second candidate for authorization inquiry**, no image use yet |
| **SynPerson: illumination shift** | [Commit `c38250fa`](https://github.com/JeremyXSC/SynPerson/tree/c38250faef6d1ad6a35e0629c8ad008e9c848d8b), [README blob `de76ea65`](https://github.com/JeremyXSC/SynPerson/blob/c38250faef6d1ad6a35e0629c8ad008e9c848d8b/README.md), [GPLv3 LICENSE blob `f288702d`](https://github.com/JeremyXSC/SynPerson/blob/c38250faef6d1ad6a35e0629c8ad008e9c848d8b/LICENSE). README specifically says dataset and data-derived work products are **non-commercial only** | GPLv3 *code* license must not be misread as overriding the explicitly non-commercial *dataset* restriction; commercial products/retraining/derivative distributions are not cleared | **Exclude from commercial ATLAS data study** pending explicit separately authorized rights |
| **ClonedPerson: clothing/generalization** | [Commit `0eb8d2d4`](https://github.com/yanan-wang-cs/clonedperson/tree/0eb8d2d4fc9ee594d9c7567103ff0a4b3dc1ebd3), [README blob `74c3f62d`](https://github.com/yanan-wang-cs/clonedperson/blob/0eb8d2d4fc9ee594d9c7567103ff0a4b3dc1ebd3/README.md). 5,621 virtual identities / 887,766 images are advertised, with clothing **cloned from real-world person photographs** | No `LICENSE` was found at the root at inspected commit. Remote asset redistribution/training rights unknown; a synthetic render **can still preserve recognizable traits from real people**, raising copyright, consent, provenance and downstream personal-data concerns | **Do not ingest or redistribute** absent explicit verified original-photo/derived-outfit rights |
| **WePerson: extreme weather/lighting** | [Commit `e76d8888`](https://github.com/lihe404/WePerson/tree/e76d888870d7e9a3fafbca8bf1ab75cd94833151), [MIT LICENSE blob `6d671e26`](https://github.com/lihe404/WePerson/blob/e76d888870d7e9a3fafbca8bf1ab75cd94833151/LICENSE), [README blob `bc6dd728`](https://github.com/lihe404/WePerson/blob/e76d888870d7e9a3fafbca8bf1ab75cd94833151/README.md). README describes **1,500** synthetic characters, ~**4 million** images, **40 viewpoints/scene**, seven weather and seven lighting types | Built from **Grand Theft Auto V game assets** with script hooks. The MIT code license does **not** clear Rockstar game assets, virtual likenesses, underlying copyrighted scenes or dataset commercial use/redistribution | **Exclude from immediate pilot** until genuine game-asset and image rights established |

These are five different research cases, not five legally cleared
datasets. **Synthetic does not automatically mean consent-safe,
copyright-free, representative of family photographs, or usable by a
commercial gallery.** Parent pipeline licenses (Unity, MakeHuman,
game engine, asset store), image licenses and rights to distribute
derived embeddings should be documented *separately*. Upstream
README counts are publisher claims, **not** independent audit counts.

## Practical first study after rights clearance: PersonX-style viewpoint stability

Why PersonX is an attractive **inquiry target**, not a ready benchmark:

- Explicit nominal **36-view** capture makes it possible to compare
  candidate identity scores as a subject rotates away from the camera.
  Before defining any `back` label, independently inspect the actual
  angle-to-person orientation and quality on permitted local copies;
  view index 18 cannot automatically be interpreted as 180° rear.
- Use distinct virtual **character IDs** as the subject-level grouping
  unit. All colors/crops/poses/background resamplings of the same
  virtual character are connected for independence accounting; no
  synthetic augmentation counts as a new physical identity.
- Partition the **character identities** across calibration and
  holdout, not just image names or backgrounds. A holdout Unknown
  character must be entirely **unenrolled** from all gallery
  candidates and all model training sets under the frozen protocol.
- Declare front/side/rear/partial/occluded visibility after reviewing
  authorized image samples and bounding-box quality; never backfill
  underperforming conditions out of the test set or retune a floor
  from the final result.
- Keep original preprocessing fixed and reference-backed: portrait
  crop coordinates, BGR/RGB order, 256×128 aspect/resizing/interpolation,
  pixel dynamic range and ONNX encoder-specific normalization.
  Evaluate exact **Unknown false review offers**, wrong-known offers,
  rear-only abstention, confidence-margin/tie behavior, pose-stratum
  counts and candidate-gallery size effects. Use the Lane C frozen
  preregistration and source-connected group audits to avoid leakage.
- A 10° multi-view same-character collection demonstrates **view
  stability**, not robust recognition across decades, changing
  clothing, partial heads or family resemblance. A separate
  consented real-world study with older/newer generations and
  unrelated lookalikes remains essential before any product claim.

For RandPerson, source-connected equivalents should unite every
frame/video/camera/scene of the same virtual character, rather than
treating thousands of frames as independent Unknown observations.

## Admission checklist: every item currently BLOCKED

No candidate moves to an evaluation workspace until **all** relevant
prerequisites are separately evidenced:

1. **Actual downloadable archive identity**: source owner/URL,
   immutable content SHA-256 from independently authenticated
   bytes, version and archive extraction safety controls. A GitHub
   README Git blob hash is NOT a SHA-256 over the remote image archive.
2. **Archive and derived-use permissions**: explicit permission for
   commercial/proprietary **research/evaluation**, reproducibility,
   derivative scores/embeddings and redistribution if ever planned,
   with MakeHuman/Unity/game assets and original image rights handled
   separately. Do not rely on code MIT/Apache by association.
3. **Consent/privacy rights**: confirm source is really fictional,
   not made from identifiable real people or real-person derived
   texture/appearance without necessary rights. No private family
   photographs are uploaded to a benchmark service.
4. **Provenance and independent partitioning**: verified source IDs,
   camera/view/environment maps, group all duplicates and visual
   variants; precommit dataset subset and Unknown-person enrollment
   lists before seeing holdout scores. Publish source counts, missing
   data and group collision decisions as aggregate-only metadata.
5. **Model checkpoint rights and fidelity**: separately authenticated
   exact ONNX weights, training-data rights, preprocessing/extraction
   parity and explicit reviewed Unknown-identity abstention; a model
   trained/evaluated on overlapping virtual characters is not an
   independent holdout.
6. **Device evidence**: once owner-authorized, measure actual S24+
   p95 inference/crop cost, memory, thermal and battery on entirely
   local media, without writing to or risking the existing People DB.
   Until then production readiness and automatic linking are **NO**.

No command in this note downloads assets, invokes pretrained inference,
opens a private photograph or changes the installed Galaxy app.
Research summaries are not legal opinions or warranties; obtain
specific source-owner confirmations and appropriate review before
using another party's media.

## Revisit triggers

**Only** after a specific, publisher-backed image-archive license/
scope clarification (or a genuinely permissive newly discovered
fictional dataset) may the candidate's `no-use` status be reviewed.
The most efficient next step is to seek direct permission for a
**small PersonX-style angle-balanced sample** with camera/character/
scene metadata, suitable for local *evaluation only* and no asset
redistribution, then preregister the exact fixed holdout and verify
that the data are legally usable. An institutional/public model
license does not replace this check.
