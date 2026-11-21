"""Optional: score a real local GGUF model on the host with llama-cpp-python (grammar-constrained JSON).

    python run_model.py --gguf /path/model.gguf --name qwen3-1.7b-q8_0 [--limit N]

Writes results/model_<name>.json. Needs `pip install llama-cpp-python`; downloads nothing itself.
Numbers are HOST CPU numbers, never S24+ numbers. If the library or file is missing the script exits
without writing a result, so an unmeasured model is never reported as measured.
"""
import argparse
import json
import platform
import time
from pathlib import Path

import bench

SCHEMA = {
    "type": "object",
    "properties": {
        "intent": {"enum": ["filter", "question", "unsupported"]},
        "people": {"type": "array", "items": {"type": "string"}},
        "exclude_people": {"type": "array", "items": {"type": "string"}},
        "place": {"type": ["string", "null"]},
        "date_from": {"type": ["string", "null"]},
        "date_to": {"type": ["string", "null"]},
        "media": {"enum": ["any", "photo", "video"]},
        "tags": {"type": "array", "items": {"type": "string"}},
        "exclude_tags": {"type": "array", "items": {"type": "string"}},
        "unresolved": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["intent"],
}


def prompt(ctx, shots, text):
    head = (
        "Convert a photo-library search request (English or Dutch) into one JSON object.\n"
        f"Today is {ctx['reference_date']}. Known people: {', '.join(ctx['people'])}. "
        f"Known places: {', '.join(ctx['places'])} (Dutch spellings like Lissabon map to the English name). "
        f"Allowed tags: {', '.join(ctx['tags'])}.\n"
        "Rules: use only known people, places and tags. A tag, person or place that is not known goes in "
        "\"unresolved\" instead. Dates are ISO yyyy-mm-dd ranges (date_from/date_to). Negated things go in "
        "exclude_people or exclude_tags (\"nobody/zonder mensen\" is exclude_tags [\"people\"]). A question about "
        "the library is intent \"question\"; anything that is not a search is intent \"unsupported\".\n\n")
    for s in shots:
        head += f"Request: {s['text']}\nJSON: {json.dumps(s['expected'], ensure_ascii=False)}\n\n"
    return head + f"Request: {text}\nJSON: "


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gguf", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args()
    from llama_cpp import Llama, LlamaGrammar  # noqa: import here so the module imports without it

    ctx = bench.load_context()
    shots = bench.load_fewshot()
    queries = bench.load_queries()[: a.limit]
    llm = Llama(model_path=a.gguf, n_ctx=2048, n_threads=a.threads, verbose=False)
    grammar = LlamaGrammar.from_json_schema(json.dumps(SCHEMA))
    preds, timings = {}, []
    for q in queries:
        t0 = time.perf_counter()
        r = llm(prompt(ctx, shots, q["text"]), max_tokens=220, temperature=0.0, grammar=grammar, stop=["\n\n"])
        dt = time.perf_counter() - t0
        txt = r["choices"][0]["text"]
        try:
            preds[q["id"]] = json.loads(txt)
        except json.JSONDecodeError:
            preds[q["id"]] = None
        u = r["usage"]
        timings.append({"id": q["id"], "seconds": round(dt, 3), "prompt_tokens": u["prompt_tokens"],
                        "completion_tokens": u["completion_tokens"]})
    summary = bench.summarise(queries, preds)
    secs = sorted(t["seconds"] for t in timings)
    out = {"method": f"{a.name} via llama-cpp-python, grammar-constrained JSON, temperature 0, {len(shots)}-shot",
           "is_model": True, "measured_on": f"host CPU {platform.machine()}, {a.threads} threads (NOT a handset)",
           "gguf_file": Path(a.gguf).name,
           "latency_seconds_per_query": {"median": secs[len(secs) // 2], "max": secs[-1]},
           "summary": summary, "timings": timings, "predictions": preds}
    Path(__file__).with_name("results").mkdir(exist_ok=True)
    Path(__file__).with_name("results").joinpath(f"model_{a.name}.json").write_text(
        json.dumps(out, indent=1, ensure_ascii=False) + "\n")
    print(json.dumps({k: summary[k] for k in ("n", "valid_json", "exact", "by_lang", "failures")}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
