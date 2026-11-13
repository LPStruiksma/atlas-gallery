# People bench: what the shipped face models do on real faces

Goal [#580](https://github.com/LPStruiksma/atlas-native/issues/580), Phase 1. Run 2026-11-11 in a
Claude cloud container. Re-run: `tools/people_bench/README.md`.

Until this run every grouping threshold was a guess checked on synthetic vectors. This page holds
the measured numbers behind `FaceMetric.SFACE` and `ClusterPolicy.SFACE_MEASURED`.

## Result

| What | Before (`SFACE_CONSERVATIVE`, raw vectors) | `SFACE_MEASURED` |
|---|---|---|
| 48 real photo libraries pooled into one, 14,182 faces: faces in groups of 3+ that are in the wrong group | 1,775 of 4,660 (38.1%) | **9 of 2,430 (0.37%)** |
| Same: faces the reference model is unsure about | 529 (11.4%) | 28 (1.2%) |
| Same: groups of 3+ that mix libraries | 296 | 36 |
| Same: largest group | 486 faces from 47 libraries | 253 faces from 2 libraries |
| Same: recurring people (10+ faces) with 80% of their faces in one group | 49 of 58 | 46 of 58 |
| 7,140 strangers: largest group | 282 faces from 243 authors | 8 faces |
| 596 strangers (independent set): groups of 3+ | 4 | 0 |

`SFACE_GALLERY` (#581's first thresholds) put 12,041 of the 14,182 library faces and 5,452 of the
7,140 strangers into one group.

The old rule keeps recurring people together only because it keeps almost everyone together. The
new rule trades a little recall (0.85 against 0.92 mean share of a person's faces in their largest
group) for groups that are almost always one person. The fragments it leaves are what the
"Same person?" review is for.

## Data

All photos are Open Images V4 photos with a CC BY 2.0 licence, checked per image against Open
Images' own metadata by `tools/visual_search_bench/fetch_images.py` and downscaled to 960 px in a
cache outside the repository. No owner media, no private data. Only aggregates are committed.

| Set | Photos with a face | Faces | Used for |
|---|---|---|---|
| A: validation split, images with a "Human face" box | 1,683 from 1,489 Flickr authors | 2,404 | stranger statistics, fitting the metric |
| B: test split, same rule | 5,027 from 3,783 authors | 7,140 | stranger statistics, fitting the metric |
| C: the VS-S2c action set | 378 | 596 | independent stranger check |
| D: 48 authors with 120 to 450 face photos each (train split) | 6,738 | 14,182 | library behaviour and recall |
| E: the next 48 such authors | 6,892 | 13,109 | held-out check of the thresholds |

Two faces from photos by different Flickr authors are different people (bar the rare public
figure). That needs no labels and is the basis of every "mixing authors" count.

A library set stands in for a personal gallery: one photographer's photos, with the people who
recur in them, pooled with 47 other libraries that act as strangers.

## Pipeline

`embed_faces.py` repeats the app: decode to a 960 px long edge
(`FaceDiscoveryContract.DEFAULT_DECODE_LONG_EDGE_PX`), YuNet through OpenCV with the top-left
640 letterbox, score 0.9, NMS 0.3, at most 32 faces, the ArcFace-template similarity alignment of
`SFaceAlignment.kt`, the shipped `face_recognition_sface_2021dec_int8.onnx` through ONNX Runtime
(both model files checked against the SHA-256 the app pins), L2 normalisation and the per-vector
int8 storage codec of `FaceEmbedding`. Grouping is not re-implemented: `jvm/PeopleBench.kt` runs
the app's `FaceClusterer` through `ClusterPublication.publish` in publications of 1,024 faces.

## Finding 1: strangers are not at 0.1

Share of cross-author face pairs at or above a raw cosine:

| cosine | set A (2.9 M pairs) | set B (25.4 M pairs) |
|---|---|---|
| 0.32 | 0.85% | 0.87% |
| 0.38 | 0.29% | 0.27% |
| 0.42 | 0.15% | 0.13% |
| 0.46 | 0.079% | 0.062% |
| 0.50 | 0.040% | 0.028% |
| 0.60 | 0.0038% | 0.0022% |

OpenCV's published same-identity cut for SFace is 0.363. It is a 1:1 verification number. A face
in a library of 10,000 other faces is compared with all of them, so at 0.38 it meets about 28
strangers above the cut.

## Finding 2: SFace vectors share a direction

Mean cosine between two strangers: 0.075. The mean of all unit vectors has length 0.275, and the
median face has cosine 0.28 to it; a third of all faces reach 0.32. A centroid of a few different
people drifts towards that mean and then resembles everyone. That is the snowball in the table
above, for any centroid rule with a low enough cut.

`FaceMetric` subtracts the mean vector and re-normalises. Strangers then average 0.003.

## Finding 3: babies and toddlers are one crowd

97% of the stranger pairs above the 1-in-10,000 cosine involve the same 5% of faces. A contact
sheet of those faces is babies and toddlers: children of different families score 0.5 to 0.65 with
each other. No fixed threshold separates them from a real match; centroid 0.60 / 0.65 still left a
72-face group from 70 authors on set A.

`FaceMetric` keeps 32 prototype directions (k-means centres of the centred stranger vectors, each
an average over at least 220 different people). A face's **crowd** value is its best cosine to a
prototype minus the median face's, never below 0. The mean-pair score subtracts the two sides' mean
crowd, so a face in a crowded region needs that much more evidence.

Held-out effect (32 prototypes and the mean fitted on set A only, measured on set B, cross-author pairs):

| | raw | centred | centred, crowd-corrected |
|---|---|---|---|
| cosine reached by 1 pair in 1,000 | 0.434 | 0.352 | 0.294 |
| 1 pair in 10,000 | 0.545 | 0.469 | 0.363 |
| 1 pair in 100,000 | 0.626 | 0.560 | 0.438 |
| most strangers one face matches at the 1-in-10,000 cut | 91 | 76 | 20 |
| share of those matches on the top 5% of faces | 97% | 94% | 32% |

8 to 256 prototypes gave the same result within 0.01; 32 is the shipped number.

What it costs real matches (set D, 1,914 sampled same-person pairs by the silver labels):

| | raw | centred | centred, crowd-corrected |
|---|---|---|---|
| median score | 0.653 | 0.641 | 0.633 |
| share at or above 0.45 | 92% | 86% | 81% |
| share at or above 0.35 | 98% | 96% | 91% |

31% of the library faces have a crowd value above 0 (90th percentile 0.08, 99th 0.27).

## Finding 4: score a group by its members, not by its centroid

A centroid cosine divides by the centroid's length, which is short for a mixed group, so a mixed
group scores *higher* against a stranger than a clean one does. `ClusterLinkage.MEAN_PAIR` scores a
face by its mean cosine to the members and two groups by the mean over all cross pairs. It is the
same dot product with the sum vector, divided by the member count instead of the norm, so it costs
nothing extra and the existing float pre-filter stays a valid upper bound.

Set D, judged by the reference model (see "Judging"); every `p:` row runs on `FaceMetric` vectors:

| Policy | groups of 3+ | faces in them | wrong | unsure | recall | people 80%+ in one group |
|---|---|---|---|---|---|---|
| `SFACE_CONSERVATIVE`, raw | 547 | 4,660 | 38.09% | 11.35% | 0.92 | 49/58 |
| centroid 0.55 / 0.60, raw | 296 | 2,742 | 15.83% | 0.98% | 0.92 | 54/58 |
| centroid 0.45 / 0.50, metric vectors | 376 | 3,262 | 19.13% | 3.53% | 0.95 | 55/58 |
| mean pair 0.45, no crowd term | 409 | 2,729 | 0.84% | 5.24% | 0.87 | 45/58 |
| mean pair 0.50, crowd 1 | 301 | 2,048 | 0.00% | 0.05% | 0.68 | 28/58 |
| mean pair 0.45, crowd 1 | 338 | 2,323 | 0.13% | 0.47% | 0.77 | 38/58 |
| mean pair 0.42, crowd 1 | 372 | 2,524 | 0.36% | 1.62% | 0.82 | 43/58 |
| mean pair 0.45, crowd 1, groups of 3+ at 0.35 | 316 | 2,454 | 0.41% | 1.47% | 0.87 | 47/58 |
| mean pair 0.45, crowd 1, groups of 3+ at 0.30 | 311 | 2,606 | 3.15% | 3.53% | 0.90 | 49/58 |
| **`SFACE_MEASURED`**: the row above at 0.35 plus the same-picture veto | 315 | 2,430 | **0.37%** | 1.15% | 0.85 | 46/58 |

The lower cut for groups of 3+ is #581's attach idea in mean-pair form: a mean over several faces
is steadier than one pair, so it can sit lower. 0.30 is where it starts to cost.

The same-picture veto: the old rule left 52 groups on set D holding two faces from one photo. Two
faces in one picture are two people, except in a collage or mirror, where staying apart is the
cheap mistake.

## Judging

Open Images has no identity labels. For recall the bench uses a stronger reference model
(InsightFace `w600k_r50`, research licence, bench machine only, never shipped): two strangers
reach reference cosine 0.29 once in 10,000 cross-author pairs on set D.

- **Wrong / unsure** (`judge_groups.py`): a face in a group of 3+ is wrong when its median
  reference cosine to the other members is below 0.15, unsure from 0.15 to 0.30.
- **Silver identities** (`silver_labels.py`): average-linkage clustering of the reference vectors
  inside one author's photos at cosine 0.42. Set D has 1,177 identities, 58 with 10 or more faces
  (1,527 faces). **Recall** is the mean share of such a person's faces in their largest group.

## Limits

- The reference model is not ground truth. It is far stronger than SFace but shares its weakness
  on small children to a lesser degree, so "wrong" for children's faces is a lower bound.
- A Flickr author's photos are closer to events and portraits than a phone gallery. Same-person
  pairs here are mostly from one period; a phone library spans years, which lowers recall.
- Thresholds 0.45 and 0.35 were picked on set D. The metric constants were fitted on sets A and B
  only. The held-out check below is the guard against having tuned to set D.
- The owner's own numbers remain the acceptance test for Phase 1: the `people_clusters` log line
  and counted review taps on the phone.

## Held-out check

Set E is 48 further photo libraries (6,892 photos with a face, 13,109 faces, 67 recurring people by the
reference model) that were fetched only after every threshold, the mean and the prototypes were
fixed on sets A to D. Nothing was changed after looking at it.

| | groups of 3+ | faces in them | wrong | unsure | recall | 80%+ in one group |
|---|---|---|---|---|---|---|
| `SFACE_CONSERVATIVE` (raw vectors) | 559 | 4,730 | 1,655 (34.99%) | 521 (11.01%) | 0.92 | 59/67 |
| `SFACE_GALLERY` (raw vectors) | 50 | 11,737 | 11,514 (98.10%) | 55 (0.47%) | 0.94 | 60/67 |
| `SFACE_MEASURED` | 379 | 2,623 | 13 (0.50%) | 22 (0.84%) | 0.82 | 50/67 |

The measured rule holds on libraries it has never seen: 0.50% wrong against 0.37% on set D, with
recall 0.82 against 0.85. Under `SFACE_GALLERY` one group again swallows the pooled library
(11,025 faces of all 48 authors). No measured group holds two faces of one photo; the largest
measured groups (94, 64, 55, 53 faces) each come from a single author.

The limit of this check is unchanged: the judge is a reference model, not a person, and the
photos are public Open Images libraries, not the owner's. The owner's review taps are the final
measure.

## "Same person?" questions

The measured rule leaves some people in two or three groups (a child at two ages, glasses on and
off). `SamePersonHypotheses` lists pairs of unnamed groups (3+ faces with 2+ faces) that may be one
person, for the user to answer; it never joins anything itself. A pair is listed when its mean
corrected cosine is 0.30 or more, or when the faces of the smaller group each find a match in the
other at a median of 0.40 or more, and no human "not the same person" fact or shared picture stands
between them.

Judged by the reference model (median cosine over the cross pairs: same from 0.30, different under
0.15):

| | questions | same | unsure | different | split pairs there were | found |
|---|---|---|---|---|---|---|
| set D | 71 | 37 (52%) | 22 (31%) | 12 (17%) | 48 | 77% |
| set E (held out) | 70 | 44 (63%) | 17 (24%) | 9 (13%) | 66 | 67% |

Lower floors mostly ask about strangers: between 0.25 and 0.30 mean cosine no pair on set D was the
same person. Questions between a group and a single face were also dropped: at 0.30 to 0.35 only
12% were the same person. Each run took 0.2 s on a desktop JVM for about 14,000 faces.

Reproduce: `bench.sh -Ddump=DUMP -Dhypotheses=HYP VECTORS measured`, then
`judge_hypotheses.py HYP DUMP WORK`.

### Named people

Only a human answer makes a face a confirmed face of a named person. A later face the engine is
sure about is shown under the person without one (next section); every other later photo of a
named person waits in an unnamed group. `SamePersonHypotheses.forPeople` asks about those groups
on the named person's page with the same scores and floors, the person's confirmed
faces taking the place of the larger group. Naming does not change a vector, so the table above
applies unchanged. A lone face is listed only from the grouping rule itself (0.35 against a person
of 3+ faces, else 0.45): the face that would have joined the group had it not been named. Below
that, lone faces are mostly strangers (the 12% above). Yes adds the group to the person; No is
stored per face and that group is not offered for that person again.

### All open questions in one list

A page asks one question at a time, so a library with hundreds of open questions needs hundreds of
page visits. People ⋮ → **Same person?** (`samePersonQueue`) lists the same questions for the whole
library, the one whose answer moves the most faces first, then by the engine's strength. Each shows
up to four faces of both sides, spread over the group. Yes and No send the action the page would
send; Skip is forgotten when the list closes. Hidden people ask nothing. The owner's first numbers
(2026-11-12: 334 open questions, 3 of the 20 largest people in one piece) are why this exists.

### Adding to a named person without asking

Would it be safe to add a later face to a named person with no question? Measured per library
set with a split by time: the earlier half of each author's uploads (by Flickr photo number,
`split_by_upload.py`) comes first and is grouped with the measured rule, every group of 3+ is
"named" as it stands, and each face of the later uploads is scored against every named person
the way a lone face is scored above (mean over the person's faces; best single match). A rule
adds the face to its best person when both reach their cut. The reference model judges each
added face by its median cosine to the person's faces (under 0.15 wrong, 0.15 to 0.30 unsure).
"Found" is the share of the later faces the reference places with a named person that the rule
added to that person.

| Rule | First set: added / wrong / unsure / found | Held-out set: added / wrong / unsure / found |
|---|---|---|
| mean 0.35 (the grouping rule) | 709 / 3 / 18 / 89% | 705 / 5 / 14 / 85% |
| mean 0.40 | 640 / 0 / 1 / 83% | 627 / 0 / 2 / 77% |
| mean 0.40 and best match 0.50 | 613 / 0 / 0 / 79% | 601 / 0 / 0 / 74% |
| **mean 0.45** | 537 / 0 / 0 / 69% | 544 / 0 / 0 / 67% |
| mean 0.50 | 461 / 0 / 0 / 60% | 420 / 0 / 0 / 52% |

First set: 135 named people, 7,011 later faces, 775 of them a named person's by the reference.
Held-out set: 201, 6,420 and 808. Each set pools 48 libraries, so every later face is also tried
against the named people of 47 other libraries.

Reading: at a mean of 0.45 (the rule's own cut for joining a small group) the reference judge
flagged none of 1,081 added faces as wrong or unsure, and about two thirds of a named person's
later faces arrive without a question. Zero flagged in 1,081 puts the rate the reference judge
would flag under 0.28% at 95% confidence on these sets; the judge is a stronger model, not
ground truth, so this is not the true error rate. The grouping cut of 0.35 is too loose for this
use (up to 0.7% flagged wrong and up to 2.5% more unsure).

A first run split the photos by even and odd photo number instead. That interleaves bursts and
events, so later faces are often near-duplicates of named ones; it gave 0 wrong and 0 unsure of
1,328 at 0.45 and found 67 to 75%. The time split above is the harder and more honest one, and
the two agree at 0.45.

Limits:

- Upload order is the only time the data has; an author can upload old photos late.
- The realistic false add is a relative in the same library (siblings, parent and child at the
  same age). Pooled strangers mostly do not test that, and these libraries hold few families
  followed for years. The phone's own numbers have to show it.
- The same reference judge and photo sources as the rest of this bench.
- Added faces were not used as examples for later ones; a build that does use them is a
  different rule and needs its own run.

#### The rule as code: `NamedPersonMatches`

`NamedPersonMatches.of(state)` is that rule in the engine: a mean of 0.45 or more to a person with
at least 3 confirmed faces, no second named person at 0.45 or more for the same face, no human
"no" against it, no confirmed face of the person in the same picture, and one face per picture per
person. Only confirmed faces are examples. It reads the state and stores nothing. The bench runs
it on the same time split (the named groups become confirmed people, the later faces are
published as on a phone):

| Naming | First set: added / wrong / unsure / found | Held-out set: added / wrong / unsure / found |
|---|---|---|
| groups of one person share a name (`-DsameName=1`) | 536 / 0 / 0 / 69% | 544 / 0 / 0 / 67% |
| every group is its own named person | 417 / 0 / 0 / 54% | 536 / 0 / 0 / 66% |

`-DsameName=1` takes "one person" from the reference model's labels, so the first row is what a
perfectly merged set of names would give, not something the app can know by itself. So the code
reproduces the table above (0 flagged of 1,080), in about half a second for 7,000
unnamed faces against 130 to 200 people on a desktop JVM. The second row shows what "no second
person" costs when one person carries two names: their later faces fit both and match neither
until the two are merged. That is the intended direction of the error (a question instead of a
guess), and the same rule is what stands between look-alike relatives. It also matched 5 unnamed
faces of the first half (lone faces near a named group), none flagged.

#### In the app

The owner chose on 2026-11-12 to add without asking when the engine is sure, each add undoable and
counted. `ClusterPolicy.SFACE_MEASURED` carries the rule as `personMatchFloor = 0.45` (in its
fingerprint as `;p=0.45`, so phones regroup once). Grouping keeps the result in
`FaceClusterState.matched`, and a test compares it with `NamedPersonMatches.of` read from nothing
after any history of scans and answers.

- A matched face shows on the person's page, in their album, in the viewer's People row and in
  People home's count. It is not a confirmed face: it is never an example for another match, and
  People home's cover is always a confirmed face.
- "Remove from this person" on such a face stores "not this person" for it. It is an unnamed face
  again and never matches or is offered for that person again. Undo puts it back.
- The store keeps a matched face as a membership row of its own, and the match itself in
  `people_matched` (face to person), written with membership by every publication and review
  commit and dropped with that row. Home, the viewer row and the album read the table, so they
  need no cluster state. A file from before the table is filled at its next publication, from
  stored vectors. An older build ignores the table and reads the face as a lone unnamed face.
- Cost: a new scan scores only the new faces against the named people; a review answer scores
  every unnamed face again (about 0.3 microseconds per face and person on a desktop JVM).

```bash
python3 -I tools/people_bench/split_by_upload.py $C/data/licensed_auth.csv $C/data/train-meta.csv $C/early.txt
java ... -Dautoadd=$C/auto -Dearly=$C/early.txt [-DsameName=1] PeopleBenchKt $C/auth/vec.bin
python3 -I tools/people_bench/judge_autoadd.py $C/auto $C/auth
```

## Phase 1 path on the bench

Phase 1 (#580, now atlas-gallery#51) asks that the owner's 20 largest people each be one group.
`PeopleBench -Dphase1=DIR` plays the owner's path on the two sets of 48 libraries: group all faces
with the measured rule; for each recurring reference identity (10+ faces), largest first, name
the unnamed group holding most of its faces, as it stands, mistakes included; let
`FaceClusterer.update` add sure faces on their own; list the questions the app would ask.
`phase1.py` judges it with the reference model. `-Dfloors=` sweeps
`ClusterPolicy.personMatchFloor` (shipped: 0.45). Faces of the named people only:

| floor | set | under the name | in a group asked about | loose, not asked (3+ / pairs / alone) | joined on their own: wrong / unsure | questions with a name: yes / no |
|---|---|---|---|---|---|---|
| 0.45 | 1 (55 people, 1,454 faces) | 90.2% | 57 | 3 / 7 / 76 | 18: 0 / 0 | 32 / 8 |
| 0.40 | 1 | 91.1% | 43 | 0 / 5 / 81 | 33: 0 / 0 | 27 / 7 |
| 0.38 | 1 | 91.8% | 32 | 0 / 5 / 82 | 43: 0 / 0 | 19 / 7 |
| 0.35 | 1 | 92.8% | 15 | 0 / 7 / 82 | 60: 0 / 1 | 6 / 7 |
| 0.33 | 1 | 93.6% | 10 | 3 / 5 / 75 | 77: 1 / 5 | 4 / 7 |
| 0.30 | 1 | 94.9% | 5 | 5 / 7 / 57 | 117: 5 / 19 | 3 / 6 |
| 0.45 | 2, held out (63 people, 1,488 faces) | 86.9% | 63 | 14 / 3 / 112 | 30: 0 / 0 | 36 / 12 |
| 0.40 | 2 | 88.0% | 46 | 9 / 3 / 118 | 47: 0 / 0 | 27 / 12 |
| 0.38 | 2 | 88.4% | 38 | 9 / 3 / 119 | 54: 0 / 0 | 22 / 12 |
| 0.35 | 2 | 89.7% | 13 | 7 / 3 / 125 | 83: 0 / 3 | 5 / 4 |
| 0.33 | 2 | 91.3% | 8 | 6 / 2 / 109 | 117: 2 / 7 | 4 / 4 |

What it says:

- **The questions are the lever for the `top20` target.** At 0.45 the loose groups of 3+ of a named
  person almost all carry a question for that name (57 and 63 faces asked about, 3 and 14 not), and
  three in four of those questions are a yes. Answering the list (People ⋮ → Same person?) is what
  takes a person to `+0/0`.
- **Lowering the floor closes questions, safely down to about 0.38.** From 0.45 to 0.40 or 0.38 the
  engine itself joins 15 to 25 more faces per set, all of them yes answers the owner would otherwise
  give (the "no" questions stay), with no wrong or unsure face in 80 (0.40) or 97 (0.38) joined
  faces. At 0.35 4 of 143 are unsure, at 0.33 3 of 194 are wrong (1.5%), at 0.30 4.3%. Zero of
  about 100 still allows up to about 3% of joined faces (under 0.1% of a person's faces); more sets
  would tighten it.
- **Lone faces are what is left, and no question reaches them.** After the questions, 5 to 8% of a
  named person's faces sit alone, under every floor that is still safe. `top20` cannot see them (it
  counts loose groups with a question), so `+0/0` overstates how whole a person is by that much.
  They are below the face rule's reach; Phase 2 evidence (same event, body, attire) is the way in.

Not yet the owner's library: bench people have 10 to 225 faces (median about 20), the owner's top 20 have 80 to 720,
and a larger person both scores a lone face more steadily and attracts more strangers.

## The phone's own numbers

The bench cannot see the owner's library, and nothing from that library may leave
the phone except counts. People ⋮ → **People numbers** shows those counts and
**Copy** puts them on the clipboard as one line (`PeopleNumbers.line()`):

```
people_numbers v=1 policy=… faces=15 named_people=1 named_faces=10 auto_added=0
groups_3plus=1 faces_in_3plus=4 pairs=0 singletons=1 open_questions=0
top20=n10+0/0,u4+0/0 taps=named:1/11,added:0/0,merged:0/0,taken_out:1/1,
not_this_person:0/0,same_yes:1/11,same_no:0/0,undone:1/0,auto_taken_out:0/0
wrong_share=0.0909 auto_wrong_share=-
```

- `top20`: the 20 largest people, named (`n`) and unnamed (`u`) together, as
  `faces+loose groups/loose faces`. Loose groups are the unnamed groups with an open
  "Same person?" question for that person or group. `+0/0` means the engine sees that person as one group.
- `taps`: review taps per kind as `taps/faces`, counted in the same transaction
  as the review commit (table `people_review_tally`: a kind and two counters).
  Undo takes the undone tap off its kind again; `undone` says how many there were.
- `auto_added`: unnamed faces now shown under a named person without a question.
  `auto_taken_out` counts the ones the owner removed again, and `auto_wrong_share`
  is those over both: the measured error of adding without asking on this library.
- `wrong_share`: faces taken out of a person or group (auto-added ones included),
  over those plus the faces now in or shown under named people. The Phase 1 target is under 0.01 with every `top20` entry
  at `+0/0`.

The line holds no face ID, file name or person name (`PeopleNumbersTest`).
