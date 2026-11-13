# Offline, provenance-pinned independent-exposure evaluation

Lane B | issue #83 | research-only. **No automatic source-exposure certification, grouping, deletion, external uploads or native-app integration.** This command does not download or rewrite pictures. It reads a manually curated local sandbox and prints aggregates; keep any private paths/source manifests out of published PRs.

## Why

Two independent shutter captures can depict exactly the same scene; two different edits can derive from the exact same shutter capture. Geometry can recognize shared content but not prove capture identity. This evaluation measures these two cases separately and holds out entire *scenes*, never random edited variants, so exposure siblings cannot leak into development.

## Manifest schema (save locally outside Git)

```json
{
  "version": 2,
  "scenes": [
    {
      "id": "commons_shells_scene",
      "scene_group": "nind_shells_same_setup",
      "split": "holdout",
      "captures": [
        {
          "id": "shells_iso200",
          "file": "shells_iso200.jpg",
          "sha256": "REPLACE_WITH_ACTUAL_64_LOWERCASE_HEX_DIGEST",
          "provenance": "independent_shutter",
          "provenance_note": "Photographer describes independent exposures with different ISO settings.",
          "author": "Trougnouf (Benoit Brummer)",
          "license": "CC-BY-4.0",
          "license_url": "https://creativecommons.org/licenses/by/4.0/",
          "source_url": "https://commons.wikimedia.org/wiki/File:NIND_shells_ISO200.jpg"
        },
        {
          "id": "shells_iso3200",
          "file": "shells_iso3200.jpg",
          "sha256": "REPLACE_WITH_ACTUAL_64_LOWERCASE_HEX_DIGEST",
          "provenance": "independent_shutter",
          "provenance_note": "Distinct original photo taken of the same shells at another ISO setting.",
          "author": "Trougnouf (Benoit Brummer)",
          "license": "CC-BY-4.0",
          "license_url": "https://creativecommons.org/licenses/by/4.0/",
          "source_url": "https://commons.wikimedia.org/wiki/File:NIND_shells_ISO3200.jpg"
        }
      ]
    }
  ]
}
```

**This illustrative JSON is not runnable until both actual SHA-256s are supplied and selected photographs are separately staged.** The **v2** tool accepts 1–20 scene records, 2–8 independently captured, SHA-256-distinct files per scene, one canonical `scene_group` per scene, and one of `development`/`holdout` per entire scene. Scenes and capture IDs must be unique, and file paths must remain below the specified image root with no symlink components. Metadata is *curator attestation*: a URL, license label or note is not machine validation of legal rights or photographic independence. Check each original Commons file description and its history. Never assume a whole category shares the same license. Real datasets can contain people/location information even if the initial examples do not.

## Run locally

```bash
python -m pip install -r tools/photo_lineage/requirements.txt
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.heldout_eval --root /separate/public-research-images --manifest /separate/provenance.json --split holdout --algorithm orb
python -m tools.photo_lineage.heldout_eval --root /separate/public-research-images --manifest /separate/provenance.json --split development --algorithm sift
```

For every staged exposure, the evaluator confirms its pinned SHA-256 **before and after decoding**, and then generates in-memory edits (resize, gamma, quarter-area crop, 38° rotation+crop, JPEG re-encode). The variants are positive same-exposure tests by construction. Other curated captures of the same scene are negative *source-exposure* tests, and one cross-scene comparison per scene pair is a negative *shared-scene* test. It summarizes `candidate`, `review`, and `unknown` counts by condition, median per-pair CPU time and a **permanent `unsafe_to_certify_exposure_identity: true` flag**. Strong candidates on independently shot same-scene cases are counted for review, not suppressed. An absent candidate is not proof of unrelated exposure. No paths or photos appear in aggregate JSON.

This is a bounded exhaustive *evaluator*, not a scalable library index. Scene matching depends on the curation; filenames, license links and metadata do not cryptographically attest shutter events. A checksum confirms the exact staged bytes, not the copyright license or existence of a separate photographic capture. Scene separation cannot detect duplicate scene aliases across curators and requires independent manual review. Do not tune thresholds against the heldout set. Do not use these scores to automatically delete or merge photographs.

## Actual external evaluation status

The instrumented synthetic fixture passed four new tests plus all previous Lane B tests (**30/30** on 2026-10-16 Linux Python/OpenCV environment). This **is not a completed independent-exposure photographic benchmark**. No NIND files have been downloaded, bundled, or evaluated in this PR. The next experiment requires only **individually verified, properly attributed public images** in a disposable local sandbox; these images and any personal file paths remain outside Git and CI.


## Exact-head integration gate (2026-10-17)

The dedicated `photo-lineage-safety.yml` workflow now checks this Python-only PR. A green full synthetic unittest suite plus `ci_gate.py` at this **exact** revision is required before merging. That result validates the instrumented protocol and synthetic invariants only: no genuine independently captured NIND photographs have been run or authorized as ground truth in CI, and matcher candidates must remain review-only.

