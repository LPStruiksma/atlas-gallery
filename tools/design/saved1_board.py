#!/usr/bin/env python3
"""SAVED-1 (#779): synthetic save / exclude / Unsorted design-evidence board.

Bootstrap for #946: remove this line as part of the requested implementation.

Draws real, scaled 1080 x 2340 device frames for panels A-G of docs/design/saved-1/README.md on top of
the shared Lapis kit (Quiet Frame v3 tokens, Funnel fonts, synthetic photo placeholders, 48 dp target
audit). Everything is synthetic: generated gradients, fictional names and counts. Nothing reads private
media, location data or the network, and no runtime or device capture is claimed.

    python3 tools/design/saved1_board.py --font-dir DIR --finalize   # render PNG + write manifest
    python3 tools/design/saved1_board.py --check                      # verify PNG against manifest
    python3 tools/design/saved1_board.py --check   # ... (rest of the existing file follows)
