// DESIGN-1: static source contract, run by autodev-ci's additive test glob.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const root = "app/src/main/java";
const tokenPath = join(root, "nl/atlas/gallery/featuredesign/AtlasTokens.kt");

function kotlinFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? kotlinFiles(path) : entry.isFile() && path.endsWith(".kt") ? [path] : [];
  });
}

function rawColors(source) {
  // A literal colour constructor is the banned spelling. Mask comments and
  // strings to avoid false positives from docs or example text.
  const code = source
    .replace(/"""[\s\S]*?"""/g, " ")
    .replace(/"(?:\\.|[^"\\])*"/g, " ")
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/\/\/[^\n]*/g, " ");
  return [...code.matchAll(/\bColor\s*\(\s*(?:0[xX][0-9A-Fa-f_]+|\d+[uU]?[lL]?)\s*\)/g)].map(match => match[0]);
}

test("raw colour constructors live only in AtlasTokens.kt", () => {
  const files = kotlinFiles(root);
  assert.ok(files.includes(tokenPath), "AtlasTokens.kt must exist");
  const violations = files.filter(file => file !== tokenPath).flatMap(file =>
    rawColors(readFileSync(file, "utf8")).map(color => file + ": " + color));
  assert.deepEqual(violations, [], "use AtlasTokens.Colors instead of literal Color(...)");
});

test("tokens define light/dark semantic colours, spacing, radius and typography", () => {
  const tokens = readFileSync(tokenPath, "utf8");
  for (const symbol of ["object AtlasTokens", "object Colors", "object Spacing", "object Radii", "object Type", "Dark", "Light"]) {
    assert.ok(tokens.includes(symbol), "missing token category " + symbol);
  }
});

test("raw colour detector catches new literal spellings", () => {
  assert.deepEqual(rawColors("val a = Color(0xFFAABBCCDD)\nval b = Color ( 0xFF001122 )"), [
    "Color(0xFFAABBCCDD)", "Color ( 0xFF001122 )"
  ]);
  assert.deepEqual(rawColors("// Color(0xFFFFFFFF)\nval text = \"Color(0xFFFFFFFF)\""), []);
});
