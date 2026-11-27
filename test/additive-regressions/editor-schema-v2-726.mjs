import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const base = 'app/src/main/java/nl/atlas/gallery/featureeditor/engine/';
test('EDIT-8 schema v2 has migration and ordered plan stages', () => {
  const recipe = readFileSync(base + 'EditRecipe.kt', 'utf8');
  const json = readFileSync(base + 'RecipeJson.kt', 'utf8');
  const plan = readFileSync(base + 'RenderPlan.kt', 'utf8');
  assert.match(recipe, /CURRENT_SCHEMA_VERSION\s*=\s*2/);
  assert.match(json, /RecipeMigration\(1\)/);
  for (const op of ['Curves', 'ColorMix', 'SpotColor', 'ShapeCrop', 'Resize']) {
    assert.match(recipe, new RegExp('data class ' + op + '\\b'));
    assert.match(plan, new RegExp('RenderStage\\.' + op + '\\b'));
  }
});
