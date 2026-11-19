# Split bench: assisted People split (SPLIT-0, #105)

`featurepeople/split/SplitProposer` proposes how a wrongly merged person divides. These
numbers come from the app's own code on licensed faces. They are aggregates only: no face,
photo or label leaves the bench cache.

## Method

- **Faces:** the People bench library set (`tools/people_bench/README.md`, steps 1-6): the
  "Human face" photos of 48 Flickr authors from the Open Images train split, CC BY 2.0
  only. That gives 8,719 photos and 14,180 faces, detected and embedded like the app does it
  (YuNet + SFace int8, 960 px), then put through `FaceMetric.SFACE`.
- **Truth:** identity labels from the research-only reference model (InsightFace
  `w600k_r50`, never shipped). 50 reference identities have 12 or more faces (1,445 faces).
- **Profiles** (`tools/people_bench/jvm/SplitBench.kt`, fixed seed):
  - `clean`: one identity. The truth is k = 1, so this is the false-split control.
  - `two`: two identities from different authors, merged.
  - `three`: three identities, each from a different author.
  - `alike`: two-identity profiles taken from the top tenth of pairs by mean-pair score
    (0.08 to 0.68). The look-alike case.
  - `family`: two identities from the same author, often relatives.
- **Picture keys:** each face carries its photo number as `media`, so two faces from one
  photo are never proposed as one person.
- **Metrics:**
  - k accuracy: proposed k equals the true number of people.
  - purity: share of placed faces that sit in their group's majority identity.
  - coverage: share of each person's faces that land in that person's best group.
  - Unsure share: share of faces left in the Unsure tray.
  - impure splits: splits with purity under 0.9.
  - false-split rate: share of `clean` profiles proposed as two or more people.

## Results (shipped constants: join 0.20, gain 0.12, Unsure margin 0.06, outlier 0.18)

| profiles | n | k accuracy | purity | coverage | Unsure share | impure splits |
|---|---|---|---|---|---|---|
| clean | 50 | 0.880 | 1.0000 | 0.917 | 5.9% | 0 |
| two | 150 | 0.927 | 0.9999 | 0.971 | 2.3% | 0 |
| three | 150 | 0.927 | 0.9987 | 0.974 | 1.5% | 0 |
| alike | 117 | 0.923 | 0.9944 | 0.968 | 2.2% | 0 |
| family | 54 | 0.852 | 0.9975 | 0.937 | 5.4% | 0 |

**False-split rate on clean profiles: 6/50 (12%). All six are reference-label errors.**
Nine of the 50 reference identities contain two faces from one photo, which means two people
under one label. The six "false splits" are all among those nine, so the shared-picture rule
is doing its job. On the 41 clean profiles without such an error the false-split rate is
**0/41**.

The same run without picture keys (`-Dnomedia=1`) shows how much of the remaining error
comes from those labels:

| profiles | k accuracy | purity | coverage | Unsure share |
|---|---|---|---|---|
| clean | 1.000 (0/50 split) | 1.0000 | 0.999 | 0.07% |
| two | 1.000 | 1.0000 | 0.998 | 0.11% |
| three | 0.993 | 0.9991 | 0.995 | 0.14% |
| alike | 0.991 | 0.9961 | 0.994 | 0.08% |
| family | 0.963 | 0.9876 | 0.977 | 0.24% |

- **No impure split in any profile kind.** When the engine splits, the groups are the
  right people. Its misses go the safe way: it under-splits a few relatives and look-alikes
  (k too low), or puts uncertain faces in the Unsure tray.
- **Cut sweep** (join 0.10 to 0.20, gain 0.12 to 0.22): join 0.10 starts merging
  look-alikes (alike k accuracy 0.72, three-person purity 0.989). From 0.15 up the results
  are flat, so the constants sit in a stable region.
- **Speed:** 521 profiles in 1.4 s on a desktop JVM (one thread). The JVM test
  `fiveThousandFacesStayBounded` times 5,000 faces at 0.3 to 0.5 s. A mid phone is roughly
  3 to 5 times slower, so measuring on a device is part of SPLIT-2.

## Limits

- **Easier than real mistakes.** These profiles are merges of identities the reference
  model keeps apart. A real wrong merge in ATLAS is one that SFace grouping itself made, so
  it is harder by construction. The `alike` and `family` rows are the closest stand-in.
  Counted owner corrections (SPLIT-2) are the real check.
- **The labels are not ground truth.** They are a stronger model's opinion, and the
  two-faces-in-one-photo errors above show where they fail.
- **Only the face layer is used.** Body, attire and context evidence are not measured yet.
  Same-session time is a weak tiebreak only, and this set has no capture times.

## Reproduce

After the People bench steps 1-6 (which write `$C/auth/vec.bin`):

```bash
A=app/src/main/java/nl/atlas/gallery
kotlinc $A/featurepeople/cluster $A/featurepeople/split $A/featureface/NativeFaceIds.kt \
    $A/featureface/FaceDiscoveryContract.kt tools/people_bench/jvm/SplitBench.kt \
    -include-runtime -d $C/split.jar
java -cp $C/split.jar nl.atlas.gallery.tools.peoplebench.SplitBenchKt $C/auth/vec.bin 150
# -Dnomedia=1 leaves out picture keys; -Dtunings="0.20,0.12;0.15,0.18" sweeps join,gain
```
