# Offline caption benchmark

Standard-library-only orchestration, not a model/inference implementation. It never downloads or uses network.

    python3 tools/caption_bench/protocol.py make-fixtures --out /tmp/cap0-fixtures
    python3 tools/caption_bench/protocol.py check-manifest /tmp/cap0-fixtures/manifest.jsonl
    python3 -m unittest discover -s tools/caption_bench/tests -v

For an approved external JSONL manifest (`id`, absolute `image`, `sha256`, `rights_id`, `rights_url`) and local adapter accepting `--image PATH --prompt TEXT` and emitting `{"caption":"..."}`:

    python3 tools/caption_bench/protocol.py bench \
      --warmups 5 --runs 30 --timeout 300 \
      --prompt 'Describe this photo factually in one short Dutch sentence. Do not guess.' \
      --output /results/model-revision.jsonl /approved/manifest.jsonl \
      -- /isolated/adapter --model /models/pinned

Run inside a recorded network-disabled environment. RSS is host child-process RSS, not Android PSS. Capacity inputs are explicit assumptions/measurements:

    python3 tools/caption_bench/protocol.py capacity --images 150000 \
      --images-per-hour 1 --retry-multiplier 1 --caption-bytes 1 \
      --metadata-bytes 1 --model-bytes 0
