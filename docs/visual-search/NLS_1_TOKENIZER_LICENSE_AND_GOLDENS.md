# NLS-1B: tokenizer licence and offline goldens (draft task scaffold)

Status: **unverified — do not package or redistribute tokenizer bytes on this evidence alone**.

This file is the seed for the bounded Codex task tracked in [atlas-gallery #217](https://github.com/LPStruiksma/atlas-gallery/issues/217). Replace this scaffold with independently verified evidence and reproducible results.

## Required evidence before shipping

- Identify the **exact** Gemma tokenizer file pinned in `tools/visual_search_bench/models.json`: SHA-256 `6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef`; verify actual downloaded bytes before using them.
- Cite **first-party** licences/terms for the specific tokenizer asset and distinguish usage permission from APK redistribution rights. Do not treat the repository's generic Apache-2.0 claim as decisive for a separately hosted asset. If the licence remains ambiguous, mark **BLOCKING** and do not declare redistribution proven.
- Regenerate deterministic English/Dutch **actual token IDs** with the exact tokenizer and `lower | tok(length=64, model="gemma", bos="no", eos="sticky")` policy, including ASCII lowercase, Unicode, long-query sticky EOS, and padding cases. No invented vectors.
- Provide an offline generator `tools/visual_search_bench/nls1_tokenizer_golden.py` and verified `tools/visual_search_bench/data/nls1_tokenizer_goldens.json`; show executed Python test commands and hash results.
- Do not commit tokenizer binary, private media, corpus, credentials, runtime dependency, changes outside these three permitted paths, or any read from `eval/held-out/**`.

This draft is a **task brief only**, not licence clearance, regenerated goldens or test proof.
