import json, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parents[1]))
import protocol
class TestProtocol(unittest.TestCase):
    def test_fixture(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'f'; protocol.fixtures(out); self.assertEqual(2,len(protocol.manifest(out/'manifest.jsonl')))
    def test_bad_hash(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'f'; protocol.fixtures(out); m=out/'manifest.jsonl'; rows=[json.loads(x) for x in m.read_text().splitlines()]; rows[0]['sha256']='0'*64; m.write_text('\n'.join(map(json.dumps,rows))+'\n')
            with self.assertRaisesRegex(ValueError,'SHA-256 mismatch'): protocol.manifest(m)
if __name__=='__main__': unittest.main()
