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

```bash
pip install numpy onnxruntime==1.23.2 tokenizers pyarrow soundfile
# FLEURS test parquet, dataset revision 70bb2e84b976b7e960aa89f1c648e09c59f894dd (CC-BY-4.0)
curl -L -o fleurs_en_us.parquet https://huggingface.co/api/datasets/google/fleurs/parquet/en_us/test/0.parquet
curl -L -o fleurs_nl_nl.parquet https://huggingface.co/api/datasets/google/fleurs/parquet/nl_nl/test/0.parquet
# model files from onnx-community/whisper-<size> at the pinned revision (see the feasibility doc):
#   onnx/encoder_model_quantized.onnx  onnx/decoder_model_merged_quantized.onnx  tokenizer.json  generation_config.json
# mel filters: openai/whisper-tiny preprocessor_config.json (the same 80-band bank for tiny, base and small)
python3 speech_bench.py run --en-parquet fleurs_en_us.parquet --nl-parquet fleurs_nl_nl.parquet \
  --per-language 30 --name whisper-tiny --revision <hf sha> --dataset-revision <hf sha> \
  --model-dir ./whisper-tiny --preprocessor preprocessor_config.json --out results/host_whisper-tiny.json
python3 speech_bench.py vad ... --vad-model silero/onnx/model.onnx --vad-model-name <name@rev> --out results/host_silero_vad.json
```

Run each model in its own process so peak RSS is per model. Keep downloads outside the repository.

## What a report contains

Per language: clip count, audio hours, reference word count, substitutions, deletions, insertions, micro and macro WER, RTF (total, clip median, clip p95). Plus model and dataset revisions, SHA-256 of every model file, decode parameters, thread count, environment, peak RSS, and the selected sample IDs.

## Rules the tool enforces

- Sample choice is a seeded hash of language and ID, so it does not depend on file order and is fixed before any model runs.
- One normaliser for every language: Unicode NFC, case-fold, drop `[ ] '`, other punctuation and symbols to spaces, collapse whitespace. Diacritics are kept.
- Empty references are counted but excluded from WER; a non-empty hypothesis for one is reported as a hallucination.
- Reports hold counts and timings only. Reference and hypothesis text, audio and transcripts are never written, printed or committed.
- RTF is wall seconds divided by audio seconds for log-mel, encoder and the decoder loop on a warmed session. WAV decode and model load are excluded and reported apart.
