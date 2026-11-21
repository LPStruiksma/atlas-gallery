#!/usr/bin/env python3
"""SPEECH-0 host benchmark: separate English and Dutch WER and real-time factor.

Subcommands
  select    deterministic, seeded sample-ID selection from a metadata list
  score     score a hypothesis against a reference (WER pieces), offline
  evaluate  aggregate per-clip observations into a transcript-free report
  run       real ONNX Runtime Whisper decode over locally cached FLEURS parquet
  vad       Silero VAD pass over the same clips (speech fraction, RTF)

Privacy: reports hold counts, durations and timings only. Reference and
hypothesis text are never written to disk or printed. Standard library plus
numpy for `run`/`vad` (onnxruntime, tokenizers, pyarrow, soundfile).
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import platform
import resource
import statistics
import sys
import time
import unicodedata

NORMALISER = "nfc+casefold+strip-punct(unicode P*,S*)+collapse-ws v1"


def normalize(text: str) -> list[str]:
    t = unicodedata.normalize("NFC", text).casefold()
    out = []
    for ch in t:
        cat = unicodedata.category(ch)
        if ch in "[]'\u2019":  # drop, so "[w]hile" and "don't" stay one word
            continue
        out.append(" " if (cat[0] in "PS" or ch.isspace()) else ch)
    return "".join(out).split()


def edit_counts(ref: list[str], hyp: list[str]) -> tuple[int, int, int]:
    """Return (substitutions, deletions, insertions) from one minimal alignment."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        d[i][0] = i
    for j in range(1, m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = 0 if ref[i - 1] == hyp[j - 1] else 1
            d[i][j] = min(d[i - 1][j - 1] + cost, d[i - 1][j] + 1, d[i][j - 1] + 1)
    i, j, s, de, ins = n, m, 0, 0, 0
    while i or j:
        if i and j and d[i][j] == d[i - 1][j - 1] + (0 if ref[i - 1] == hyp[j - 1] else 1):
            s += 0 if ref[i - 1] == hyp[j - 1] else 1
            i, j = i - 1, j - 1
        elif i and d[i][j] == d[i - 1][j] + 1:
            de += 1
            i -= 1
        else:
            ins += 1
            j -= 1
    return s, de, ins


def score_pair(reference: str, hypothesis: str) -> dict:
    ref, hyp = normalize(reference), normalize(hypothesis)
    s, de, ins = edit_counts(ref, hyp)
    return {"ref_words": len(ref), "hyp_words": len(hyp), "sub": s, "del": de, "ins": ins}


def select_ids(metadata: list[dict], per_language: int, seed: str) -> dict[str, list[str]]:
    """Deterministic order-independent choice: smallest hash(seed|lang|id) wins."""
    by_lang: dict[str, list[dict]] = {}
    for row in metadata:
        by_lang.setdefault(row["lang"], []).append(row)
    chosen = {}
    for lang, rows in sorted(by_lang.items()):
        ranked = sorted(rows, key=lambda r: hashlib.sha256(f"{seed}|{lang}|{r['id']}".encode()).hexdigest())
        chosen[lang] = sorted(str(r["id"]) for r in ranked[:per_language])
    return chosen


def _median(xs):
    return statistics.median(xs) if xs else None


def _p95(xs):
    if not xs:
        return None
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(0.95 * (len(xs) - 1))))]


def aggregate(observations: list[dict]) -> dict:
    """Per-language micro and macro WER, RTF. Empty references are counted, not scored."""
    out = {}
    for lang in sorted({o["lang"] for o in observations}):
        rows = [o for o in observations if o["lang"] == lang]
        scored = [o for o in rows if o["ref_words"] > 0]
        empty = [o for o in rows if o["ref_words"] == 0]
        n = sum(o["ref_words"] for o in scored)
        errs = sum(o["sub"] + o["del"] + o["ins"] for o in scored)
        audio = sum(o["audio_s"] for o in rows)
        wall = sum(o["wall_s"] for o in rows if o.get("wall_s") is not None)
        clip_wer = [(o["sub"] + o["del"] + o["ins"]) / o["ref_words"] for o in scored]
        rtfs = [o["wall_s"] / o["audio_s"] for o in rows if o.get("wall_s") and o["audio_s"] > 0]
        out[lang] = {
            "clips": len(rows),
            "scored_clips": len(scored),
            "empty_reference_clips": len(empty),
            "hallucinated_on_empty": sum(1 for o in empty if o["hyp_words"] > 0),
            "audio_hours": round(audio / 3600, 5),
            "ref_words": n,
            "substitutions": sum(o["sub"] for o in scored),
            "deletions": sum(o["del"] for o in scored),
            "insertions": sum(o["ins"] for o in scored),
            "micro_wer": round(errs / n, 4) if n else None,
            "macro_wer": round(sum(clip_wer) / len(clip_wer), 4) if clip_wer else None,
            "rtf_total": round(wall / audio, 4) if audio and wall else None,
            "rtf_clip_median": round(_median(rtfs), 4) if rtfs else None,
            "rtf_clip_p95": round(_p95(rtfs), 4) if rtfs else None,
        }
    return out


