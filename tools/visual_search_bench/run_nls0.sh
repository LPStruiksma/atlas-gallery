#!/usr/bin/env bash
# Reproduce NLS-0 (#104) end to end on a workstation (not in CI): text tower
# export, the licensed relational benchmark, and all metrics. Everything is
# cached under $VS_CACHE (default ~/.cache/atlas-vs), outside the repository.
# Needs ~20 GB disk (Open Images train annotations are ~5.6 GB), Python 3.11+
# and network access to storage.googleapis.com and
# open-images-dataset.s3.amazonaws.com. Run from anywhere; reads the app's
# shipped SigLIP 2 / YuNet / BlazePose assets from this checkout.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
C="${VS_CACHE:-$HOME/.cache/atlas-vs}"
mkdir -p "$C"/{models,onnx,data,nls0_images,nls0_work}

if [ ! -x "$C/venv/bin/python" ]; then
  python3 -m venv "$C/venv"
  "$C/venv/bin/pip" install -q -r "$HERE/requirements.txt"
fi
PY="$C/venv/bin/python -I"

fetch() { # url dest [sha256]
  [ -f "$2" ] || { curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }
  [ -z "${3:-}" ] || echo "$3  $2" | sha256sum -c --quiet
}
for k in siglip2_b16_224 gemma_tokenizer; do
  read -r url sha < <($PY -c "import json,sys;d=json.load(open('$HERE/models.json'))['$k'];print(d['url'],d['sha256'])")
  fetch "$url" "$C/models/$(basename "$url")" "$sha"
done

OI=https://storage.googleapis.com/openimages
for u in v7/oidv7-class-descriptions.csv \
         v7/oidv7-train-annotations-human-imagelabels.csv v6/oidv6-train-annotations-bbox.csv \
         2018_04/train/train-images-boxable-with-rotation.csv \
         v7/oidv7-val-annotations-human-imagelabels.csv v5/validation-annotations-bbox.csv \
         2018_04/validation/validation-images-with-rotation.csv \
         v7/oidv7-test-annotations-human-imagelabels.csv v5/test-annotations-bbox.csv \
         2018_04/test/test-images-with-rotation.csv; do
  fetch "$OI/$u" "$C/data/$(basename "$u")"
done

# 1. Text tower: PyTorch port -> ONNX fp32 / int8; image tower fp32 for the int8 check.
$PY "$HERE/text_tower.py" --weights-dir "$C/models" --out-dir "$C/onnx"
[ -f "$C/onnx/siglip2_b16_224.fp32.onnx" ] || \
  $PY "$HERE/export_onnx.py" --weights-dir "$C/models" --out-dir "$C/onnx" --only siglip2_b16_224

# 2. Benchmark: the committed manifest and judgments are what build_relational.py
#    writes from these annotations (re-run it to check: the output must not change).
if [ "${NLS0_REBUILD:-0}" = 1 ]; then
  $PY "$HERE/build_relational.py" --oi-dir "$C/data" --out-dir "$HERE/data"
fi
$PY "$HERE/fetch_images.py" --manifest "$HERE/data/nls0_manifest.csv" \
  --metadata "$C/data/train-images-boxable-with-rotation.csv" \
  --metadata "$C/data/validation-images-with-rotation.csv" \
  --metadata "$C/data/test-images-with-rotation.csv" \
  --cache "$C/nls0_images" --out "$C/nls0_work/licensed.csv"

# 3. Metrics.
$PY "$HERE/nls_bench.py" --images "$C/nls0_images" --onnx-dir "$C/onnx" --weights-dir "$C/models" \
  --work "$C/nls0_work" --out "$C/nls0_results.json"
echo "results: $C/nls0_results.json"
