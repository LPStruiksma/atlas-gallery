#!/usr/bin/env bash
# One-time optional installation from Termux. Creates a NEW private repository.
set -euo pipefail
set -o noclobber
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"
if ! command -v gh >/dev/null || ! command -v git >/dev/null; then
  echo 'Git and GitHub CLI required. In Termux run: pkg install git gh'
  exit 2
fi
if ! gh auth status >/dev/null 2>&1; then
  echo 'Sign in to GitHub (explicit user action needed)'
  gh auth login --web
fi
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git init -b main
  git add .
  git -c user.name='ATLAS development' -c user.email='atlas@localhost.invalid' commit -m 'ATLAS native 0.2 with emulator UI regression evidence'
fi
if git remote get-url origin >/dev/null 2>&1; then
  echo "Existing origin: $(git remote get-url origin)"
  echo 'Repository already connected; refusing to push automatically.'
  exit 0
fi
echo 'This will create a NEW PRIVATE GitHub repository and push the ATLAS source code.'
echo 'No personal photos, V4 database, or V5 index are part of this project.'
read -r -p "Type CREATE to proceed: " approval
if [[ "$approval" != 'CREATE' ]]; then
  echo 'Cancelled.'
  exit 0
fi
gh repo create atlas-native --private --source . --remote origin --push
echo 'Private ATLAS repository created. GitHub Actions will build an APK on push.'
echo 'To run emulator UI tests: Actions > ATLAS Android - Build and UI Evidence > Run workflow.'
