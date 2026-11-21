# speech_bench (SPEECH-0)

Host-only benchmark for local English and Dutch speech-to-text. It supports the feasibility study in [`docs/visual-search/SPEECH_0_FEASIBILITY.md`](../../docs/visual-search/SPEECH_0_FEASIBILITY.md). Nothing here ships in the app.

## Offline checks (no downloads, standard library only)

```bash
cd tools/speech_bench
python3 -m unittest test_speech_bench
python3 speech_bench.py select fixtures/metadata.jsonl --per-language 3 --seed speech-0-v1
python3 speech_bench.py evaluate fixtures/observations.jsonl --provenance fixtures/provenance.json
```

The fixtures are synthetic. They check the WER and RTF arithmetic and say nothing about any model.

## Real run (needs network once, then a local cache)

Every input is pinned. Expected SHA-256 values match the `sha256` fields in `results/*.json` (model files) and are listed here for the parquet and preprocessor.

```bash
pip install numpy onnxruntime==1.23.2 tokenizers pyarrow soundfile
mkdir -p cache && cd cache
HF=https://huggingface.co

# FLEURS test split (CC-BY-4.0). Parquet conversion branch pinned to a commit; source dataset revision 70bb2e84b976b7e960aa89f1c648e09c59f894dd
PQ=168de341b3db6859a9bac1c50a2ef5e3b47647e0
curl -L -o fleurs_en_us.parquet "$HF/datasets/google/fleurs/resolve/$PQ/en_us/test/0000.parquet"
curl -L -o fleurs_nl_nl.parquet "$HF/datasets/google/fleurs/resolve/$PQ/nl_nl/test/0000.parquet"
# sha256 prefixes: en_us 6428a4d04d3aac29, nl_nl a89456e5219b9cd3

# Mel filter bank (same 80-band bank for tiny, base, small), pinned openai/whisper-tiny revision
curl -L -o preprocessor_config.json "$HF/openai/whisper-tiny/resolve/169d4a4341b33bc18d8881c4b69c2e104e1cc0af/preprocessor_config.json"
# sha256 prefix: 9b5cd03a36fbb8a6

# Models: onnx-community conversions at pinned revisions
fetch() {  # fetch <size> <revision>
  mkdir -p "whisper-$1/onnx"
  for f in onnx/encoder_model_quantized.onnx onnx/decoder_model_merged_quantized.onnx tokenizer.json generation_config.json; do
    curl -L -o "whisper-$1/$f" "$HF/onnx-community/whisper-$1/resolve/$2/$f"
  done
}
fetch tiny  ff4177021cc41f7db950912b73ea4fdf7d01d8e7
fetch base  27a07b4b52b3a4c5886134bb4320e93e1a4bbc7c
fetch small 36050c46d777d46dc4b5f43f6d90574fc38f8732
mkdir -p silero && curl -L -o silero/model.onnx "$HF/onnx-community/silero-vad/resolve/e71cae966052b992a7eca6b17738916ce0eca4ec/onnx/model.onnx"
sha256sum whisper-*/onnx/*.onnx silero/model.onnx   # compare with results/*.json

DS=70bb2e84b976b7e960aa89f1c648e09c59f894dd
run() {  # run <size> <revision>; one process per model so peak RSS is per model
  python3 ../speech_bench.py run --en-parquet fleurs_en_us.parquet --nl-parquet fleurs_nl_nl.parquet \
    --per-language 30 --seed speech-0-v1 --threads 4 --name "whisper-$1" --revision "$2" --dataset-revision $DS \
    --model-dir "whisper-$1" --preprocessor preprocessor_config.json --out "../results/host_whisper-$1.json"
}
run tiny  ff4177021cc41f7db950912b73ea4fdf7d01d8e7
run base  27a07b4b52b3a4c5886134bb4320e93e1a4bbc7c
run small 36050c46d777d46dc4b5f43f6d90574fc38f8732
python3 ../speech_bench.py vad --en-parquet fleurs_en_us.parquet --nl-parquet fleurs_nl_nl.parquet \
  --per-language 30 --seed speech-0-v1 --threads 4 --vad-model silero/model.onnx \
  --vad-model-name onnx-community/silero-vad@e71cae966052b992a7eca6b17738916ce0eca4ec --out ../results/host_silero_vad.json
```

Run from `tools/speech_bench`. `cache/` holds downloads and must not be committed. WER and the selected sample IDs reproduce exactly; RTF and peak RSS vary with the host.

## What a report contains

Per language: clip count, audio hours, reference word count, substitutions, deletions, insertions, micro and macro WER, RTF (total, clip median, clip p95). Plus model and dataset revisions, SHA-256 of every model file, decode parameters, thread count, environment, peak RSS, and the selected sample IDs.

## Rules the tool enforces

- Sample choice is a seeded hash of language and ID, so it does not depend on file order and is fixed before any model runs.
- One normaliser for every language: Unicode NFC, case-fold, drop `[ ] '`, other punctuation and symbols to spaces, collapse whitespace. Diacritics are kept.
- Empty references are counted but excluded from WER; a non-empty hypothesis for one is reported as a hallucination.
- Reports hold counts and timings only. Reference and hypothesis text, audio and transcripts are never written, printed or committed.
- RTF is wall seconds divided by audio seconds for log-mel, encoder and the decoder loop on a warmed session. WAV decode and model load are excluded and reported apart.
