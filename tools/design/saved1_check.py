#!/usr/bin/env python3
"""Verify the committed SAVED-1 board against docs/design/saved-1/manifest.json (size, SHA-256, frames, 48 dp)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import saved1_board  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(saved1_board.check())
