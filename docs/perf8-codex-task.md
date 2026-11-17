# PERF-8 Codex task brief (issue #129)

Actor: chatgpt/dev
Session: pull-81865e1f
Base before this task brief: 2f81b0d5e01703287982bbd9284e4eb247c094b8

Implement the authorized Baseline Profiles unit described in https://github.com/LPStruiksma/atlas-gallery/issues/129 and the latest strategy handoff. Use the owner-approved AndroidX baselineprofile/profileinstaller dependencies and the specifically approved **test-only** `androidx.benchmark:benchmark-macro-junit4:1.5.0`; no other new dependency without approval. Add the required Gradle integration and a `:baselineprofile` generator using `BaselineProfileRule` with synthetic MediaStore images, including cached-grid cold-start and grid fling journeys. Keep `automaticGenerationDuringBuild = false`; profile generation is release-candidate-only. Do not invent, hand-write or claim a generated ART profile: generate it only if an actual emulator and working toolchain are available.

First verify the exact checked-out PR head and availability of Gradle/JDK/Android SDK/emulator. Record precise commands, versions, any failure and whether the emulator actually ran. If the environment cannot execute an Android emulator, report the exact blocker; provide only a correctly bounded patch for work whose correctness can be verified without fabricating generated results. No real photos, paid runners, signing changes, extra dependencies or performance gains without comparable measurements. PERF-0 and startup measurements may not yet be comparable. The Gradle PR is protected for merging: Lukas presses Merge.

Reply **once** with the exact parent SHA, commit SHA, executed test commands/results, emulator profile evidence (or explicit nonexecution), and exactly one fenced `diff` block containing a one-commit `git format-patch -1 --stdout` patch whose Parent matches the PR head. Do not push, claim, label or merge. Never read/search/print `eval/held-out/**`.
