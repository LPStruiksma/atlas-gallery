# Offline caption benchmark

Standard-library-only orchestration, **not** a model/inference implementation. It downloads nothing; run the supplied adapter in an externally enforced network-disabled environment.

    python3 tools/caption_bench/protocol.py make-fixtures --out /tmp/cap0-fixtures
    python3 tools/caption_bench/protocol.py check-manifest /tmp/cap0-fixtures/manifest.jsonl
    python3 -m unittest discover -s tools/caption_bench/tests -v

For an approved external JSONL manifest (id, absolute image, sha256, rights_id, rights_url) and a locally installed adapter that accepts --image PATH --prompt TEXT and emits a JSON object with a **non-empty** caption string:

    python3 tools/caption_bench/protocol.py bench \
      --discard-launches 5 --runs 30 --timeout 300 \
      --prompt 'Describe this photo factually in one short Dutch sentence. Do not guess.' \
      --output /results/model-revision.jsonl /approved/manifest.jsonl \
      -- /isolated/adapter --model /models/pinned

**These are per-request cold process-launch timings, not warm inference latency or model-resident throughput.** Each invocation starts and exits its own adapter process. The --discard-launches option discards preliminary launches but cannot warm a model for future launches (the old --warmups option was misleading and has been removed). Every recorded invocation includes process launch/model load, if any. Do not plug these cold timings into a warm background-indexing throughput estimate. CAP-1 needs a separate resident-process/streaming adapter benchmark and physical S24+ runs for that.

JSONL output marks each attempt success or failed, its return code, any adapter error, and the cold elapsed time. Only return-code-0 calls with non-empty string captions contribute to p50_cold_ms and p95_cold_ms; the summary gives attempted, success, failed and discarded trial counts. When every caption fails the command exits 2, reports null latency percentiles and retains diagnostic rows. Timeouts are failures. **Peak process memory is NOT MEASURED**: cumulative RUSAGE_CHILDREN.ru_maxrss differences are not per-child memory, and Android PSS requires separate tools.

Capacity inputs are explicit assumptions or independent measurements; retry-multiplier must be finite and at least 1, images-per-hour finite and positive:

    python3 tools/caption_bench/protocol.py capacity --images 150000 \
      --images-per-hour 1 --retry-multiplier 1 --caption-bytes 1 \
      --metadata-bytes 1 --model-bytes 0

The commands above are reproducibility instructions, **not evidence that a model was executed**. Synthetic fixtures check I/O contracts only, not model caption quality, Dutch or licences.
