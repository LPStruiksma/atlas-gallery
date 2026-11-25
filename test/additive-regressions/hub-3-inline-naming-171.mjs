import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const root = 'app/src/main/java/nl/atlas/gallery/'
const queue = readFileSync(root + 'SamePersonQueueScreen.kt', 'utf8')
const naming = readFileSync(root + 'featurepeopleui/SamePersonQueueNaming.kt', 'utf8')
const shared = readFileSync(root + 'PersonPageScreen.kt', 'utf8')
assert.match(queue, /atlas-same-queue-subject-name/)
assert.match(queue, /atlas-same-queue-other-name/)
assert.match(queue, /SamePersonQueueCommits\.name/)
assert.match(queue, /PersonNameDialog\(/)
assert.match(queue, /PersonMergeConfirm\(/)
assert.match(naming, /personNaming\(/)
assert.match(naming, /mergeAction\(/)
assert.match(naming, /samePersonSkipFingerprint\(/)
assert.match(naming, /sourceGeneration/)
assert.match(shared, /More people/)
assert.match(shared, /sourcePreview/)
console.log('HUB-3 shared naming and scoped queue contract')

assert.match(queue, /onQueryChange = \{ nameDraft = it \}/)
assert.match(queue, /onExpandedChange = \{ nameExpanded = it \}/)
assert.match(queue, /saveName\(candidate.name, candidate\)/)
assert.doesNotMatch(queue, /nameDraft = picked.name/)
assert.match(shared, /maxHeight < 400.dp/)
assert.match(shared, /QueueNamingProgress/)

assert.match(shared, /import androidx\.compose\.material3\.CircularProgressIndicator/)
assert.match(queue, /loading = !portraitsReady && !portraitFailed/)
assert.match(queue, /atlas-same-queue-name-sources/)
