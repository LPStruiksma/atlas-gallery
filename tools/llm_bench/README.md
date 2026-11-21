# LLM-0 host benchmark (issue #671)

Host-only, synthetic. Not an Android integration, ships no model and makes no network call. Findings: [`docs/visual-search/LLM_0_FEASIBILITY.md`](../../docs/visual-search/LLM_0_FEASIBILITY.md).

- `data/queries.json`: 72 EN/NL search requests (36 pairs) with reviewed expected filters. `data/context.json`: invented people, places, tags and the reference date. `data/fewshot.json`: prompt examples, disjoint from the set.
- `bench.py`: filter schema, normaliser and scorer. `heuristic.py`: rule-based baseline (not a model).
- `run_baseline.py` writes `results/baseline.json`. `run_model.py` scores a local GGUF with llama-cpp-python (grammar-constrained JSON); results are host CPU numbers, never handset numbers. A model that was not run has no result file and counts as "not measured".
- Tests: `cd tools/llm_bench && python3 -m unittest -q` (standard library only).

Rules: no real names or library content, no weights in Git, no held-out files, and mock outputs are never labelled as model accuracy.