## Scene-correlated uncertainty audit (Lane B follow-up, 18 Oct 2026)

The evaluator now reports `scene_exposure_risk` beside original per-condition pair counts. Within a curated scene with 2–8 independently labeled shutters there are 1–28 correlated pair comparisons. Counting all 28 as 28 independent confidence samples would dramatically underestimate uncertainty if the same scene, capture setup or texture produces many related false offers. Each scene therefore counts at most **one** event for the safety-oriented endpoint: did *any* pair of its independently captured frames get a strong geometric `candidate`? The report exposes number of scenes, affected scenes, separate weak-review and abstention counts, correlated-pair diagnostics, and the **two-sided Wilson 95% upper endpoint** on the scene-level fraction. Even **0 strong candidates among 20 tested scenes** gives an upper endpoint of about **0.161**, not zero.

This is **not a deployment false-match confidence bound** unless different scenes are statistically independent and representative, which manually curated public samples cannot guarantee. Scenes of the same underlying subject, location, camera setup or curation channel may remain correlated; source labels cannot independently certify separate shutters. An `unknown` on a hard negative is **not proof** that the matcher can confidently distinguish exposures, and a strong geometry hit is still review-only. Report both pair-level descriptive totals and scene-level uncertainty, and hold out whole scene groups from tuning. No real NIND frames were staged for these tests.


## Manifest v2: fail closed on obvious scene/exposure leakage (18 October 2026)

**Breaking, safety-motivated protocol change:** v1 manifests are rejected with an explicit migration error. Add `"scene_group": "stable_curated_scene_token"` to **every** scene and change the manifest version to `2`. The group is a manually curated identity of the *underlying scene/camera setup*, not an image content hash. Treat any aliases or alternate names for the same underlying scene as **one group**. The evaluator refuses duplicate groups across scene records, including across `development` and `holdout`; consolidate those records into a single scene with 2–8 independent-shutter captures, or exclude the ambiguous record. It cannot detect honestly mislabeled or unknown aliases, so curatorial review remains essential.

Every captured exposure must have its own **canonical HTTPS source *page*** URL. The evaluator refuses reused URLs across **all splits**, even when the files, SHA-256 digests or curatorial labels are different. Hostname case and equivalent percent-encoded page paths normalize to one source; unstable links carrying auth, custom ports, queries or fragments are rejected rather than ambiguously identifying a file page. Use independently verified permanent individual Commons `File:` description pages (or analogous source-page records) instead of CDN download URLs, mutable search results or tracking links. This checks metadata consistency **only**; a distinct URL can still depict an identical original shutter event, and URL validity does not prove license or capture independence. Each relative file reference must also be unique across both partitions. All of these inexpensive checks run before image decoding, even when evaluating only one split. No web access, original edits, private image uploads or automatic decisions are involved.

**Migration example:** existing `version: 1` manifests require an explicit provenance review; do not merely generate random `scene_group` values to silence collisions. Scene/photographer/camera setups shared across partition boundaries can invalidate nominal heldout independence. A valid split must be decided by a curator **before** tuning and kept immutable until final evaluation. Dataset-page metadata and different ISO filenames cannot certify physically independent shutters.

New pure-fiction regression tests exercise cross-partition scene aliasing, repeated source pages and percent-encoding aliases, duplicate file references, malformed URL/path records, and v1/boolean-version rejection. This makes common curator mistakes fail early; it does **not** justify upgrading the existing Wilson upper bound into a real-world guarantee without representative camera evidence.


## Complete-edit retention by capture and scene (Lane B, 18 October 2026)

The read-only heldout evaluator now includes an additive `edit_retention` summary, complementing existing per-transform counts and independent-exposure false-offer rates. A high average across resized/gamma/crop/38°-rotated-crop/JPEG-reencode variants can hide that **one particular original photograph consistently fails on every rotation**. Each original exposure now must yield exactly one `candidate`, `review` or `unknown` for **all five** edit conditions. `candidate` and `review` count as *reviewable candidates*, **not as confirmed same exposure**; `unknown` counts as a missed review opportunity, not a proven different image.

The summary records (a) exposures recoverable across *all five* edits, (b) scenes where *every* included exposure is recoverable across all edits, (c) number of scenes with **any** missed variant and a conservative two-sided Wilson 95% upper endpoint at the **scene** unit, and (d) per-transform scene failures versus raw correlated exposure counts. Repeated variants and captures of one physical scene do **not** create extra independent confidence samples. Even 0 failure scenes out of 20 yield an upper endpoint of roughly 16.1%; do not declare photo retrieval reliable based on small, procedural synthetic fixtures.

This evaluates only the existing **geometric pair verifier** on the specified five transformations, **not** the preceding approximate shortlist's recall@K, not unknown transformations (severe AI retouching, color restoration, tiny crops, lens perspective), and not all real photos. Only published, verified independent camera captures in representative scene-heldout cohorts can support broader photo-family performance claims. No actual photos, automatic group/merge/delete capability, cloud processing or native UI are introduced.

