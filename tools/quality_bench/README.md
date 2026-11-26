# QUALITY-0 generated benchmark

`bench.py` times a deterministic, decode-free Python analogue of the four-neighbour
Laplacian loop. It is a reproducibility/tooling check, **not** Kotlin, Android, S24+
or preference evidence. Fixtures come from the fixed LCG in the script; no photos,
datasets or third-party assets are included.

Run: `python3 tools/quality_bench/bench.py --width 256 --height 192 --warmups 10 --samples 50`.
The script reports host/Python, dimensions, warm-ups, p50/p95, and explicitly marks
memory not measured. Image decode, colour conversion, exposure histogram, faces and
embeddings are excluded.
