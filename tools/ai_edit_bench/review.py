"""Blinded human comparisons: the reviewer HTML never contains provider names.

Keep pairwise-key.json separate from reviewers; votes can be decoded after review.
All pages and images are local artifacts; nothing is uploaded or tracked.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import shutil
from collections import Counter
from pathlib import Path

VOTES = {"A", "B", "tie", "both_bad"}

def _safe_output(root: Path, value: str) -> Path:
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("output image path must stay within output directory")
    result = (root / relative).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError("output image path escapes directory")
    return result

def create_pairwise(cases: list[dict], records: list[dict], out: Path, *,
                    seed: str = "aiedit-v1") -> dict:
    """Create provider-free review.html and a separate private decoding key."""
    by_case = {}
    for record in records:
        if (record["outcome"] == "completed"
                and record.get("output_path") and not record.get("degraded")):
            by_case.setdefault(record["case_id"], []).append(record)
    images = out / "pair-assets"
    images.mkdir(parents=True, exist_ok=True)
    key, cards = {}, []
    for case in cases:
        candidates = sorted(by_case.get(case["id"], []), key=lambda r: r["provider"])
        if len(candidates) < 2:
            continue
        if len(candidates) > 2:
            # A deterministic pair; multi-provider tournament scheduling is separate.
            candidates = candidates[:2]
        a, b = candidates
        digest = hashlib.sha256(f"{seed}:{case['id']}".encode()).hexdigest()
        if int(digest[:2], 16) % 2:
            a, b = b, a
        identifier = digest[:16]
        images_for_pair = []
        for label, candidate in (("A", a), ("B", b)):
            source = _safe_output(out, candidate["output_path"])
            if not source.is_file():
                raise ValueError(f"missing comparison image: {case['id']} {label}")
            filename = f"{identifier}-{label.lower()}{source.suffix.lower()}"
            destination = images / filename
            shutil.copyfile(source, destination)
            images_for_pair.append((label, "pair-assets/" + filename))
        key[identifier] = {"case_id": case["id"], "A": a["provider"],
                           "B": b["provider"]}
        pictures = "".join(
            f'<figure><img loading="lazy" src="{html.escape(path, quote=True)}" '
            f'alt="Candidate {label}"><figcaption>Candidate {label}</figcaption></figure>'
            for label, path in images_for_pair
        )
        options = "".join(
            f'<button type="button" data-pair="{identifier}" data-vote="{vote}">'
            f'{html.escape(title)}</button>'
            for vote, title in (("A", "A wins"), ("B", "B wins"),
                                ("tie", "Tie"), ("both_bad", "Both poor"))
        )
        cards.append(
            f'<section><header><span>{html.escape(case["category"])}</span>'
            f'<span>Pair {identifier}</span></header>'
            f'<p>{html.escape(case["instruction"])}</p>'
            f'<div class="pictures">{pictures}</div><div class="buttons">{options}</div>'
            f'</section>'
        )
    # Static HTML; no scripts, images, endpoints, or provider names are fetched remotely.
    page = r"""<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ATLAS AI Edit — blind image review</title>
<style>
:root{font-family:system-ui,sans-serif;color-scheme:light dark}
body{max-width:960px;margin:auto;padding:20px;background:#121519;color:#f3f4f5}
h1{font-size:1.5rem}p,.note{line-height:1.5;color:#c9d0d7}
section{border:1px solid #444c53;border-radius:14px;padding:18px;margin:20px 0}
header{display:flex;justify-content:space-between;gap:12px;color:#aeb8c1}
.pictures{display:grid;grid-template-columns:1fr 1fr;gap:12px}
figure{margin:0}img{width:100%;aspect-ratio:4/3;object-fit:contain;background:#212831}
figcaption{text-align:center;margin-top:4px}
.buttons{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}
button{background:#293442;border:1px solid #697786;color:#fff;border-radius:9px;padding:10px;cursor:pointer}
button.selected{background:#276e69;border:2px solid #a3e0d6}
#export{position:sticky;top:8px;background:#99dbd0;color:#122825;font-weight:bold;z-index:2}
</style></head><body>
<h1>Blind pairwise image review</h1>
<p class="note">Choose better adherence and preservation, considering artifacts.
Labels A/B are randomly balanced and do not reveal the provider. Skip uncertain
pairs; download your votes when finished. Keep the decoding key private.</p>
<button id="export">Download votes as JSON</button>
<main>CARDS_PLACEHOLDER</main>
<script>
"use strict";
const votes = Object.create(null);
document.querySelectorAll("[data-vote]").forEach(button => {
  button.addEventListener("click", () => {
    const id = button.dataset.pair;
    votes[id] = button.dataset.vote;
    document.querySelectorAll('[data-pair="' + id + '"]').forEach(el => {
      el.classList.toggle("selected", el === button);
    });
  });
});
document.getElementById("export").addEventListener("click", () => {
  const blob = new Blob([JSON.stringify({schema_version:1,votes:votes},null,2)],
                        {type:"application/json"});
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href=url; a.download="blind-votes.json"; a.click();
  setTimeout(() => URL.revokeObjectURL(url),1000);
});
</script></body></html>""".replace("CARDS_PLACEHOLDER", "\n".join(cards))
    (out / "review.html").write_text(page, encoding="utf-8")
    (out / "pairwise-key.json").write_text(
        json.dumps({"schema_version": 1, "pairs": key}, indent=2) + "\n",
        encoding="utf-8",
    )
    return key

def decode_votes(key: dict, votes: dict) -> dict:
    """Unblind only after human annotation; reject unknown labels/identifiers."""
    pairs = key.get("pairs", {})
    if key.get("schema_version") != 1 or votes.get("schema_version") != 1:
        raise ValueError("invalid vote/key schema")
    raw_votes = votes.get("votes")
    if not isinstance(raw_votes, dict):
        raise ValueError("votes object required")
    counts = Counter()
    for pair_id, vote in raw_votes.items():
        if pair_id not in pairs or vote not in VOTES:
            raise ValueError("unknown pair or vote")
        pair = pairs[pair_id]
        if vote in {"A", "B"}:
            counts[pair[vote]] += 1
        elif vote == "tie":
            counts["ties"] += 1
        else:
            counts["both_bad"] += 1
    return {"total_pairs_voted": len(raw_votes), "wins_or_ties": dict(counts)}

def main(argv=None):
    p = argparse.ArgumentParser(description="Unblind exported pairwise votes locally")
    p.add_argument("--key", required=True, help="pairwise-key.json, withheld from reviewers")
    p.add_argument("--votes", required=True, help="human exported blind-votes.json")
    args = p.parse_args(argv)
    key = json.loads(Path(args.key).read_text(encoding="utf-8"))
    votes = json.loads(Path(args.votes).read_text(encoding="utf-8"))
    print(json.dumps(decode_votes(key, votes), indent=2))

if __name__ == "__main__":
    main()
