import gzip, io, json, tempfile, unittest, zipfile
from pathlib import Path
import places_geonames as pg

# Synthetic miniature of the GeoNames dump format; no network, no real data file.
def row(gid, name, lat, lon, fclass="P", pop=0):
    f = [str(gid), name, name, "", str(lat), str(lon), fclass, "PPL", "NL", "", "07", "", "", "", str(pop), "", "", "Europe/Amsterdam", "2020-01-01"]
    return "\t".join(f) + "\n"

def alt(aid, gid, lang, name, preferred="", historic=""):
    return "\t".join([str(aid), str(gid), lang, name, preferred, "", "", historic, "", ""]) + "\n"

PLACES = [row(1, "Alpha", 52.00, 5.00), row(2, "Beta", 52.00, 5.05), row(3, "Farm", 52.01, 5.00, "S"), row(4, "Gamma", 53.00, 6.00)]
ALTS = [alt(1, 2, "en", "Beta EN"), alt(2, 2, "nl", "Beta NL", "1"), alt(3, 2, "nl", "Oud", "", "1"), alt(4, 1, "de", "Alfa")]

class T(unittest.TestCase):
    def test_parse_keeps_only_populated_places(self):
        self.assertEqual([p[1] for p in pg.parse_places(PLACES)], ["Alpha", "Beta", "Gamma"])

    def test_alternates_prefer_preferred_and_skip_historic_and_other_languages(self):
        a = pg.parse_alternates(ALTS, {1, 2})
        self.assertEqual(a, {2: {"en": "Beta EN", "nl": "Beta NL"}})

    def index(self):
        return pg.PlaceIndex([(1, "Alpha", 52.0, 5.0, 0, "", ""), (2, "Beta", 52.0, 5.05, 0, "Beta EN", "Beta NL"),
                              (4, "Gamma", 53.0, 6.0, 0, "", "")])

    def test_nearest_and_cutoff(self):
        i = self.index()
        self.assertEqual(i.nearest(52.001, 5.04)[1][3], "Beta")
        self.assertIsNone(i.nearest(52.5, 3.0))          # far offshore/abroad: no Dutch place
        self.assertEqual(i.nearest(52.0, 5.0)[0], 0.0)

    def test_nearest_crosses_grid_cells(self):
        i = self.index()
        self.assertEqual(i.nearest(52.0, 5.099)[1][3], "Beta")  # query and match in different cells

    def test_tie_breaks_to_lower_geonameid(self):
        i = pg.PlaceIndex([(9, "Nine", 52.0, 5.0, 0, "", ""), (3, "Three", 52.0, 5.0, 0, "", "")])
        self.assertEqual(i.nearest(52.0, 5.0)[1][3], "Three")

    def test_prepare_and_bench_roundtrip_offline(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            for fn, data in (("NL.zip", "".join(PLACES)), ("alt-NL.zip", "".join(ALTS))):
                with zipfile.ZipFile(d / fn, "w") as z:
                    z.writestr("NL.txt", data)
            out = d / "i.tsv.gz"
            pg.main(["prepare", "--source", str(d), "--output", str(out)])
            m = json.loads(Path(str(out) + ".manifest.json").read_text())
            self.assertEqual((m["rows"], m["rows_with_en"], m["rows_with_nl"]), (3, 1, 1))
            self.assertGreater(m["filtered_bytes"], 0)
            self.assertEqual(pg.PlaceIndex.load(out).nearest(52.0, 5.05)[1][5], "Beta EN")

if __name__ == "__main__":
    unittest.main()
