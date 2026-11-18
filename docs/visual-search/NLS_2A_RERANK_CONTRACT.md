# NLS-2A — bounded count + negation reranking contract

Unit: [atlas-gallery#230](https://github.com/LPStruiksma/atlas-gallery/issues/230), goal [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94). Latitude: room.

## Scope and input

`SceneCountNegationRerank` is a **pure Kotlin, unconnected** ranking primitive. The
future NLS runtime passes the existing SigLIP 2 image/keyframe **top-100 cosine
candidates**, their stable opaque moment IDs, and **already observed** YuNet
face and/or MediaPipe person-detector counts. Each count is a nonnegative
number, **null means unknown**, and fused count = `max(faceCount, personCount)`
of the observed fields, following NLS-0's `people_count`. Thus zero is
meaningful only when the detector actually produced zero; this core never
triggers a detection, scans an archive, or assumes unknown means empty.
Optional `negatedClauseCosine` is the *same candidate moment's* cosine with
an independently encoded negative clause. The caller may omit every such
cosine; the core never runs a model, performs I/O, accesses a pixel, or
creates a second index.

`parse(query)` extracts only explicit EN/NL count and absence cues:

| Text | Count | Negated clause |
| --- | --- | --- |
| `Two people on a beach` / `Twee mensen op het strand` | Exactly 2 | — |
| `A man and a woman` / `Een man en een vrouw` | Exactly 2 | — |
| `A single person` / `Eén persoon` | Exactly 1 | — |
| `At least three people` / `Minstens drie mensen` | At least 3 | — |
| `A crowd` / `Een menigte` | Crowd (5+ match, 3–4 neutral) | — |
| `Church without people` / `Kerk zonder mensen` | Exactly 0 | `people` / `mensen` |
| `Beach without a dog` / `Strand zonder rode jas` | Unknown | `a dog` / `rode jas` |

Only explicit absence of generic people (also `no people`, `geen
mensen`) or a supported empty *scene* phrase implies zero. `Two dogs`
does not imply two people. Extraction is intentionally narrow: it does not
claim to solve negated predicates such as `not standing`, attachment,
gender, spatial positions, or contradictory prose. An unknown cue means no
count bonus or penalty. UI integration, model deployment, translation,
runtime benchmark and end-to-end indexing remain separate units.

## Scoring and order invariants

1. Sort candidates by **original semantic cosine** (descending), then
   opaque ID (ascending); reject nonfinite cosine or duplicate/blank ID.
   Only the first `min(100, size)` are eligible; `topK` may narrow, not
   expand, this head.
2. Let `σ` be the *population* standard deviation of the head's semantic
   cosines (same dispersion statistic as NLS-0). If `σ ≤ 0.000001`, scores
   and order stay semantic: a flat or near-flat head is not an evidential
   license to invent a winner.
3. For an observed people count, agreement with `Exactly(n)` is `+1`
   on equality, `−0.5` off by one and `−1` off by two or more; the
   `AtLeast` and crowd variants use bounded agreement too. **Missing**
   counts contribute **0**, not disagreement.
4. For an explicit negated clause, take finite, in-range negative-clause
   cosines from the head. Only if **at least two** exist with non-flat
   dispersion, normalize each by that pool's mean and standard deviation,
   clip to `[−1, +1]`, and subtract it. Missing/invalid values contribute
   zero. This contrasts evidence *within this head* rather than subtracting
   a large absolute text similarity from all scenes.
5. `score = originalCosine + σ × (countAgreement − relativeNegativeCosine)`.
   Count and negation weights are both 1 by default. This adapts the NLS-0
   score-dispersion count heuristic with a bounded relative negation term;
   it does **not** reproduce the benchmark's unbounded absolute
   `−negatedPhraseCosine` term. We do not claim its benchmark outcome for
   this new formula.
6. Sort head by adjusted score (descending), original cosine (descending)
   and ID (ascending). Append the entire remaining **semantic tail
   unchanged**; there is no global resort, filtering, suppression or
   top-K eviction. A head score can be numerically below a tail cosine:
   **returned list order**, not a subsequent global score sort, is the
   output contract. Every candidate's original semantic cosine is returned.

Stable ties are independent of input iteration order. Photos and video
moments are opaque unique IDs, so callers can use one key per indexed moment
and later deduplicate videos using their existing result logic.

## Existing evidence, not a new measurement

[NLS-0's public results](../visual-search/results/nls0_results_20261114.json)
measure a **4,824-image, 33-query** Open Images relational gallery with
top-100 structured reordering. The shipped int8 image + int8 text baseline
has **EN macro P@10 0.4848**, **NL 0.4273**; the benchmark's isolated
count heuristic gives **EN 0.5000**, **NL 0.4212**; the isolated negation
heuristic gives **EN 0.5091**, **NL 0.4485**. These are **NLS-0 benchmark
figures**, *not* NLS-2A measured performance and not runtime acceptance.
Doubling the count weight in NLS-0 reduced English P@10 (0.4788), so this
core does not double it. NLS-0 found no gain from person crops or pose as a
default: neither is present here.

The synthetic pure-JVM
`SceneCountNegationRerankTest` pins one-difference cruxes (two people vs
one, zero vs some), EN/NL parsing, negative-clause contrast, missing vs
observed-zero counts, deterministic ties, flat-score fallback, and an
unchanged item #101. It does **not** replace a real-device detector-accuracy
study, integrated runtime test, or a re-run of the public benchmark with
this exact scoring rule. Final P@k and hard-negative-rate recheck waits for
NLS runtime integration.