def environment() -> dict:
    env = {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "normaliser": NORMALISER,
    }
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    env["cpu_model"] = line.split(":", 1)[1].strip()
                    break
        with open("/proc/meminfo") as f:
            env["mem_total_gb"] = round(int(f.readline().split()[1]) / 1048576, 1)
    except OSError:
        pass
    for mod in ("numpy", "onnxruntime", "tokenizers", "pyarrow", "soundfile"):
        try:
            env[mod] = __import__(mod).__version__
        except Exception:
            env[mod] = None
    return env


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ----------------------------------------------------------------- audio / mel
def decode_wav(raw: bytes):
    import numpy as np
    import soundfile as sf

    data, sr = sf.read(io.BytesIO(raw), dtype="float32", always_2d=True)
    data = data.mean(axis=1)
    if sr != 16000:  # linear resample; FLEURS is already 16 kHz
        x = np.arange(0, len(data), sr / 16000)
        data = np.interp(x, np.arange(len(data)), data).astype("float32")
    return data


def log_mel(audio, mel_filters):
    """Whisper log-mel: n_fft 400, hop 160, 80 mels, padded to 30 s -> (80, 3000)."""
    import numpy as np

    n_samples = 480000
    audio = np.pad(audio[:n_samples], (0, max(0, n_samples - len(audio)))).astype("float32")
    win = np.hanning(401)[:-1].astype("float32")
    padded = np.pad(audio, (200, 200), mode="reflect")
    n_frames = 1 + (len(padded) - 400) // 160
    idx = np.arange(400)[None, :] + 160 * np.arange(n_frames)[:, None]
    spec = np.abs(np.fft.rfft(padded[idx] * win, axis=1)) ** 2
    spec = spec[:-1].T  # drop last frame -> (201, 3000)
    mel = mel_filters @ spec
    log = np.log10(np.maximum(mel, 1e-10))
    log = np.maximum(log, log.max() - 8.0)
    return ((log + 4.0) / 4.0).astype("float32")


# ----------------------------------------------------------------- whisper
def _looping(tokens: list[int], n: int = 4, times: int = 3) -> bool:
    """True when the last n tokens repeat `times` times in a row (greedy runaway loop)."""
    need = n * times
    if len(tokens) < need:
        return False
    tail = tokens[-need:]
    return all(tail[i] == tail[i % n] for i in range(need))


