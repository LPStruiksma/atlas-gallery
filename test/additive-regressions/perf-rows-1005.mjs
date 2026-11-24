import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const report = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/PerformanceReport.kt', import.meta.url), 'utf8');
assert.match(report, /No data yet\. Do this action once\./);
assert.match(report, /Not built yet/);
assert.doesNotMatch(report, /metric\(p, "viewer_ms"/);
assert.match(report, /setOf\(1, 2, 5, 8\)/);
