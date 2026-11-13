# Prospective open-set / rear-pose validation-size audit

This optional, **offline, Python-standard-library-only** companion does
**not** change frozen V5 pose-study protocols v1–v4, their threshold, or
their acceptance decisions. It does not process photographs, weights,
scores, embeddings, identifiers or private databases.

## A real statistical trap in rear-facing Unknown reviews

Twenty different People can share the same photo event; 100 unknown-person
frames can share one original photograph. Neither should be passed to a
Wilson calculation as independent samples. A typed lineage/event/session
component audit already exists in V5; use its conservative connected
components as the denominator *only after independently checking source
provenance*. Different declared component IDs do not establish physical
independence.

Another trap is **simultaneous testing**. Six important rear/partial
Unknown intersections plus one overall criterion, each examined at
one-sided 95% separately, do **not** jointly offer 95% coverage. If these
seven tests are fixed *before data inspection*, Bonferroni uses one-sided
alpha 0.05/7 per test. This familywise correction is conservative even
for overlapping strata, but cannot correct post-hoc condition selection,
threshold tuning or inspecting many models then reporting the winner.

Run a **hypothetical, aggregate-only** example:

```sh
python tools/identity_fusion/plan_reid_unknown_budget.py
python tools/identity_fusion/plan_reid_unknown_budget.py \
  --checks 7 --max-errors 2 --observations 100 \
  --components 1 --error-components 1
python tools/identity_fusion/test_plan_reid_unknown_budget.py
```

For a predeclared 5% maximum unknown false-offer rate and **zero errors**,
the one-sided Wilson95 threshold needs at least **52 genuinely
independent components for one check**, but **115 for seven simultaneous
predeclared checks**. At 20 independent components, even zero observed
errors is inadequate. One error is allowed only at a larger denominator;
the tool binary-searches exact full-precision bounds and gives bounded
targets for 0–5 errors. The default seven-check scenario is illustrative,
not an assertion that the current study preregistered seven checks.

The optional observed numbers are **aggregate fabricated counts** and
cannot establish that study components were correctly clustered. This is
*precision/uncertainty arithmetic*, **not a statistical power calculation**:
it does not choose a representative sample, model prevalence, expected
accuracy, false-accept cost, or acquisition/pilot sample size. Wilson
coverage presumes independent, representative Bernoulli groups; a
component with ten photographs is one trial for any-error risk. The
software cannot verify capture provenance, fixed preregistration,
comparability across body poses/decades/cameras, sequential looks, legal
checkpoint rights, or Android inference timings.

A real study must use a separately attested pre-holdout protocol, held-out
Unknown people and genuinely distinct rear/partial photo sources, fixed
model/checkpoint and all exclusion/condition/model/threshold decisions.
Compare the **same held-out cohort** across face-only, body, session and
fusion variants. Unknown abstention is always available; even statistically
qualified suggestions are **human-review-only**, never person confirmation.
Qualifying synthetic arithmetic does not approve production use.
