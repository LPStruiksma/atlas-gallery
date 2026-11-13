# Photo DNA: offline CC0/public-domain photographic smoke

Lane B / issue #83 — **optional, read-only real photographic-image sanity check**, not same-scene independent-shutter evaluation. No private media, online download, modification, application integration, identity assignment, family merge or deletion.

## What changed and why

Previously we could compare only procedurally generated source images in this environment because direct Wikimedia Commons NIND bytes were inaccessible. The scikit-image package also carries **locally bundled photographs** and documents their rights: `camera` (Lav Varshney, CC0), `astronaut` (NASA, public domain), `chelsea` (Stefan van der Walt, CC0), `clock` (Stefan van der Walt, public domain), `coffee` (Rachel Michetti, CC0). Source notes: [skimage.data official documentation](https://scikit-image.org/docs/stable/api/skimage.data).

These are **five distinct photographs of visibly different scenes**, not independent-shutter pairs of one scene. Their edits can provide limited *real photographic content* checks of crop/rotation/JPEG/gamma/resize behaviors, and their pairwise comparisons are unrelated-scene negatives. They CANNOT test difficult same-scene burst/tripod discrimination, prove real source-exposure precision or represent the actual 150k-photo archive.

## Run without downloading or copying photo bytes

Install the optional `scikit-image` package if needed into an appropriate isolated Linux research virtualenv. Installing packages on an Android/Termux device or the production gallery is **not** required, approved, or recommended for this research tool. With a compatible scikit-image installation whose five **packaged** assets match the fixed pinned hashes:

```bash
python -m tools.photo_lineage.public_domain_smoke --algorithm orb
python -m tools.photo_lineage.public_domain_smoke --algorithm sift
python -m tools.photo_lineage.public_domain_smoke --algorithm akaze
```

The command uses `importlib.util.find_spec('skimage.data')` and accesses only its installed package directory—not its on-demand cache or network downloader. Every allowed filename is hardcoded, length-bounded, checked for symlinks, compared against an independently recorded exact original-file SHA-256, and decoded as bounded PNG. Unexpected package versions, missing files, changes or incorrect hashes **fail closed**. Source bytes are not copied into the repo or modified. The report contains only the five public photo labels, credits, edit-verdict counts, unrelated-scene verdict counts, and explicit scientific caveats; no image arrays, thumbnails or private paths.

The five byte pins were independently read from a locally installed scikit-image **0.26.0** package. Other versions might ship different image encodings or versions; do not silently change the image samples and still compare results. Review licensing and provenance again before changing any pin. `scikit-image` is an **optional research-only** dependency; the main project `requirements.txt` and GitHub synthetic CI remain unaffected.

## Metrics and nonclaims

The smoke produces five deterministic edit types from the *same real source pixels* via the heldout evaluator's `_derived_edits`, and compares each source against four other **different-scene** photos. There are 25 edited-positive comparisons and 10 unrelated-scene negatives total. For each condition, return `candidate`, `review`, `unknown` counts. `candidate` still means **review required**. Count sources with all five derived edits reviewable rather than assuming 25 correlated edits are independent successes. No confidence interval, population accuracy, same-scene shutter false-match rate, actual camera-provenance verification, or S24+ speed/battery/thermal evidence is claimed.

CI tests use generated **fictional** fixture images and Python mocks to enforce source-file immutability, SHA pins, no symlink acceptance, format and input bounds, aggregation and nonclaims. They do not require installing scikit-image or embedding any third-party image bytes in the repository. For a genuinely representative heldout, curate individually licensed NIND or other separate shutter originals with stable exposure/scene groups, pin actual bytes, and use `heldout_eval.py` on an approved offline sandbox. Retain originals and avoid automatic source-family confirmation.
