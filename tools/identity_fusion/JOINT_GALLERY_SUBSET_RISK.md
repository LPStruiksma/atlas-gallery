# Exact simultaneously attainable gallery-size false-offer risk — V5

## Why this complements the prior order-independent envelope

A prior Lane C source-only audit, `audit_all_enrollment_orders.py`,
asks whether **each** reviewed probe *could ever* become an unsafe
review suggestion under **any** candidate-enrollment sequence. Its
exact per-probe existential envelope may count incompatible risks
together: a distinct order might be required to harm each probe.
It explicitly does **not** demonstrate that one enrolled gallery
simultaneously exposes all of those probes.

`audit_joint_enrollment_peak.py` answers the separate question:

**At any one reachable gallery size, what is the largest simultaneous
number of newly unsafe Unknown and known-person review offers?**

Any subset of added candidates is a prefix of at least one complete
enrollment order. Therefore checking **all subsets**, rather than
all `N!` permutations, yields an exact worst-case simultaneous count
for each number of added candidates. This is an exact finite score
counterfactual, NOT a real-world probability or an algorithm to
choose the actual gallery enrollment order.

## Strict, bounded input contract

Requires exactly the same two complete, anonymous scored-gallery CSVs
as prior `audit_gallery_growth.py`, optionally with the independent
five-column reviewed lineage/event/subject provenance sidecar.
Its existing checks require identical reviewed probes, truth identities,
condition labels, sessions and all original score *strings*;
full candidate roster per probe and strict inclusion of the original
gallery in the expanded gallery; no calibration/holdout ID leakage;
at least 50 calibration known and 80 Unknown reviewed cases, and
conservative baseline-only known/Unknown/session Wilson guards.
All later measurements reuse ONE baseline calibration score floor,
with optional frozen top-two margin. Holdout cannot select a threshold.

For computational and interpretive safety:

- **1–10** new gallery candidate identities per split, not arbitrary
  factorial/flooded input. Each split has at most **1,024** candidate
  subsets including the empty baseline.
- At most **200,000 per-probe subset states per split**, and per-source
  score count limits inherited from prior tests.
- Each subset's candidate best score, exact ties and runner-up gap are
  reproduced using the existing independently tested incremental rank
  update. Before reporting, each split's complete endpoint and union
  of per-probe possible unsafe offers **must match independent** final
  snapshot and all-order envelope evaluations. Disagreement fails closed.
- Output is a per-gallery-size maximum for **newly unsafe Unknown
  observations**, **newly unsafe known observations**, **combined
  simultaneous** newly unsafe observations, and distinct nominal
  sessions; optional supplied provenance yields counts of affected
  declared transitive connected components. Already-unsafe baseline
  observations are excluded from the *newly* unsafe numerator.
- The Unknown peak and the known peak may occur in *different*
  candidate subsets. **Only** the separately reported combined maximum
  is guaranteed reachable for both classes at one same subset.
  Likewise, separately reported session and component maxima need
  not share a common subset. **No identities, scores, subset member
  identities or personal filenames** are ever emitted.
- Every output explicitly refuses to claim actual image lineage
  independence, prospective enrollment chronology, deployment-ready
  accuracy, identity probability or automatic Person confirmation.

## Synthetic mutually incompatible-risk counterexample

Two invented, reviewed Unknown probes begin with TWO baseline
lookalike candidates tied at similarity `0.90`. Each therefore
abstains. The locked cutoff is `0.90` and required top-two score
margin is `0.05`.

| Hypothetical added candidate | Unknown probe A | Unknown probe B |
| --- | --- | --- |
| New 0 only | 0.96 — unsafe offer | 0.93 — abstain |
| New 1 only | 0.93 — abstain | 0.96 — unsafe offer |
| Both | top 0.96 / second 0.93 — abstain | top 0.96 / second 0.93 — abstain |

**Individual existentially unsafe probes: 2.**

**Maximum number of newly unsafe Unknown observations at any single
reachable gallery state: 1.**

**Newly unsafe Unknown observations at full final gallery: 0.**

Thus a per-probe worst-case union is **not** the simultaneous joint
worst case. The opposite confusion is also unsafe: an apparently
safe final gallery conceals one transient false review offer for
either enrollment order. Test cases cross-check the independent
progressive replay and order-free witness audit.

An additional fictional example has 4 Unknown + 2 known errors caused
by the same new candidate, in which the simultaneous peak of **6**
is indeed reachable at one subset. Provenance collapses four correlated
Unknown observations to one declared connected source component, not
four independent validation trials.

## Offline usage

Only use scores that were previously computed lawfully and reviewed
under a frozen study. Neither tool nor tests read photos, People
databases or pretrained checkpoints:

```sh
python tools/identity_fusion/audit_joint_enrollment_peak.py \
  baseline_scores.csv expanded_scores.csv \
  --min-top-two-margin 0.05 \
  --provenance-csv reviewed_provenance.csv --require-provenance

python tools/identity_fusion/test_audit_joint_enrollment_peak.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```

**Blockers remain:** independently authenticated exact checkpoint
weights and rights, realistic consented and non-correlated rear/partial
lookalike Unknown trials across years/clothing and contexts,
predeclared candidate chronology, and S24+ p95/memory/thermal
evidence. The output is a controlled synthetic-ready research audit,
never authorization for production or automatic identity links.
