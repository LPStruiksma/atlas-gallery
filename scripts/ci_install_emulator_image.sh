#!/usr/bin/env bash
# Install the emulator system image before android-emulator-runner (#377).
#
# The runner downloads the ~1.5 GB image with one sdkmanager call and no retry.
# On run 44262648504 (attempt 2, shard 0) that download ended in
# "Error on ZipFile unknown archive" after 4 minutes, so the emulator never
# started and no test ran. Installing it here first, with a bounded retry and a
# check that the image is really on disk, turns that blip into a short delay;
# the runner then finds the package installed and skips its own download.
#
# Every final failure prints "::error::Emulator system image unavailable" so a
# log reader can tell this setup failure from a test failure.
set -euo pipefail

api="${ATLAS_EMULATOR_API:-35}"
target="${ATLAS_EMULATOR_TARGET:-default}"
arch="${ATLAS_EMULATOR_ARCH:-x86_64}"
attempts="${ATLAS_IMAGE_ATTEMPTS:-3}"
delay="${ATLAS_IMAGE_DELAY_SECONDS:-20}"

[[ "$api" =~ ^[0-9]{2}$ && "$target" =~ ^[a-z_]+$ && "$arch" =~ ^[a-z0-9_]+$ ]] || {
  echo "::error::Emulator system image unavailable: bad package spec ${api}/${target}/${arch}"; exit 1;
}
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || {
  echo "::error::Emulator system image unavailable: ANDROID_HOME is not set"; exit 1;
}

package="system-images;android-${api};${target};${arch}"
image_dir="$ANDROID_HOME/system-images/android-${api}/${target}/${arch}"

image_complete() {
  local img
  [[ -f "$image_dir/package.xml" ]] || return 1
  for img in "$image_dir"/*.img; do
    [[ -s "$img" ]] && return 0
  done
  return 1
}

for ((attempt = 1; attempt <= attempts; attempt++)); do
  if image_complete; then
    echo "PASS: $package already installed"
    exit 0
  fi
  echo "Installing $package (attempt $attempt of $attempts)"
  if sdkmanager --install "$package" --channel=0 > /dev/null && image_complete; then
    echo "PASS: $package installed (attempt $attempt of $attempts)"
    exit 0
  fi
  echo "Install of $package failed or left an incomplete image (attempt $attempt of $attempts)"
  # A broken download must not be mistaken for an installed package next time.
  rm -rf "$image_dir" "$ANDROID_HOME/.temp"
  if ((attempt < attempts)); then
    sleep "$delay"
  fi
done
echo "::error::Emulator system image unavailable: $package did not install after $attempts attempts"
exit 1