## Optional exact decoded-pixel leakage audit (19 October 2026)

Manifest v2's globally unique SHA-256 checksums and curated `scene_group`/`source_url` declarations can still miss a **lossless transcode** of the same pixels: one image saved as PNG and BMP has different file bytes and potentially different URLs, but should not automatically count as two independent captures.

Before trusting a benchmark, run:

```bash
python -m tools.photo_lineage.decoded_alias_audit --manifest ./curated-v2.json --root ./public-sandbox/
python -m tools.photo_lineage.heldout_eval --manifest ./curated-v2.json --root ./public-sandbox/ --split holdout --algorithm orb --pixel-audit
```

The **opt-in**, offline verifier checks *every declared capture in both development and holdout*, validates SHA-pinned original bytes before and after decoding, applies EXIF orientation, normalizes full-resolution pixels to RGBA, and hashes dimensions/pixels in 64-row strips. It rejects symlinks and inputs above 95 megapixels, never rewrites originals, and produces only aggregate counts (no image paths). If identical decoded pixels occur among supposed independent captures, the standalone command exits nonzero and `heldout_eval --pixel-audit` refuses benchmark output. This is **ambiguous provenance**, not proof of an identical shutter; independently captured static frames can theoretically be pixel-identical.

`--pixel-audit` is **not enabled by default** because examining both partitions adds significant disk reads, decoding, memory and CPU even when only one split is being evaluated. Without the preflight the report explicitly says `all_splits_pixel_audit: not_run`; on a clean opt-in pass it says `passed_exact_only`—**not** exposure independence proven. Pillow may decode full source frames internally: strips bound additional RGBA byte arrays, not total decoder peak memory. This is not evidence of suitability for a 150k-photo S24+ gallery.

The check catches **exact decoded-pixel collisions only**; lossy JPEG re-encodes, crops, resizing, small photometric edits and stronger manipulations may evade it. Do **not** use permissive pHash thresholds to automatically reject visually similar samples: separate shutter captures of an almost motionless scene are the *hard negatives* we intentionally need. Review ambiguous captures and their camera provenance manually.

No source-exposure certification, automatic family grouping, identity merge, original photo deletion, cloud upload or native Android integration is authorized.


## Fail-closed JSON provenance and anchored file reads (21 October 2026)

A curated exposure manifest must be unambiguous **as serialized**, not merely after a JSON parser has selected the last occurrence of a duplicated key. The offline evaluator now rejects repeated JSON properties at **every nesting level**, including duplicated `version`, `sha256`, `file`, `source_url` or license declarations. It also refuses JSON `NaN` / `Infinity` extensions, UTF-8 errors and a manifest changing during a bounded descriptor-based read. This closes a last-wins provenance loophole without changing the v2 schema or any source-exposure verdict threshold.

Manifest reads and each source SHA-256 pass now open from `/` through directory descriptors with `O_NOFOLLOW` on **every ancestor and leaf**, and require regular files with stable metadata before/after the pass. An earlier `is_symlink()` / `is_file()` check alone was vulnerable to a rename or symlink substitution before `open()`; the matcher already had this stronger primitive. This is POSIX-only protection against common path substitution, **not** a filesystem snapshot, an untrusted decoder sandbox, or proof that a malicious in-place writer cannot race a read while restoring its original metadata. All source images remain read-only and no cloud/network request is added.

The deterministic fictional regression suite covers duplicated top-level and nested properties (including metadata in an *unselected* split), nonfinite values, an over-budget manifest, and substituted parent/leaf symlinks. These guards improve **integrity of future research evidence**, not source-exposure identity accuracy. Public independent-exposure data and exact-head CI remain separate proof requirements; without real held-out images, do not claim empirical precision or recall.


## Pixel-audit descriptor integrity follow-up (21 October 2026)

The optional exact-decoded-pixel alias preflight formerly verified SHA-256 on a safely opened capture but then decoded using `Image.open(path)`. Because this was a **separate pathname lookup**, an attacker or concurrent file-reorganizer could replace a parent directory or leaf with a symlink between integrity check and decode. The pixel audit now opens **every path component** with POSIX `O_NOFOLLOW` and passes one anchored regular-file descriptor to Pillow for the entire EXIF-oriented RGBA digest operation, checking device/inode/size/mtime/ctime before and after decoding. Direct synthetic tests cover preexisting parent/leaf symlinks and an in-place write while decoding. The evaluator still checks the pinned source SHA before and after the audit. This is a fail-closed race reduction, not a guarantee against malicious in-place writes with forged metadata or a sandbox around image codecs.

As always, `no_exact_pixel_collisions_observed` does not establish distinct shutters, and the optional audit is still not a native-gallery, Termux or physical S24+ performance claim.
