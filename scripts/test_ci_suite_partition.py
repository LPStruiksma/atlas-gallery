"""Synthetic runner status/XML fixtures for #989."""
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from ci_suite_partition import PREFIX, inventory, partition, verify


def raw(items):
    lines = []
    for cls, method in items:
        for code in (1, 0):
            lines.extend([f"INSTRUMENTATION_STATUS: class={cls}",
                          f"INSTRUMENTATION_STATUS: test={method}",
                          f"INSTRUMENTATION_STATUS: numtests={len(items)}",
                          f"INSTRUMENTATION_STATUS_CODE: {code}"])
    return "\n".join(lines + ["INSTRUMENTATION_CODE: -1"])


ITEMS = [("nl.test.A", "test[0]"), ("nl.test.A", "test[1]"),
         ("nl.test.B", "test"), ("nl.test.C", "test"), ("nl.test.D", "test")]


class SuitePartitionTest(unittest.TestCase):
    def test_inventory_and_deterministic_partition(self):
        found = inventory(raw(ITEMS))
        self.assertEqual(found, set(ITEMS))
        bins = partition(found)
        self.assertEqual(bins, partition(inventory(raw(list(reversed(ITEMS))))))
        self.assertEqual(set.union(*bins), {cls for cls, _ in ITEMS})
        self.assertEqual(sum(map(len, bins)), 4)
        self.assertTrue(all(bins))

    def test_discovery_errors(self):
        valid = raw(ITEMS)
        for malformed in ("", valid.replace("CODE: -1", "CODE: 0"),
                          valid.replace("numtests=5", "numtests=6"),
                          valid.replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
                          raw(ITEMS + [ITEMS[0]]),
                          valid.replace("test=test[0]", "test=", 1)):
            with self.subTest(malformed=malformed[:80]), self.assertRaises(ValueError):
                inventory(malformed)
        with self.assertRaises(ValueError):
            partition({("nl.test.A", "one")})

    def test_ignored_status(self):
        self.assertEqual(inventory(raw(ITEMS).replace("STATUS_CODE: 0", "STATUS_CODE: -3")),
                         set(ITEMS))

    def make_shards(self, root):
        for index, classes in enumerate(partition(set(ITEMS))):
            shard = root / f"{PREFIX}{index}"
            evidence = shard / "ui-evidence"
            evidence.mkdir(parents=True)
            (evidence / "suite-discovery.txt").write_text(raw(ITEMS))
            (evidence / "suite-selector.txt").write_text(",".join(sorted(classes)) + "\n")
            results = shard / "app/build/outputs/androidTest-results"
            results.mkdir(parents=True)
            suite = ET.Element("testsuite")
            for cls, method in ITEMS:
                if cls in classes:
                    ET.SubElement(suite, "testcase", classname=cls, name=method)
            ET.ElementTree(suite).write(results / "TEST-fixture.xml")

    def test_join_success_and_fail_closed(self):
        for mutation in ("none", "missing", "duplicate", "selector", "discovery", "wrong", "failure", "empty"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.make_shards(root)
                shard = root / f"{PREFIX}0"
                xml = next(shard.rglob("*.xml"))
                evidence = shard / "ui-evidence"
                if mutation == "missing":
                    xml.unlink()
                elif mutation == "selector":
                    (evidence / "suite-selector.txt").write_text("nl.test.Z")
                elif mutation == "discovery":
                    (evidence / "suite-discovery.txt").write_text(raw(ITEMS + [("nl.test.Z", "test")]))
                elif mutation in ("duplicate", "wrong", "failure", "empty"):
                    doc = ET.parse(xml)
                    suite = doc.getroot()
                    case = suite.find("testcase")
                    if mutation == "duplicate":
                        ET.SubElement(suite, "testcase", **case.attrib)
                    elif mutation == "wrong":
                        case.set("classname", "nl.test.Z")
                    elif mutation == "failure":
                        ET.SubElement(case, "failure")
                    else:
                        suite.clear()
                    doc.write(xml)
                if mutation == "none":
                    verify(root)
                else:
                    with self.assertRaises(ValueError):
                        verify(root)


if __name__ == "__main__":
    unittest.main()
