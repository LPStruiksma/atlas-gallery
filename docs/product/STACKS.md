# STACK-0: display-only stack grouping

Status: **feasibility, tested rules** for [#574](https://github.com/LPStruiksma/atlas-gallery/issues/574) (goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97), Vision E [#102](https://github.com/LPStruiksma/atlas-gallery/issues/102)). This unit adds a pure function and its tests only. Nothing in the app calls it yet; no UI, Pictures grid, MediaStore, Photo DNA or visual-index runtime code changes here. Any stacks UI needs its own Design brief and preflight.

Code: [`StackGroupingRules.kt`](../../app/src/main/java/nl/atlas/gallery/featurestacks/StackGroupingRules.kt). Tests: [`StackGroupingRulesTest.kt`](../../app/src/test/java/nl/atlas/gallery/featurestacks/StackGroupingRulesTest.kt) (synthetic records only).

## Why

Google Photos (stacks with a top pick), Samsung Gallery (group similar images), Aves (burst and RAW+JPEG stacking) and Immich (stacks) all collapse a sequence into one tile. ATLAS shows every frame as its own tile, which costs scroll length and attention at 150k+ items. ATLAS already has the inputs: the Photo DNA dHash, capture times, folders and the face-quality score.

## Safety invariants

- **A view, not a change.** `StackGroupingRules.group(records)` returns a projection of the caller's ordered records. Nothing is moved, hidden, deleted, edited or persisted. `StackProjection.unstacked()` returns exactly the input ids in input order, so "Unstack" (one stack or all) is always exact.
- **No second index.** The Photo DNA 64-bit dHash is an input. The function computes no new hash or embedding and keeps nothing after it returns.
- **One folder.** Files stack only inside one directory. The same file name in two folders never stacks.
- **Photos only.** Videos never stack, neither with photos nor with other videos, in this first version.
- **Unknown is no.** A missing capture time, a missing hash or a flat (non-informative) hash never satisfies a rule. Time adjacency alone never stacks anything.
- **No chains.** Every time-based rule uses complete linkage: a frame joins a stack only if it matches *every* member, so A~B and B~C with A≁C gives {A, B} and C alone.
- **One stack per item.** Rules run in the order below; an item claimed by an earlier rule is not offered to a later one. No nested stacks.
- **Bounded.** A stack has at most 200 members: a longer time run is split, and a same-stem RAW group larger than that is declined (no stack). Work is linear in the library size plus a sort per folder (150k synthetic records group in well under a second on the CI JVM; the test allows 10 s).

## Rules

Thresholds are constants on `StackGroupingRules`, so a later tuning unit changes them in one place, with the tests.

| Order | Reason | Rule |
|---|---|---|
| 1 | `RAW_COMPANION` | Same folder, same file stem (case-insensitive), at least one RAW (`dng cr2 cr3 nef nrw arw srf sr2 orf rw2 raf pef srw x3f rwl 3fr iiq`) and at least one JPEG/HEIC (`jpg jpeg heic heif`), every capture time known and all within **2 s**. Two JPEGs without a RAW are not a pair. |
| 2 | `EDITED_ORIGINAL` | Either explicit lineage (`originalId` recorded by an editor, both in the same folder), or the documented `-edited` / `_edited` suffix on an otherwise identical stem with exactly one candidate original, **and** both dHashes informative and at most **16 bits** apart. Generic suffixes (`_copy`, `(1)`) are not edits. |
| 3 | `BURST` | A specific vendor burst file-name grammar, same folder, same burst key, known capture times, at most **2 s** between neighbouring frames and **30 s** for the whole burst. Grammars: Samsung `yyyyMMdd_HHmmss_NNN`, Google Camera `<n><AAA>_<n>_BURST<17 digits>[_COVER]`. Plain sequential names (`IMG_0001`, `IMG_0002`) are not a burst. |
| 4 | `NEAR_IDENTICAL` | Same folder, known capture times, at most **3 s** to the previous member and **10 s** across the stack, dHash informative and at most **4 bits** from every member, both dimensions known, same orientation and an aspect ratio within **2 %** (a crop or a rotated frame is a different picture; unknown dimensions never match). |

**Why these numbers.** The near-identical bound of 4 bits is deliberately tighter than Photo DNA's 5-bit "possible visual match", because Photo DNA shows pairs for review while a stack hides frames without asking. For two unrelated images with independent bits, the chance of a dHash distance of 4 or less is about 3.7 × 10⁻¹⁴; real photos are correlated (sky, walls), so the true rate is higher, which is why the rule also needs the same folder, seconds-level capture times and the same aspect ratio. This is a strong guard, not a mathematical guarantee: a false positive is possible (for example two frames of a blank wall). The cost asymmetry decides every tie: a missed stack costs a little scrolling, a wrong stack hides a distinct shot.

**Burst grammars.** Only the two grammars above are recognised. Other vendors (Sony, Xiaomi, iPhone exports) are added one grammar at a time, each with a hard-negative test, after the aggregate run below shows how many items they would cover.

## Tile placement

Each stack sits where its first member was in the caller's order; the other members are removed from the tile list. Members keep input order. Singles are untouched. With a newest-first grid, the stack appears at the newest frame.

## Top pick (cover)

1. A user override wins when the chosen id is a member of that stack (`topPickOverrides`, keyed by any member id). Stale entries (the chosen item is gone) are skipped, so a valid override on another member still wins; with none valid, the rule decides.
2. For `RAW_COMPANION` the JPEG/HEIC is preferred (it is what can be shown); for `EDITED_ORIGINAL` the edited copy is preferred (Google Photos shows the edit too).
3. Then the sharpest frame (compared in 0.01 steps), then the best face quality, then the largest file, then the smallest id, so the result is deterministic.
4. A missing sharpness or face-quality observation ranks below any measured value; it never wins by default.

This is the same idea as best-shot covers ([#163](https://github.com/LPStruiksma/atlas-gallery/issues/163)). Sharpness is an optional input: ATLAS has a per-face Laplacian in `FaceQuality`. QUALITY-0 adds an isolated, nullable whole-frame observation, but it is not wired into this rule; covers still fall through to face quality and file size. Any future QUALITY-1 integration must preserve edit/RAW preference, abstain on missing or hard-negative evidence, and never use sharpness alone to classify a distinct image as disposable.

## Aggregate estimate for the owner's library

`StackGroupingRules.aggregate(projection)` returns only four numbers: original tiles, stack count, items in stacks and tiles saved (each stack of k items saves k − 1 tiles). `percentSaved` is tiles saved divided by original tiles.

Method: on the phone, run `group()` over the Pictures-grid records with the Photo DNA hashes already in memory, keep only the `StackAggregate`, and report it through the existing aggregate-only diagnostics path (the numeric `PerformanceAggregates` style: fixed keys, no ids, names, paths, times or hashes). One number per reason (burst, RAW, edit, near-identical) is enough to decide which follow-up is worth building first. Wiring that report is not in STACK-0's touch list; it is follow-up unit S1 below. Nothing about the owner's library goes into GitHub except those totals, if the owner chooses to share them.

## Follow-up units (not STACK-0)

Each UI unit needs its own Design brief and preflight.

- **S1 Aggregate run:** add fixed numeric keys (per reason) to the on-device diagnostics report and run once on the owner's phone. Decides which reasons ship first.
- **S2 Grid badge and count:** the stack tile shows the top pick with a small count badge; tapping opens the viewer on the top pick.
- **S3 Viewer strip:** a filmstrip of the stack's members inside the viewer, with "Set as cover" (writes the override) and "Unstack" (per stack).
- **S4 Selection semantics:** selecting a stack tile acts on the whole stack for share, move and delete, with the count shown in the action bar before anything happens; an explicit "top pick only" choice. Destructive actions always show the member count.
- **S5 Setting:** "Group similar images" on/off in Settings, as Samsung has; off returns every individual tile.
- **S6 Persistence:** remember overrides and per-stack unstack choices across restarts (a small keyed store, not a similarity index).
- **S7 More grammars and nested stacks:** further vendor bursts, and letting a RAW+JPEG pair act as one frame inside a burst.
