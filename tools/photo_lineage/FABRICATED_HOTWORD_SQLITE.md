# Actual SQLite preflight under fabricated hot-word augmentation

Lane B, issue #83. Research-only numeric posting pressure test.
Not representative photo accuracy, real ORB term frequencies, independent
camera exposure proof or S24+ performance.

## Reproduce

Run from the repository root:

    python -m tools.photo_lineage.fabricated_hotword_sqlite --fake-docs 120

Within a disposable TemporaryDirectory, create two ordinary
procedurally textured 256px PNGs: a query and its exact synthetic
source copy. Make three separate *actual SQLite indexes* via the
unchanged retrieval.open_db and query them through the unchanged
retrieval.shortlist. Add 10 to 5000 numeric-only fake document IDs;
their photo paths do not exist and are never read or created.

Every fake ID has one synthetic cold word that is **not** in the
query. Counterfactual indexed variants add zero, one, or two query-hot
visual words to those fake IDs, well under the 256 supplementary-word
per-photo research budget. This does not simulate real ORB geometry;
it tests the exact SQL posting preflight and distinct-ID gate.

Let Q be the number of ORB terms in the synthetic query and D the
fabricated document count. Actual SQLite counts must satisfy:

| Fictional index | Query posting visits | Distinct candidate IDs |
|---|---:|---:|
| Baseline (no shared extras) | Q | 1 |
| One shared extra word | Q + D | D + 1 |
| Two shared extra words | Q + 2D | D + 1 |

In the two-word case, D fake IDs satisfy the numerical two-word
shortlist precondition. They remain nonexistent paths, so this is
NOT an observed human-review error. The only materialized hit is
the exact synthetic source, and exact inclusive budgets must retrieve
it at rank 1. A posting-visit budget one below the true count must
raise typed PostingBudgetExceeded(posting_visits). With extras, a
distinct-ID limit one below the true count must raise
PostingBudgetExceeded(distinct_documents). Both are incomplete
searches, never evidence that no relative exists.

## Conditional 150,000-document arithmetic

No actual 150k index is built. Under the extreme, clearly synthetic
assumption that *all* other 150,000 documents acquired the query-hot
words, one extra shared word produces about Q+150,000 postings,
usually under the 250,000 posting-visit budget, but 150,001 distinct
IDs over the 100,000-document budget. Two added shared words yield
Q+300,000 postings, exceeding both budget limits. A per-photo
256-word storage ceiling cannot guarantee a bounded query if just
one or two of those words become extremely common. This is a
counterfactual adversarial pattern, NOT measured gallery prevalence.

Do not weaken the completeness gates or treat an exhausted query as
negative source evidence. Raising query budgets, dropping common
words, or shipping augmented fingerprints requires proper real
word-frequency, source-exposure and device tests, including
separately captured same-camera shutter hard negatives.

Four synthetic-only tests execute real temporary SQLite preflights
at exact budget boundaries, reject invalid sizes and verify
zero persistent/camera files, no media copies or uploads, and
false-exposure calibration flags. No Android, V4/V5, release workflow,
production index, private People/Gallery/Settings data, signing
secrets, model or threshold changed.
