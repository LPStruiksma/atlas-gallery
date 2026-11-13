# Virtual-person viewpoint manifest: metadata-only V5 admission preflight

**Status: synthetic-ready validation logic; absolutely no real PersonX, RandPerson,
or other external images/3D assets have been licensed, downloaded, approved,
opened or benchmarked.** This is a safe preparatory tool for a future
source-owner-authorized *local-only* rear/partial/Unknown study.

## Scientific motivation

PersonX's [upstream source documentation](https://github.com/sxzrt/Instructions-of-the-PersonX-dataset/tree/9dc31bacb42e2670bce7feb5c7ca025f7bcfa794)
describes 1,266 virtual characters at 36 viewpoint indices (10-degree
increments) under six backgrounds. Its paper
[Dissecting Person Re-Identification from the Viewpoint of Viewpoint
(CVPR 2019)](https://openaccess.thecvf.com/content_CVPR_2019/papers/Sun_Dissecting_Person_Re-Identification_From_the_Viewpoint_of_Viewpoint_CVPR_2019_paper.pdf)
studies left/front/right/back orientations, but a filename's numeric
viewpoint index is NOT independent evidence that a specific exported
crop is rear facing. Preserve that uncertainty until image rights and
reviewed camera orientation are independently established. The authors'
README additionally describes ID zero as a copy of ID one used to
initialize an export controller. That duplicate must not be counted as
another independent virtual person.

The code/MIT license alone does **not** license separately hosted
virtual-person archives, Unity backgrounds/meshes or derived image
embeddings. Refer to `SYNTHETIC_REID_DATASET_RIGHTS_RESEARCH.md`.

## Exact input (no photographs, filenames or real person IDs accepted)

`audit_virtual_viewpoint_manifest.py` reads a bounded CSV with
exactly these seven **opaque-only** columns:

```csv
virtual_character_uid,split,camera_uid,view_index,role,reviewed_visibility,capture_component_uid
```

- `virtual_character_uid`: consistent, anonymous, dataset-specific
  character key, reused on every variant of the SAME virtual person.
  No raw face/person names or image file paths. All variants of one
  character must stay in one `calibration` **or** `holdout` partition.
- `camera_uid`: caller-declared background/camera token. Camera tokens
  do not establish physical independence.
- `view_index`: exact canonical decimal **1–36** corresponding to
  the published PersonX example. This is a coverage/index *token*, not
  automatically a front/back label or degree value.
- `role`: `gallery_known`, `query_known` or `query_unknown`.
  A known character requires both gallery and known-query evidence.
  An Unknown character must have **only** Unknown queries and never
  be a gallery candidate.
- `reviewed_visibility`: canonical reviewed visibility from the
  current V5 vocabulary, e.g. `front`, `back`, `back+occluded`,
  `partial` or `unknown` (unreviewed). No duplicated/reordered tags,
  no contradictory front+back. A report cannot **infer** this from
  `view_index` without an authorized independent review.
- `capture_component_uid`: opaque manually declared source family.
  Never reuse the same capture token across different virtual people
  or splits; recognize that duplicates *not* declared by the author
  cannot be discovered from an opaque manifest.

One (character, camera, viewpoint index) triple can occur **once only**.
This blocks relabeling a single render as both gallery and query
or as an independent calibration/holdout observation.

## Canonical PersonX profile and a disguised source-reuse failure

**Official upstream PersonX README** at
[`9dc31bacb42e2670bce7feb5c7ca025f7bcfa794`](https://github.com/sxzrt/Instructions-of-the-PersonX-dataset/blob/9dc31bacb42e2670bce7feb5c7ca025f7bcfa794/README.md)
specifically says its **ID 0 is a copy of ID 1** used to initialize the
Unity two-camera capture process, and **does not count** toward the
1,266 published identities. Export example filenames use canonical
four-digit numeric subject IDs such as `0001`.

The metadata parser therefore refuses all zero-only subject tokens
(`0`, `00`, `0000`) even in the generic fictional-identity mode.
For actual PersonX-style filenames, invoke
`--dataset-profile personx_v1` to additionally require **exactly four
ASCII digits from `0001` to `1266`** for every declared virtual
character. In particular, `1` and `0001` cannot masquerade as
independent characters, and a duplicated initialization render cannot
inflate the Unknown-person denominator. This check verifies a
**declared token grammar**, not the identity or legal authenticity of
external archive images; if an archive genuinely uses a different ID
format, independently establish its naming contract rather than
renaming units to pass the gate.

A second previously possible **false pass** was more subtle:
the validator required that a known person have **at least one**
camera- and source-disjoint front-gallery/rear-query pair, yet it
could silently accept another gallery/query pair for that person
which reused **the same capture component**, presented with a
different camera token/viewpoint. One legitimate pair must not
launder an invalid duplicated query into the study. The gate now
rejects a known character if **any** capture component occurs
in both gallery and query roles, even if an unrelated correctly
separated front/rear pair exists. All declared known-role captures
are checked, not just those chosen for the front/rear comparison.

The front-gallery/rear-query independent-pair existence check is
also implemented with an **O(fronts + backs)** indexed
camera/source counting procedure instead of a potentially quadratic
nested cartesian scan. Synthetic exhaustive small-set tests compare
that procedure to a separately written independent cartesian oracle,
including repeated camera/source tokens. Camera and capture-source
tokens remain **caller-declared** and cannot establish physical
independence or training-dataset separation.

Example (authorized **metadata only**, no actual PersonX archive
ingested here):

```sh
python tools/identity_fusion/audit_virtual_viewpoint_manifest.py \
  /authorized/personx_opaque_viewpoint_manifest.csv \
  --dataset-profile personx_v1 \
  --require-unknown-tag back
```

The report states `dataset_profile_declared_not_authenticated`,
`personx_duplicate_zero_and_noncanonical_ids_refused`, and
`all_known_gallery_query_capture_role_overlap_refused`. All
underlying image rights/authorship and safety approvals still
return **false**.

## Baseline admission checks

In **each split independently**, require at least:

- **20** distinct known virtual characters with a separately declared
  *front* gallery capture and *rear* query capture from different
  cameras AND different capture-source groups.
- **52** distinct Unknown virtual characters, each with a reviewed
  *back*-tag query and no gallery image for that character.
- Exact subject/capture partition isolation and known-vs-Unknown
  enrollment. A single source never counts twice as a different
  person's evidence.

These are **metadata completeness minima, not statistically adequate
sample sizes**. Twenty known people cannot support a tight low-error
confidence interval, and 52 Unknown *virtual meshes* are not assured
independent capture components, independent real humans or a
familywise 5%-95% error-rate certificate. More rigorous registered
component-level budget planning remains in
`plan_reid_unknown_budget.py` and `preregistered_pose_study.py`.

Optional `--require-unknown-tag back|partial|occluded|...` means each
specified visibility tag must appear on at least 52 **distinct
Unknown characters** in both partitions. Missing real reviewed tags
cause a refusal, not a misleading zero-risk score.

### Example — only metadata the owner is authorized to inspect

```sh
python tools/identity_fusion/audit_virtual_viewpoint_manifest.py \
  /authorized/opaque_viewpoint_manifest.csv \
  --require-unknown-tag back \
  --require-unknown-tag occluded
python tools/identity_fusion/test_audit_virtual_viewpoint_manifest.py
```

The CLI opens **only** the explicitly staged CSV and rejects symlink
parents and leaves, hardlink aliases, nonregular files, files larger
than 8 MiB, and source inode/size/timestamp changes during read.
Its report contains aggregate counts and public category labels only;
it **never** echoes raw character/camera/capture IDs, paths, individual
score values or images. All files remain unchanged. Errors are generic
and do not reveal private local paths.

## What a successful report means — and does not mean

A successful result says only that the **declared** virtual-character,
camera, capture and rear-view metadata have structural coverage
consistent with the fixed preliminary study design.

Explicitly **false** even after a full synthetic pass:

- permission to use any dataset, commercially or otherwise;
- confirmation of remote image archive SHA-256/authorship;
- independent validation of filenames' front/back orientation;
- subject/event/physical-image independence;
- proof of no model-training overlap or retrospective gallery selection;
- consented cross-year family-photo recognition accuracy;
- model checkpoint permissions, safe on-device export, S24+ latency,
  memory, thermal, or power readiness;
- automatic Person linking, production deployment or deletion.

After obtaining source-owner rights and separately authenticated assets,
record registered source and archive hashes, independent labels,
character/source-connected groups, legal scope and time-frozen
holdout before running actual person-ReID inference. A camera change
within the same 3D character is a viewpoint test, NOT proof of
cross-age identity robustness.

The automated tests use exclusively invented token-only rows; **no
training, downloads, photos, cloud inference or installed Android
app interaction occur**.
