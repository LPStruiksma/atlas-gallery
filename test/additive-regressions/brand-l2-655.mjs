// BRAND-L2 #655: approved Lapis tokens and locally bundled Funnel faces.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const src = readFileSync("app/src/main/java/nl/atlas/gallery/featuredesign/AtlasTokens.kt", "utf8");
const design = readFileSync("app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt", "utf8");

const rows = [
  ["Ground", "FFFFFF", "000000"],
  ["Surface", "F2F3F8", "14152A"],
  ["Ink", "0E1030", "F2F3F8"],
  ["InkSecondary", "5A5F7A", "9AA0B8"],
  ["BrandFill", "2639C9", "3448E0"],
  ["BrandText", "2639C9", "8C9BFF"],
  ["OnBrand", "FFFFFF", "FFFFFF"],
  ["OnBrandSecondary", "DCE1FF", "DCE1FF"],
  ["ViewerGround", "000000", "000000"]
];

const lin = byte => {
  const value = byte / 255;
  return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
};
const luminance = rgb => {
  const c = rgb.match(/../g).map(value => lin(parseInt(value, 16)));
  return c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722;
};
const ratio = (a, b) => {
  const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (high + 0.05) / (low + 0.05);
};

test("Lapis semantic palette has every approved light/dark role and contrast", () => {
  for (const [name, light, dark] of rows) {
    const prop = new RegExp("val\\s+" + name + "\\s*=\\s*Color\\(0xFF([0-9A-Fa-f]{6})\\)", "g");
    const found = [...src.matchAll(prop)].map(match => match[1].toUpperCase());
    assert.deepEqual(found, [dark, light], name + " must be declared dark then light");
  }
  for (const theme of [1, 2]) {
    const get = name => rows.find(r => r[0] === name)[theme];
    for (const foreground of ["Ink", "InkSecondary", "BrandText"]) {
      for (const background of ["Ground", "Surface"]) {
        assert.ok(ratio(get(foreground), get(background)) >= 4.5,
          foreground + " on " + background + " fails contrast");
      }
    }
    assert.ok(ratio(get("BrandFill"), get("Ground")) >= 3);
    assert.ok(ratio(get("OnBrand"), get("BrandFill")) >= 4.5);
    assert.ok(ratio(get("OnBrandSecondary"), get("BrandFill")) >= 4.5);
  }
});

test("Funnel faces are bundled and used in Compose typography", () => {
  for (const name of ["funnel_display", "funnel_sans"]) {
    const bytes = readFileSync("app/src/main/res/font/" + name + ".ttf");
    assert.ok(bytes.length > 20000, name + " must be an actual TTF");
    assert.ok(bytes.subarray(0, 4).equals(Buffer.from([0, 1, 0, 0])) ||
      bytes.subarray(0, 4).toString("ascii") === "OTTO");
    assert.ok(readFileSync("app/src/main/res/raw/" + name + "_ofl.txt", "utf8")
      .includes("SIL OPEN FONT LICENSE"));
  }
  assert.match(design, /Font\(R\.font\.funnel_display/);
  assert.match(design, /Font\(R\.font\.funnel_sans/);
});

test("viewer and selection aliases use Lapis tokens, not the old accents", () => {
  for (const old of ["3E91FF", "FF4D5E", "2A2A2A", "292929", "888888"]) {
    assert.ok(!src.includes("0xFF" + old), "legacy UI chrome remains: " + old);
  }
});
