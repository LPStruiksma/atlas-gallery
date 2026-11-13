"""Execute the REAL V4 preview packaging shell in a disposable checkout.

This is an offline packaging test, not access to private Termux or backups.
"""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile

root=Path(__file__).parents[2]
workflow=(root/'.github/workflows/v4-preview-bundle.yml').read_text()
header=("      - name: Validate and assemble current V4 preview\n"
        "        shell: bash\n        run: |\n")
assert workflow.count(header)==1
block=workflow.split(header,1)[1].split('      - name:',1)[0]
script='\n'.join(line[10:] if line.startswith('          ') else line
                 for line in block.rstrip().splitlines())+'\n'
assert "set -euo pipefail" in script
assert "'tools/v4/verify_preview_backup.py'" in workflow
sha='a'*40
with tempfile.TemporaryDirectory(prefix='atlas-v4-bundle-simulation-') as temp:
    temp=Path(temp)
    (temp/'tools/v4').mkdir(parents=True)
    for name in ('monochrome_studio_v4_people_ux_preview.py',
                 'install_latest_download.sh','verify_preview_backup.py'):
        shutil.copy2(root/'tools/v4'/name,temp/'tools/v4'/name)
    env=dict(os.environ,GITHUB_SHA=sha)
    result=subprocess.run(['bash','-c',script],cwd=temp,env=env,
                          text=True,capture_output=True)
    assert result.returncode==0,(
        'Exact V4 bundle packaging shell failed: '+result.stderr[-1200:])
    dist=temp/'dist'
    expected={'BUILD.txt','SHA256SUMS.txt',
              'monochrome_studio_v4_people_ux_preview.py',
              'atlas_v4_install_latest.sh','verify_preview_backup.py'}
    assert {p.name for p in dist.iterdir()}==expected
    assert (dist/'BUILD.txt').read_text()==f'ATLAS V4 preview\ncommit {sha}\n'
    manifest={}
    for line in (dist/'SHA256SUMS.txt').read_text().splitlines():
        digest,name=line.split(maxsplit=1)
        manifest[name]=digest
    assert len(manifest)==3
    for name in manifest:
        assert hashlib.sha256((dist/name).read_bytes()).hexdigest()==manifest[name]
    verified=subprocess.run(['sha256sum','-c','SHA256SUMS.txt'],
                            cwd=dist,capture_output=True)
    assert verified.returncode==0
    # Damaging just the verifier makes the artifact manifest fail closed.
    with (dist/'verify_preview_backup.py').open('ab') as out:out.write(b'bad')
    damaged=subprocess.run(['sha256sum','-c','SHA256SUMS.txt'],
                           cwd=dist,capture_output=True)
    assert damaged.returncode!=0
print('PASS: actual V4 bundle shell includes private-data-safe verifier; exact SHA256 manifest, provenance and byte-tamper refusal')
