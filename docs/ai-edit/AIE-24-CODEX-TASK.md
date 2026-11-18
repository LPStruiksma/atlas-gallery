# AIE-24 Codex task

Work item: [#456](https://github.com/LPStruiksma/atlas-gallery/issues/456).

Implement the unit in the issue, including the bounded HTTP 4xx/5xx error reply (64 KiB maximum), documented Gemini and fal policy refusal mappings, non-disclosure of reply text in logs or `toString()`, fake-server JVM tests, and the outcome description in `docs/ai-edit/ARCHITECTURE.md`.

Do not modify retry semantics, dependencies or unrelated paths. This staging note can be removed in the implementation patch if the main architecture doc covers the final outcome.
