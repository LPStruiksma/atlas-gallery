# Splash navigation diagnostics (#997)

Evidence-only scope: capture three API 35 light-mode cold-start splash frames, navigation-band pixel colors, starting-window/system UI flags, and navigation mode, with a dark-mode comparison. Preserve the existing Lapis background and white mark; do not change launch themes before measurements identify a cause.

Acceptance for this instrumentation step: link the exact-head emulator CI run and its rendered evidence to issue #997. Final visual acceptance remains `U2 NAV-CONTRAST light ... PASS` on 3/3 launches after a subsequent verified fix.
