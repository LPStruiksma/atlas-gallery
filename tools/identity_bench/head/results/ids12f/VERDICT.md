# IDS-12G — candidate verdict: FAIL (hosted replay pending)

Frozen rubric IDS-12A@096f0796, 20 synthetic pairs; no new inference. This expected report is derived from the committed IDS-12F evaluator report and deterministic decoding of both fixed judge records. It is **not yet a hosted evaluator execution** and must not be used as an admission receipt.

| Gate | Expected result | Evidence |
| --- | --- | --- |
| Cohort | PASS | 20 planned, 20 declared, no reported cohort/row problems |
| Whole-head and pose | FAIL | 9/20 (45%); requires 18/20 |
| Edit fence | PASS | 20/20, no failures or incomplete |
| Scale | PASS | 20/20 measured; median 5.6184%, maximum 10% |
| New hair/hairline | PASS | 40/40 candidate preferences; A judge 20, B judge 20 |
| Neck seam | PASS | 28/40 (70%, exactly cutoff); A judge 9, B judge 19; 5 ties from A, none from B; 10 split decisions |
| Profile identity | INSUFFICIENT_EVIDENCE | 0/20 scored by two independent profile-capable sources; visual preferences do not qualify |

The frozen evaluator gives FAIL when a complete cohort has any failed gate. The profile-identity evidence remains insufficient. The adapter dossier (#1090) is NOT VERIFIABLE; full_head stays UNAVAILABLE independently of these votes. The substantial seam disagreement is retained, not reconciled.

Cheapest prospective next lever: compare a larger crop (fixed other settings) against the frozen cohort to target whole-head/pose failures; this is a hypothesis, not a measured improvement. Separately obtain two genuinely profile-capable independent identity sources and verified adapter provenance before any route admission. No new runs authorized here.

## Reproduction gate

From repository root, hosted exact-head CI must run:

```sh
python3 tools/identity_bench/head/results/ids12f/assemble_votes.py
python3 -m unittest discover -s tools/identity_bench/head -p 'test_*.py' -v
d=tools/identity_bench/head/results/ids12f
rc=0
python3 tools/identity_bench/head/quality.py tools/identity_bench/head/cohort.json "$d/rows-with-votes.json" --json /tmp/ids12g-verdict.json || rc=$?
test "$rc" -eq 1
python3 -c 'import json,sys; assert json.load(open(sys.argv[1])) == json.load(open(sys.argv[2]))' "$d/verdict.json" /tmp/ids12g-verdict.json
```

The expected verdict file is a candidate until all these checks pass. Preserve original raw files and frozen rows. No local test or Python evaluator execution in the assembling ChatGPT turn.
