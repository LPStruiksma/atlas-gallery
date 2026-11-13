#!/usr/bin/env bash
# Reproduce the VS-S0 spike end to end on a workstation (not in CI).
# Everything is cached under $VS_CACHE (default ~/.cache/atlas-vs), outside
# the repository: weights, ONNX exports, images and embeddings never get
# committed. Needs ~8 GB disk, Python 3.11+ and network access to
# github.com, media.githubusercontent.com and storage.googleapis.com and
# open-images-dataset.s3.amazonaws.com.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
C="${VS_CACHE:-$HOME/.cache/atlas-vs}"
mkdir -p "$C"/{models,zoo,onnx,images,work,data}

if [ ! -x "$C/venv/bin/python" ]; then
  python3 -m venv "$C/venv"
  "$C/venv/bin/pip" install -q -r "$HERE/requirements.txt"
fi
PY="$C/venv/bin/python -I"

fetch() { # url dest sha256
  [ -f "$2" ] || { curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }
  echo "$3  $2" | sha256sum -c --quiet
}
$PY - "$HERE/models.json" > "$C/fetch.tsv" <<'PYEOF'
import json, sys
for k, v in json.load(open(sys.argv[1])).items():
    if isinstance(v, dict):
        print(k, v["url"], v["sha256"], sep="\t")
PYEOF
while IFS=$'\t' read -r name url sha; do
  case "$name" in mediapipe_*) dir="$C/zoo" ;; *) dir="$C/models" ;; esac
  fetch "$url" "$dir/$(basename "$url")" "$sha"
done < "$C/fetch.tsv"

# The committed manifest pins the image set; fetch_images re-checks every
# image's licence against Open Images' own metadata before use.
OI=https://storage.googleapis.com/openimages
[ -f "$C/data/train-images-boxable-with-rotation.csv" ] || \
  curl -fsSL -o "$C/data/train-images-boxable-with-rotation.csv" "$OI/2018_04/train/train-images-boxable-with-rotation.csv"
$PY "$HERE/fetch_images.py" --manifest "$HERE/data/open_images_actions_v1.csv" \
  --metadata "$C/data/train-images-boxable-with-rotation.csv" --cache "$C/images" --out "$C/data/licensed.csv"

$PY "$HERE/export_onnx.py" --weights-dir "$C/models" --out-dir "$C/onnx"
$PY "$HERE/bench.py" --images "$C/images" --set "$C/data/licensed.csv" --onnx-dir "$C/onnx" \
  --zoo-dir "$C/zoo" --work "$C/work" --out "$C/results.json"
echo "results: $C/results.json"
