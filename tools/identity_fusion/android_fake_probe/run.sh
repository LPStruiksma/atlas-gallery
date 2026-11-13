#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
stubs="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
command -v kotlinc >/dev/null || { echo "kotlinc required for this opt-in local smoke"; exit 2; }
command -v java >/dev/null || { echo "java required for this opt-in local smoke"; exit 2; }
source="$root/app/src/main/java/nl/atlas/gallery/featureface"
kotlinc "$source/FaceDiscoveryContract.kt" "$source/FaceDecodeBudget.kt" \
    "$source/FaceScanEngine.kt" "$source/AndroidMediaFaceFrameReader.kt" \
    "$source/AsyncFaceLocator.kt" \
    "$stubs/AndroidFakes.kt" "$stubs/UriFake.kt" "$stubs/LooperFake.kt" \
    "$stubs/GraphicsFakes.kt" "$stubs/Probe.kt" \
    -include-runtime -d "$work/reader.jar"
java -jar "$work/reader.jar"
echo "FAKE-ANDROID ONLY: not Android SDK, emulator, ML Kit, image benchmark or device approval"
