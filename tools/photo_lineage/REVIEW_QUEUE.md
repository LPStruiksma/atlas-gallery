# Review-only Photo DNA evidence queue

**Scope:** Lane B / issue #83. This is a standalone *offline research preview*, not an Android/V4 UI, duplicate-cleaner, source-exposure certifier, or gallery scanner. It never reads/copies original photos. All suggestions remain unconfirmed.

## Try it on synthetic evidence

After using `pair`, bounded `scan`, or `find --verify`, save the JSON stdout:

```bash
python -m tools.photo_lineage.cli find ./example-query.jpg --db ./private-cache/lineage.sqlite --top-k 15 --verify > ./private-cache/candidates.json
python -m tools.photo_lineage.review_queue ./private-cache/candidates.json --output ./private-cache/review-001.html
```

Open the new HTML **locally**. Each expandable card shows the two ephemeral photo labels, relative geometric evidence, and a manual question: **same original exposure**, **independent shots of same scene**, or **uncertain**. Cards are sorted to surface strong geometric *suggestions* first. A geometric `candidate` is **never a probability or proof**. Crops, rotations, recoloring and AI enhancements can all make matches worth looking at, but distinct burst frames and static camera scenes can be visually almost identical. No transitive closure: A↔B and B↔C do not establish A↔C. No automatic file operations, photo clusters, identity links or deletions.

By default, paths are **not embedded in the HTML** (only report-local Photo 0001, etc.). This limits accidental exposure when sharing a research screenshot, at the cost of being unable to find the original from the HTML alone. For an explicitly **private**, locally stored human audit, opt into `--show-names` to include the complete original paths. Paths and the incoming JSON may be sensitive. Never publish path-revealing reports. Reports contain no thumbnails, image contents, scripts, external fonts, tracking resources or network requests. Source JSON is read without following its final symlink and capped at 2 MB / 5,000 evidence edges. Existing HTML is never overwritten: new files are created with restrictive owner-only permissions. Choose a fresh filename for each iteration.

### Input assumptions and limitations

- Input must be output directly from `cli pair`, `cli scan`, or `cli find --verify`. The report generator does **not** verify the input evidence cryptographically, nor rerun the image matcher; untrusted/fabricated input could claim an arbitrary geometric verdict. Full source images are not read.
- `unknown` is abstention, **not** a negative match. Missing shortlist pairs are not shown and remain unknown. Retrieval scores alone are not accepted as lineage evidence.
- The optional HTML contains no interactive identity approvals or persistent review state; decisions must remain outside this research prototype until an explicitly designed and audited confirmation flow exists.
- Safeguards protect the final symlink and use private no-overwrite file creation, but do not constitute a sandbox against a malicious concurrent actor controlling the parent directories. Store reports in an owner-controlled private directory.
- Use individually rights-cleared independent camera captures for genuine field precision/recall estimates; current synthetic results are **not** calibrated. Do not enable automated grouping/deletion.

Run focused tests with `python -m unittest tools.photo_lineage.tests.test_review_queue -v` or the dedicated synthetic workflow gate. Python standard library only; no media, model weights, cloud uploads, or Android integration.

## Optional nontransitive investigation neighborhoods (19 October 2026)

To prioritize human inspection without prematurely merging exposures, build a **read-only pair-evidence graph** from the same bounded `pair`, `scan`, or `find --verify` JSON:

```bash
python -m tools.photo_lineage.investigation ./private-cache/candidates.json > ./private-cache/review-neighborhoods.json
```

Every connected component is called an **investigation neighborhood**, explicitly **not a photographic family**. It lists only the direct `candidate`/`review` edges actually supplied by the matcher. If A↔B and B↔C were suggested but A↔C was never assessed, the output counts that missing pair rather than inventing a connection. If A↔C was explicitly tested but produced `unknown`, that is reported separately from an untested pair. Unknown observations never connect otherwise separate investigation neighborhoods; unknown means abstention, not a negative certificate.

The graph uses iterative depth-first bridge detection, surfacing **bridge edges** whose removal would split a neighborhood. These are weak evidence-network links worth independently checking before considering anything family-like. A complete graph with no missing pairs or bridges **still does not prove identical source exposure**: a static scene photographed twice may produce many convincing geometric matches. `candidate` is not a probability; human source-exposure confirmation is always required.

By default, only report-local ephemeral labels appear, no image paths, thumbnails or visual media. An opt-in `--show-names` includes full original paths in stdout and should **never be shared**. Input uses the same 2MB and 5000-edge safety bounds as the existing review queue, and no file/photo/database modifications, browser requests, identity assignments, transitive deduplication, merges or deletions occur. A large neighborhood can reflect an entire repeating architectural scene; connectivity is *not* a safe cleanup instruction. Use this as a prioritization and quality-audit aid, not as a live Android feature.


## Optional private visual contact sheet (Lane B, October 21)

The default review queue deliberately contains **no photographs** and remains the safest format for sharing evidence. For a private, human-usable visual comparison of the *same existing* `pair`, `scan`, or `find --verify` evidence, opt in to:

```bash
python -m tools.photo_lineage.review_contact_sheet ./private-cache/candidates.json --output ./private-cache/contact-001.html
# Only when you explicitly need full filesystem paths in the report:
python -m tools.photo_lineage.review_contact_sheet ./private-cache/candidates.json --output ./private-cache/contact-with-names.html --show-names
```

Each geometric evidence edge is rendered as a **side-by-side pair of actual 320-pixel-or-smaller JPEG previews**. You can visually distinguish some crops/rotations from similar scenes, but even these visual pairs **cannot establish same camera exposure automatically**. Each pair stays independent; no transitive photo families or delete decisions are created. The algorithm/reason and bounded measurements remain visible. Missing/unsafe sources show a placeholder; if *every* image is unavailable the command refuses to write a useless report. The report itself is deterministic for the same input bytes and photo contents; no persistent review decisions are recorded.

**Important privacy tradeoff:** the opt-in output contains real image pixels (small JPEGs), unlike the default redacted evidence queue. **Treat the whole HTML as sensitive private photographs**: never post to GitHub, share, upload, sync publicly, or send as ordinary diagnostic logs. EXIF/IPTC metadata is not saved in the new JPEGs, and paths are hidden by default, but subjects in pixels are still identifiable. There are no scripts, network fetches or external URLs. A restrictive no-network content-security policy is emitted. New HTML uses private 0600, create-only output; existing files are never overwritten. Keep the output outside the original photo folders when practical.

Safety/work bounds: **maximum 32 edges / 64 unique source images**; each source must be a regular non-symlink image reached without following *any* path component, at most 100 MiB compressed / 95 MP decoded dimensions. Individual embedded JPEGs are capped at 64 KiB and total HTML at 9 MiB. Source descriptor identity/size/mtime/ctime must remain stable across read. Unsupported formats or inaccessible images abstain. No originals are ever moved/deleted/written. This is a **Python/Pillow local prototype**, not production Android decoding, a cross-process hostile-directory sandbox, or a 150k-media gallery scanner. Run `python -m unittest tools.photo_lineage.tests.test_review_contact_sheet -v` (synthetic only).

**Future interface, not a release requirement:** `render_contact_sheet(payload, show_names=False, max_edges=32) -> (html, stats)` consumes the existing normalized evidence contract; `stats.preview_available` / `stats.preview_unavailable` explicitly distinguish inaccessible images. A specialist gallery review UI could reuse this *paired candidate + review-required + unavailable* vocabulary, but permission-aware native thumbnail decoding and UI integration remain other lanes' responsibility, after the Android P0 release. Do not embed sensitive HTML in CI artifacts.
