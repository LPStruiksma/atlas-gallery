# Identity bench fixtures and their licences

Every face in the benchmark is synthetic. No photo of a real person is used, and
no image, crop, vector or weight is committed: `fixtures.py` pins each file by
archive URL, byte offset, size and SHA-256, fetches it with one ranged request
into `~/.cache/atlas-identity-bench/fixtures/` and refuses a file whose hash
differs. The licence texts below were read at their source on 2026-11-18.

## Sources

| Set | What it is | Used for | Licence (as read) | Where |
|---|---|---|---|---|
| **FLUXSynID** 1.0.0 (Ismayilov, Sero, Spreeuwers; University of Twente, EINSTEIN project) | 14,889 photoreal synthetic identities. Each has a document-style photo and three "live" variants (LivePortrait expression and pose, Arc2Face, PuLID scene), 1024 px (Arc2Face 512 px) | The 8 benchmark identities (4 references each), 24 look-alike negatives, 22 targets, and the development split | **CC BY-NC 4.0** ("free to use, share, and adapt the dataset for non-commercial purposes, provided that appropriate credit is given"). The record adds that the images were made with FLUX.1 [dev], whose own non-commercial licence users must respect | Record and licence statement: https://zenodo.org/records/15172770 (DOI 10.5281/zenodo.15172770). Licence text: https://creativecommons.org/licenses/by-nc/4.0/legalcode |
| **Face Synthetics**, 1,000-image sample (Microsoft; Wood et al., ICCV 2021) | 512 px rendered faces with per-pixel labels and 70 landmarks | 7 hard targets chosen from the ground-truth labels: profile, opaque eyewear, glasses, wide-open mouth, dark, beard | **Research Use of Data Agreement v1.0** (R-UDA): "solely for Computational Use for non-commercial research" | https://github.com/microsoft/FaceSynthetics (`LICENSE.txt`) |
| **DigiFace-1M**, 72-images-per-identity part 1 (Microsoft; Bae et al., WACV 2023) | 112 px rendered faces, 72 per identity over pose, expression, lighting and accessories | The rendered-identity track: 4 identities with 8 references each, reported apart from the headline | **R-UDA v1.0**, the same text | https://github.com/microsoft/DigiFace1M (`LICENSE`) |

Credit, as the licences ask: FLUXSynID by Raul Ismayilov, Dzemila Sero and Luuk
Spreeuwers, CC BY-NC 4.0; Face Synthetics and DigiFace-1M by Microsoft, R-UDA 1.0.

## What these licences allow here, and what they do not

- **Evaluation: yes.** Both licences allow non-commercial use and adaptation.
  Swapping one synthetic face onto another synthetic photo and measuring the
  result is such a use. ATLAS is a personal, non-commercial app.
- **Not a commercial input.** R-UDA 2.1 says the data and results may not be
  used "in any commercial offering, including as part of a product or service
  (or to improve any product or service)". CC BY-NC and the FLUX.1 [dev] terms
  are also non-commercial. If ATLAS ever becomes a commercial offering, the
  engine choice in `docs/identity-studio/BENCH.md` must be re-derived on
  fixtures that allow it. This is our reading, not legal advice.
- **No redistribution from this repository.** The repository is public, so the
  images stay out of it. Contact sheets are adaptations of CC BY-NC and R-UDA
  material: they carry the credit line above when shared, are kept in the
  private project files, and only a handful of small figures may go in a pull
  request (R-UDA 5.5 allows "de minimis portions ... such as figures").
- **Personality rights: none arise.** The faces are generated or rendered. Both
  Microsoft sets note that a resemblance to a real person is unintentional.
- **Not checked by us:** the FLUX.1 [dev] licence text itself (the record
  points to it; we rely on the record's CC BY-NC grant for the images).

## How the set was chosen (before any swap was run)

`python3 -m tools.identity_bench select` re-derives the manifest. The rules look
only at the untouched fixtures, never at a swap result.

1. **Pool.** The 265 FLUXSynID identities whose five files lie wholly in the
   first 200 MiB of the archive, in archive order. The first 60 are the
   development split, used for trying things out and for screening; the other
   205 are the test split.
2. **Identities (8).** In the test split, rank same-gender pairs by the research
   judge's similarity of their document photos and take the two closest
   disjoint pairs (4 identities that each have a strong look-alike among the
   others). Then the first identity in archive order for each of: woman under
   30, woman 50 or older, man under 30, man 50 or older. References: all four
   photos of each (document, expression and pose, Arc2Face, scene).
3. **Hard negatives (24).** For each identity, its three most similar other
   test-split identities by the same judge. Each enters the rank-1 gallery with
   its document photo.
4. **Targets (29).** From the remaining test split, the first identities in
   archive order that fit each row; the dataset's own attribute file decides
   glasses, beard and headwear.

   | Targets | Tag | Photo | Recipe |
   |---|---|---|---|
   | T01–T03 | frontal | document | none |
   | T04–T07 | scene | scene | none |
   | T08–T10 | expression | expression and pose | none |
   | T11–T12 | glasses | scene, attribute "eyewear" | none |
   | T13–T14 | beard | scene, attribute "beard" | none |
   | T15 | headwear | scene, attribute "headwear" | none |
   | T16–T17 | low-light | scene | exposure x0.16 plus seeded noise |
   | T18, T19 | small-face | scene | shrunk to 25 % and 12 % on a blurred backdrop (face about 110 and 60 px) |
   | T20 | occlusion | document | a flat pole drawn over one eye |
   | T21–T22 | two-people | two scenes side by side | the left face is replaced, the right must not change |
   | T23–T24 | profile, rendered | Face Synthetics | first two frames with strong yaw |
   | T25–T28 | glasses, expression, low-light, beard (rendered) | Face Synthetics | first frontal frame over the label threshold |
   | T29 | occlusion, rendered | Face Synthetics | the frame with the most opaque eyewear |

   The attribute tags are the dataset's, not ours: two "scene" targets also wear
   glasses, and one "beard" target has only light stubble.
5. **Rendered identities (4).** DigiFace-1M subjects 0–3, eight references each:
   the first image with a detectable face in each block of nine (two per
   accessory set). The 112 px images are enlarged four times before detection.
6. **Pairs (70).** Target number n is swapped with identities n mod 8 and
   (n+3) mod 8, so every identity meets about seven targets, same and other
   gender, age and skin tone. Each rendered identity is swapped onto three
   photoreal targets.

## Known limits of this set

- Synthetic faces are not real photos. FLUXSynID faces are clean, sharp and
  mostly frontal; the "same person" across its four photos was itself produced
  with identity-preserving generators that use ArcFace-family embeddings, so
  the reference sets are tighter and friendlier to an ArcFace-family judge than
  a real People album would be.
- Four references per identity is the unit's minimum, with a modest pose spread.
  Real pose spread exists only in the rendered DigiFace-1M track.
- Profile, natural low light and heavy occlusion exist only as rendered or
  derived targets.
- No children. No real camera noise, motion blur or compression.
- 70 pairs give intervals of several hundredths on a mean; small differences
  between candidates are not resolved.
