# Emulator CI quarantine — issue #970

This note records the scope for the Codex implementation task. Keep the blocking emulator instrumentation suite intact except for the known-flaky test classes tracked in #967, #968 and #969. Run excluded tests separately with visible results without making their failures block the regular suite. Confirm exact test class names from source before editing the quarantine list. Preserve all other tests, existing shard handling, and synthetic recovery checks. Do not modify `eval/held-out/**`.

Acceptance requires exact-head emulator CI evidence for the blocking suite and visible results for the quarantined run. Tracking issues remain open until the underlying test defects are fixed.
