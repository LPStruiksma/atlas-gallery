"""Run the rule-based baseline on the synthetic EN/NL set and write results/baseline.json."""
import json
from pathlib import Path

import bench
import heuristic

ctx = bench.load_context()
queries = bench.load_queries()
preds = {q["id"]: heuristic.parse(q["text"], ctx) for q in queries}
summary = bench.summarise(queries, preds)
out = {"method": "rule-based heuristic (tools/llm_bench/heuristic.py)", "is_model": False,
       "measured_on": "host (no handset)", "reference_date": ctx["reference_date"],
       "summary": summary, "predictions": preds}
Path(__file__).with_name("results").mkdir(exist_ok=True)
Path(__file__).with_name("results").joinpath("baseline.json").write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n")
print(json.dumps({k: summary[k] for k in ("n", "valid_json", "exact", "by_lang", "failures")}, ensure_ascii=False, indent=1))
