# Order-independent upper envelope for V5 gallery enrollment

## Why a single declared enrollment order is insufficient

Existing Lane C research compares one fixed gallery with its expanded
roster (`audit_gallery_growth.py`) and replays a *declared*
candidate-addition order (`audit_progressive_gallery.py`).

Neither answers the stronger question: **could a temporarily unsafe
person-identification review offer occur in *some other* addition
order, even if the declared order and final gallery both abstain?**

This matters when the People gallery grows one labeled individual at
a time. The encounter history, not only the endpoint model accuracy,
determines which potentially misleading suggestions a reviewer saw.

## Exact existential witness, no factorial search

`audit_all_enrollment_orders.py` returns a **per-probe, union-over-all-
possible-orders** risk envelope using the already-reviewed, scored
baseline and final candidate rosters. It inherits the existing strict
V5 audit requirements:

- Every probe has the **same complete** gallery candidate pool within
  each split; the expanded pool is a strict superset.
- Exact probe, person truth/Unknown, session, condition and all old
  candidate score *strings* are frozen.
- The original calibration determines a conservative similarity floor
  exactly once; no holdout-dependent threshold or ranking selection.
- Any optional independently reviewed lineage/event/subject sidecar
  must cover *every* probe and must not leak across splits. Reports
  retain only aggregate component counts.
- Only 1–64 newly added candidates per split are supported, within the
  existing bounded complete score CSV and candidate counts.

Consider a single probe that was **not** a false review offer at
baseline. If ANY intermediate enrollment prefix yields a new wrong
candidate offer, the highest-ranked *new* candidate at that prefix
must beat the complete baseline's top candidate and pass the
threshold/top-two margin. Enrolling that one new candidate first also
yields a wrong offer, because it can only have an **equal or larger**
score gap over the original baseline runner-up. Conversely, a
single-new-candidate witness gives a valid enrollment order: put
that new candidate first. Therefore the search over `N!` possible
full permutations reduces to checking each new candidate **once
per probe**, using the EXACT existing top-1/tie/runner-up semantics.

The recorded observations are split into known-person and Unknown:
already unsafe at baseline; safe at baseline; possibly *newly*
unsafe in at least one candidate order; newly unsafe in the actual
*final* complete snapshot; possible unsafe **only temporarily**
despite a safe final snapshot; number of different one-candidate
witnesses; distinct nominal sessions and, when provenance is provided,
declared connected source components. All relevant counts are
computed separately for calibration and untouched holdout.

### Explicit non-claims

The union of possible unsafe observations is **not** necessarily
reachable all at once under a *single* global candidate order.
The tool does NOT claim a simultaneously attainable maximum number
of wrong offers, a probability, statistically independent trial
groups, genuine chronology or a proof of exhaustive enumeration of
the actual ATLAS People database. The external candidate scores,
model/rights/decoding chain and source truth remain independently
unverified. These are conservative, conditional, model-free
counterfactual bounds, not validated rear-facing identification.

## Important synthetic counterexample

One fictional heldout Unknown probe has two baseline identities tied
at `0.91`, so it abstains. Require a minimum score gap of `0.05`.
Two added candidates have fixed scores `0.94` and `0.97`.

- **First 0.94, then 0.97:** no unsafe offer. First gap is
  `0.94-0.91=0.03`; after the second, gap is
  `0.97-0.94=0.03`; both abstain.
- **First 0.97, then 0.94:** the first gap is
  `0.97-0.91=0.06`, so an unsafe Unknown-person review offer
  appears temporarily, then disappears when `0.94` is added.

Both enrollments finish at the EXACT same roster and complete score
matrix. The order-free witness identifies the missing risk without
arbitrarily choosing or enumerating all rank permutations.

## Use

All three inputs are **already existing, local, anonymous, precomputed
score CSV** documents; only the first two are required:

```sh
python tools/identity_fusion/audit_all_enrollment_orders.py \
  baseline_scores.csv expanded_scores.csv \
  --min-top-two-margin 0.05 \
  --provenance-csv reviewed_equivalence_groups.csv \
  --require-provenance
```

The provenance flags can be omitted for a clearly marked weaker,
descriptive-only analysis. The tool never downloads, opens or executes
weights, captures, People databases or images; it neither writes nor
mutates the input CSVs. JSON output contains **no actual opaque
identifiers, individual photos, raw cosine scores, local filenames
or personal metadata**. Failures print a fixed path-free refusal.

Run fictional regressions:

```sh
python tools/identity_fusion/test_audit_all_enrollment_orders.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```

Real evaluation still requires an authenticated, legally admitted
person-ReID checkpoint, independently consented rear/partial and
lookalike Unknown photographs with verified capture/person/event
equivalence groups, frozen prospective calibration and gallery
enrollment, model preprocessing parity and measured Galaxy S24+
memory/latency/thermal performance. **No automatic identity
confirmation**; all offered matches require explicit human review.
