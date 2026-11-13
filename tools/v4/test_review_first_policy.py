"""Guard V4's public scan/browser/CLI entrypoints against unapproved identity links."""
import ast
from pathlib import Path

source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py').read_text()
tree=ast.parse(source)
defs={node.name:node for node in tree.body if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef))}

def calls(name):
    return [node.func.id for node in ast.walk(defs[name])
            if isinstance(node,ast.Call) and isinstance(node.func,ast.Name)]

assert 'auto_identity_candidates' not in calls('_scan_impl'), 'Scan must never assign a model guess'
assert 'auto_identity_backfill' not in calls('start_job'), 'Browser background jobs must not auto-file People'
assert "auto_enabled=False" in source, 'Status must not imply legacy auto-configuration is active'
assert "if not args.preview:" in source, 'Legacy CLI must refuse automatic commit mode'
assert "auto_identity_backfill(c,limit=args.limit,commit=False" in source
assert "raise ValueError('Unreviewed auto-confirmation is disabled" in source

html=source.split('<script>',1)[1].split('</script>',1)[0]
assert "Match old photos automatically" not in html
assert "Enable auto-identification" not in html
assert "Suggest profile matches" in html
assert "if(a==='accept'||a==='reject')" in html
assert "keepReviewUndo(result);" in html
assert "for(const id of submittedIds)selected.delete(id)" in html
assert "if(!reviewWriteStillVisible(start))" in html
assert "suggestions, not confirmed identities" in source.lower()
print('PASS: V4 scanner, browser, settings, and CLI fail closed on unapproved model identity confirmation')
