# IDS-12A — full-head quality admission (pre-registration)

Status: **pre-registered rubric, NOT a backend acceptance result**. Unit [#772](https://github.com/LPStruiksma/atlas-gallery/issues/772); goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). This rule set is committed before the IDS-12 backend acceptance exercise. A later exercise must cite this revision and must not adjust thresholds to suit results. Neither the four-pair IDS-3 pilot nor any synthetic self-test is a twenty-pair backend pass.

## What is being tested

The requested operation **replaces the entire head**—face, hair/hairline, ears and head outline—while preserving target head pose, body and the unedited photograph. A larger face mask is not sufficient. This gate supplements, rather than redefines, the IDS-2 [benchmark](BENCH.md) and the IDS-3 [full-head probe](FULL_HEAD.md). Reuse the IDS-2 fixture manifests and judges when measuring images; preserve IDS-3's `full_head`, `face_only` and `target_head_remains` classification distinctions. Additional categories `no_swap` and `failed` are nonpasses. **Neither Codex nor a CI self-test is being asked to run an inference model.**

## Cohort locked before a final backend run

- Predeclare at least **20 distinct, licence-cleared or fully synthetic target/reference pairs**, identified by stable pair IDs, with their sources, source licence and permitted use, immutable target/reference IDs, baseline IDs and any hash needed to reproduce them. Merely repeating one pair with different seeds does not make a new pair.
- Include **at least 4 profile/three-quarter** target views, at least **2 distinct reference hair shapes**, examples where the hairline must change, and difficult neck/background boundaries; preserve these tags and list the specific pair IDs meeting them. The unit test may use constructed records, but a model admission run requires the underlying images and licences.
- Before generating outcomes, lock a fixed candidate engine/version and model/adapter digests, per-pair seed, target pose, declared edit fence and the face-only comparison. Record the exact rubric revision. No real/private photographs, new model weights, paid compute, downloads or credentials enter this work.

## Mandatory row evidence

For each predeclared pair, keep explicit fields for pair ID, target/reference IDs and licence evidence, pose tag, seed, engine/version, model and adapter SHA-256 (or an explicit not-applicable reason), output-mask hash, predeclared edit-fence hash, baseline, classification, target-pose check, full-head target/result scale, outside-fence pixel equality, new-hair/hairline votes, neck-seam votes and profile-capable identity evidence. Each field is either `measured` with a typed value and method/provenance, or `not_measured` with a nonempty abstention/failure reason. A missing, malformed, non-finite, contradictory or unlicensed row is **not a pass**. Preserve original individual judge votes, blinded presentation order, disagreement, ties, abstentions and missing judgements; never back-fill them from a consensus guess.

Freeze the `N` planned pairs before results. Missing output, parser errors and inference failure retain their place in `N`. `N < 20`, absent licence proof, missing hard-case coverage, a changed rubric, or missing provenance makes the overall verdict **INSUFFICIENT_EVIDENCE / FAIL-CLOSED**, never PASS.

## Registered gates

1. **Whole head and pose:** at least **90% of all predeclared pairs** must both classify `full_head` by the IDS-3 taxonomy and preserve the *target* pose (not merely match the reference pose). The count is over the entire cohort, including failures and abstentions. Do not infer pose preservation from a missing yaw or landmarks: a missing measurement is nonpassing. In addition, **zero** `face_only` or `target_head_remains` cases may be labelled or surfaced to a user as successful whole-head replacement, even if some other score is high.
2. **Edit fence:** **bit-identical original pixels outside the predeclared edit fence in every output** (exact integer byte equality, not PSNR or fuzzy tolerance). The fence is fixed before seeing output; a dynamically enlarged or all-frame mask is invalid. Missing pixels/mask, incompatible shapes, an empty outside region, or missing fence hash fails this gate.
3. **Scale:** for each valid measurable pair use `abs(result_head_span - target_head_span) / target_head_span`, using the same explicitly defined head span (e.g. outer silhouette width or calibrated landmark distance) before/after. Target span must be finite and positive. The **median absolute relative error ≤ 0.10**. No cherry-picked subset: report missing/abstained scale pairs; a missing required measurement makes the gate not met.
4. **Two independent blinded judges versus face-only:** for **new hair/hairline** and **neck-seam quality** separately, at least **70% prefer the candidate** over the paired face-only baseline. Freeze blinded ordering and collect two distinct judges per case. Count candidate wins against all expected votes (`2 × N`); baseline wins, ties, absence and abstentions remain in the denominator and are not wins. Also report judge-wise counts and conflicts without hiding discord. Missing baseline or judge independence means no PASS.
5. **Profile-capable identity:** report reference identity preservation for *every* pair with **two independent judges or two independent scoring methods capable of profile views**. Preserve individual outcomes and abstentions. A frontal-only SFace score on an unscorable profile is not identity proof; abstentions cannot be counted as passes. Identity disagreement or insufficient profile-capable identity evidence yields INSUFFICIENT_EVIDENCE; this pre-registration specifies no invented numeric identity accuracy threshold.

The final verdict is PASS only when cohort/provenance/completeness and **all five** gates pass. Each failed gate lists its counts, denominator, the IDs involved, and why measurements were not available. Any missing required evidence is explicitly an insufficient/failed gate, not a waived threshold. Keep raw per-pair data and produce both human-readable and machine-readable summaries.

## Test-only implementation contract

Implement pure, deterministic, offline standard-library routines in `tools/identity_bench/head/quality.py`, with additive synthetic tests in `test_quality.py`; update `tools/identity_bench/head/README.md`. Reuse IDS-2/IDS-3 formats and classification instead of building a new private fixture set or altering `probe.py` thresholds. Test the boundary (18/20 whole-head-and-pose, 14/20 hair and seam, 10% scale), immediately-below-threshold failures, denominator retention for failed/absent rows, one-vote and two-vote disagreement, abstention, missing/false licence, profile-unscorable identity, contradictory class/success labels, edit-fence tampering and an all-frame/empty-outside mask. Synthetic passing records prove **the evaluator**, not an actual head-swap backend.

Requested offline checks (must be confirmed in hosted exact-head CI or a clearly linked exact-SHA runner log; green unrelated Android CI is not equivalent):

```sh
python3 -m unittest tools.identity_bench.head.test_quality
python3 -m unittest tools.identity_bench.head.test_probe
```

The original IDS-3 **four-pair pilot** in `tools/identity_bench/head/results/results.json` remains separately attributed to IDS-3 and **does not satisfy** the 20-pair real-backend acceptance threshold. If evidence cannot be gathered, write `NOT MEASURED`; never invent output quality or a pass.
