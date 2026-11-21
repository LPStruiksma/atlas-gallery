---
name: atlas-tenant-guard
description: Use at the start of every ATLAS implementation, review, CI, docs, or release unit to enforce tenant repository, privacy, and APK-size boundaries.
---
# ATLAS tenant guard
1. Read [ATLAS tenant memory](https://github.com/LPStruiksma/autodev/blob/main/memory/tenant-atlas-native.md), `docs/OWNER_INTENT.md`, and `.autodev/project.json`; live [AutoDev AGENTS](https://github.com/LPStruiksma/autodev/blob/main/AGENTS.md) and roles override memory or this skill.
2. Build from `LPStruiksma/atlas-gallery` live `main`. `LPStruiksma/atlas-native` is a frozen archive: never open PRs, push, re-run jobs, merge, or modify labels there.
3. GitHub and CI use synthetic or licensed test photos and people only; never upload the owner's real photos, embeddings, private People/settings databases, personal records, or credentials.
4. Do not install/uninstall on the owner's S24+, reset People data, touch signing, or change SAF grants as part of routine Dev. Release and privacy approvals are separate.
5. APK size is not a current tenant concern: do not propose slimming, feature cuts, or dependency changes solely to reduce APK bytes ([issue #647](https://github.com/LPStruiksma/atlas-gallery/issues/647)).
6. `.claude/**` is a protected path in `.autodev/project.json`; use an independently reviewed PR and leave merging to Lukas.
7. This skill grants no new authority: keep branch ownership, exact-head CI, independent Review, and human-only gates.