class Whisper:
    LANG_TOKENS = {"en": "<|en|>", "nl": "<|nl|>"}

    def __init__(self, model_dir: str, enc: str, dec: str, mel_filters, threads: int):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self.mel = mel_filters
        t0 = time.perf_counter()
        self.enc = ort.InferenceSession(os.path.join(model_dir, enc), so, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(os.path.join(model_dir, dec), so, providers=["CPUExecutionProvider"])
        self.load_s = time.perf_counter() - t0
        self.tok = Tokenizer.from_file(os.path.join(model_dir, "tokenizer.json"))
        gen = json.load(open(os.path.join(model_dir, "generation_config.json")))
        self.eos = self.tok.token_to_id("<|endoftext|>")
        self.sot = self.tok.token_to_id("<|startoftranscript|>")
        self.transcribe = self.tok.token_to_id("<|transcribe|>")
        self.nots = self.tok.token_to_id("<|notimestamps|>")
        self.suppress = np.array(gen.get("suppress_tokens", []), dtype="int64")
        self.begin_suppress = np.array(gen.get("begin_suppress_tokens", []), dtype="int64")
        self.past_names = [i.name for i in self.dec.get_inputs() if i.name.startswith("past_key_values")]
        self.heads, self.head_dim = self.dec.get_inputs()[2].shape[1], self.dec.get_inputs()[2].shape[3]
        self.np = np
        self.loop_stops = 0

    def transcribe_audio(self, audio, lang: str, max_tokens: int = 200) -> str:
        np = self.np
        feats = log_mel(audio, self.mel)[None]
        enc_out = self.enc.run(None, {"input_features": feats})[0]
        prompt = [self.sot, self.tok.token_to_id(self.LANG_TOKENS[lang]), self.transcribe, self.nots]
        empty = np.zeros((1, self.heads, 0, self.head_dim), dtype="float32")
        past = {n: empty for n in self.past_names}
        tokens, ids, use_cache = [], np.array([prompt], dtype="int64"), False
        for step in range(max_tokens):
            feed = {"input_ids": ids, "encoder_hidden_states": enc_out, **past,
                    "use_cache_branch": np.array([use_cache])}
            outs = self.dec.run(None, feed)
            logits = outs[0][0, -1].copy()
            logits[self.suppress] = -1e9
            if step == 0:
                logits[self.begin_suppress] = -1e9
            nxt = int(logits.argmax())
            names = [o.name for o in self.dec.get_outputs()][1:]
            for name, val in zip(names, outs[1:]):
                key = name.replace("present", "past_key_values")
                if ".encoder." in key and use_cache:
                    continue  # cached encoder KV is not re-emitted on the cache branch
                past[key] = val
            if nxt == self.eos:
                break
            tokens.append(nxt)
            if _looping(tokens):
                self.loop_stops += 1
                del tokens[-8:]  # drop the repeated tail
                break
            ids, use_cache = np.array([[nxt]], dtype="int64"), True
        return self.tok.decode(tokens, skip_special_tokens=True)


def load_clips(parquet_path: str, ids: set[str] | None, limit: int | None, seed: str, lang: str):
    import pyarrow.parquet as pq

    t = pq.read_table(parquet_path, columns=["id", "audio", "transcription"])
    # FLEURS sentence ids repeat across speakers, so key a clip by sentence id + row index
    rows = [{"id": f"{r['id']}#{i}", "lang": lang} for i, r in enumerate(t.select(["id"]).to_pylist())]
    keep = set(select_ids(rows, limit, seed)[lang]) if limit else {r["id"] for r in rows}
    if ids:
        keep &= ids
    out = []
    ids_col = t.column("id").to_pylist()
    pick = [i for i in range(t.num_rows) if f"{ids_col[i]}#{i}" in keep]
    for i, r in zip(pick, t.take(pick).to_pylist()):
        cid = f"{r['id']}#{i}"
        if cid in keep:
            out.append({"id": cid, "bytes": r["audio"]["bytes"], "ref": r["transcription"]})
    return sorted(out, key=lambda c: c["id"])


def cmd_run(a):
    import numpy as np

    pre = json.load(open(a.preprocessor))
    mel = np.array(pre["mel_filters"], dtype="float32")
    model = Whisper(a.model_dir, a.encoder, a.decoder, mel, a.threads)
    obs, per_lang_ids = [], {}
    for lang, path in (("en", a.en_parquet), ("nl", a.nl_parquet)):
        clips = load_clips(path, None, a.per_language, a.seed, lang)
        per_lang_ids[lang] = [c["id"] for c in clips]
        warm = decode_wav(clips[0]["bytes"])
        model.transcribe_audio(warm, lang)  # untimed warm-up so load/JIT cost is separate
        for c in clips:
            t0 = time.perf_counter()
            audio = decode_wav(c["bytes"])
            t1 = time.perf_counter()
            hyp = model.transcribe_audio(audio, lang)
            t2 = time.perf_counter()
            sc = score_pair(c["ref"], hyp)
            obs.append({"id": c["id"], "lang": lang, "audio_s": round(len(audio) / 16000, 3),
                        "decode_s": round(t1 - t0, 4), "wall_s": round(t2 - t1, 4), **sc})
    rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    report = {
        "schema": "speech-bench-report/1",
        "model": {"name": a.name, "revision": a.revision,
                  "files": {f: sha256_file(os.path.join(a.model_dir, f))
                            for f in (a.encoder, a.decoder, "tokenizer.json")},
                  "quantisation": a.quant},
        "dataset": {"name": "google/fleurs", "revision": a.dataset_revision, "split": "test",
                    "configs": {"en": "en_us", "nl": "nl_nl"}, "licence": "CC-BY-4.0",
                    "seed": a.seed, "per_language": a.per_language, "sample_ids": per_lang_ids},
        "params": {"decode": "greedy, forced language, no timestamps, suppress_tokens from generation_config",
                   "threads": a.threads, "provider": "CPUExecutionProvider", "max_tokens": 200,
                   "timing": "wall = log-mel + encoder + decoder loop, excludes wav decode and model load"},
        "model_load_s": round(model.load_s, 3),
        "peak_rss_mb": round(rss_mb, 1),
        "loop_guard_stops": model.loop_stops,
        "environment": environment(),
        "results": aggregate(obs),
        "observations": [{k: v for k, v in o.items()} for o in obs],
    }
    json.dump(report, open(a.out, "w"), indent=1, sort_keys=True)
    print(json.dumps({"model": a.name, "results": report["results"], "peak_rss_mb": report["peak_rss_mb"]}, indent=1))


# ----------------------------------------------------------------- silero vad
def cmd_vad(a):
    import numpy as np
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.intra_op_num_threads = a.threads
    sess = ort.InferenceSession(a.vad_model, so, providers=["CPUExecutionProvider"])
    names = [i.name for i in sess.get_inputs()]
    result = {"model": a.vad_model_name, "sha256": sha256_file(a.vad_model), "inputs": names, "per_language": {}}
    for lang, path in (("en", a.en_parquet), ("nl", a.nl_parquet)):
        clips = load_clips(path, None, a.per_language, a.seed, lang)
        total_s = speech_s = wall = 0.0
        for c in clips:
            audio = decode_wav(c["bytes"])
            state = np.zeros((2, 1, 128), dtype="float32")
            ctx = np.zeros((1, 64), dtype="float32")
            t0 = time.perf_counter()
            probs = []
            for i in range(0, len(audio) - 511, 512):
                x = np.concatenate([ctx, audio[None, i:i + 512]], axis=1)
                out, state = sess.run(None, {"input": x, "state": state, "sr": np.array(16000, dtype="int64")})
                probs.append(float(out[0, 0]))
                ctx = x[:, -64:]
            wall += time.perf_counter() - t0
            total_s += len(audio) / 16000
            speech_s += sum(p > 0.5 for p in probs) * 512 / 16000
        result["per_language"][lang] = {
            "clips": len(clips), "audio_s": round(total_s, 1),
            "speech_fraction_p>0.5": round(speech_s / total_s, 4), "vad_rtf": round(wall / total_s, 5)}
    result["note"] = "VAD effect on WER is NOT measured; FLEURS clips are read speech with little silence."
    json.dump(result, open(a.out, "w"), indent=1, sort_keys=True)
    print(json.dumps(result, indent=1))


# ----------------------------------------------------------------- offline cli
def cmd_select(a):
    meta = [json.loads(x) for x in open(a.metadata) if x.strip()]
    print(json.dumps(select_ids(meta, a.per_language, a.seed), indent=1))


def cmd_score(a):
    print(json.dumps(score_pair(a.reference, a.hypothesis)))


def cmd_evaluate(a):
    obs = [json.loads(x) for x in open(a.observations) if x.strip()]
    report = {"environment": environment(), "results": aggregate(obs)}
    if a.provenance:
        report["provenance"] = json.load(open(a.provenance))
    print(json.dumps(report, indent=1, sort_keys=True))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("select"); s.add_argument("metadata"); s.add_argument("--per-language", type=int, default=30)
    s.add_argument("--seed", default="speech-0-v1"); s.set_defaults(fn=cmd_select)
    s = sub.add_parser("score"); s.add_argument("reference"); s.add_argument("hypothesis"); s.set_defaults(fn=cmd_score)
    s = sub.add_parser("evaluate"); s.add_argument("observations"); s.add_argument("--provenance")
    s.set_defaults(fn=cmd_evaluate)
    for name, fn in (("run", cmd_run), ("vad", cmd_vad)):
        s = sub.add_parser(name)
        s.add_argument("--en-parquet", required=True); s.add_argument("--nl-parquet", required=True)
        s.add_argument("--per-language", type=int, default=30); s.add_argument("--seed", default="speech-0-v1")
        s.add_argument("--threads", type=int, default=4); s.add_argument("--out", required=True)
        if name == "run":
            s.add_argument("--name", required=True); s.add_argument("--revision", required=True)
            s.add_argument("--quant", default="int8"); s.add_argument("--dataset-revision", required=True)
            s.add_argument("--model-dir", required=True); s.add_argument("--preprocessor", required=True)
            s.add_argument("--encoder", default="onnx/encoder_model_quantized.onnx")
            s.add_argument("--decoder", default="onnx/decoder_model_merged_quantized.onnx")
        else:
            s.add_argument("--vad-model", required=True); s.add_argument("--vad-model-name", required=True)
        s.set_defaults(fn=fn)
    a = p.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
