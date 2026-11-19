import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// #185: documentation-only AIE-0 evidence contract. Before PROVIDERS.md is
// introduced this test fails (RED); it needs no credentials, network or media.
const path = "docs/ai-edit/PROVIDERS.md";
const doc = readFileSync(path, "utf8");
const architecture = readFileSync("docs/ai-edit/ARCHITECTURE.md", "utf8");

for (const title of [
  "Evidence and uncertainty",
  "Hosted capability matrix",
  "Open-weight capability matrix",
  "Content-policy and refusal matrix",
  "Quality evidence",
  "100-edit benchmark budgets",
  "Recommendations and adapter contracts",
  "Owner gates",
  "Sources",
]) assert.ok(doc.includes(title), `missing AIE-0 section: ${title}`);

for (const candidate of [
  "GPT-Image-2.6", "Gemini 3.1 Flash Image", "FLUX.2",
  "Seedream", "Ideogram", "Recraft", "Stability", "fal.ai",
  "Replicate", "Qwen-Image-Edit-2511", "FLUX.1 Kontext",
  "Step1X-Edit", "OmniGen2", "HiDream-E1",
]) assert.ok(doc.includes(candidate), `missing candidate: ${candidate}`);

for (const key of [
  "mask", "reference", "multi-turn", "streaming", "resolution",
  "rate limit", "retention", "training", "region", "VRAM",
  "licence", "latency", "lawful", "refusal", "100", "2026-11-15",
]) assert.ok(doc.toLowerCase().includes(key.toLowerCase()), `missing criterion: ${key}`);

const urls = [...doc.matchAll(/https:\/\/[^\s)]+/g)].map(x => x[0]);
assert.ok(new Set(urls).size >= 20, "requires at least 20 primary-source URLs");
assert.ok(urls.some(x => x.includes("ai.google.dev/gemini-api/docs/pricing")));
assert.ok(urls.some(x => x.includes("bfl.ai")));
assert.ok(urls.some(x => x.includes("huggingface.co/Qwen")));
assert.ok(urls.some(x => x.includes("runpod.io/pricing")));
assert.match(doc, /No (paid API|live inference|personal media)/i);
assert.match(doc, /\[adapter directory\]\(adapters\/\)/);
assert.equal([...doc.matchAll(/\]\(adapters\/\)/g)].length, 1, "PROVIDERS.md needs one stable adapter-directory pointer");
assert.doesNotMatch(doc, /GeminiImageEditProvider|FalImageEditProvider/);
assert.match(architecture, /document implemented adapter behavior only in the stable \[adapter directory\]\(adapters\/\), one file per adapter/);
console.log("PASS: AIE-0 source-backed comparison, licensing, refusal and cost coverage");
