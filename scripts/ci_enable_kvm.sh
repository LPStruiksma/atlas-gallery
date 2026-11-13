#!/usr/bin/env bash
# Give the GitHub runner user access to /dev/kvm before an emulator job (#597).
#
# `udevadm trigger` is asynchronous, so a permission check straight after it
# can run before the rule has applied; that race failed several emulator jobs
# before any test ran. Wait for udev, then retry the check briefly. A missing
# /dev/kvm cannot be fixed by waiting, so that fails at once.
#
# Every failure prints the exact "::error::KVM unavailable" marker: the
# Strategy re-run sweep and scripts/audit_recovery_ci_log.py match on it.
set -euo pipefail

attempts="${ATLAS_KVM_ATTEMPTS:-5}"
delay="${ATLAS_KVM_DELAY_SECONDS:-2}"

echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=kvm
sudo udevadm settle --timeout=15 || echo "udevadm settle timed out; checking /dev/kvm anyway"

if [[ ! -e /dev/kvm ]]; then
  echo '::error::KVM unavailable; /dev/kvm does not exist on this runner, refusing multi-minute software-emulation fallback'
  exit 1
fi
for ((attempt = 1; attempt <= attempts; attempt++)); do
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    echo "PASS: GitHub runner can use /dev/kvm (attempt $attempt of $attempts)"
    exit 0
  fi
  echo "/dev/kvm not yet readable and writable (attempt $attempt of $attempts)"
  if ((attempt < attempts)); then sleep "$delay"; fi
done
ls -l /dev/kvm || true
echo "::error::KVM unavailable; /dev/kvm still not usable after $attempts attempts, refusing multi-minute software-emulation fallback"
exit 1
