#!/usr/bin/env python3
"""Discover the unfiltered AndroidJUnitRunner inventory and verify class shards."""
import argparse
from collections import Counter, defaultdict
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

CLASS = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+")
SHARD_COUNT = 3
PREFIX = "ATLAS-android-UI-evidence-shard-"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identity(classname, method):
    require(bool(CLASS.fullmatch(classname)) and bool(method.strip()),
            f"Invalid test identity: {classname!r} / {method!r}")
    return classname, method


def inventory(raw):
    """Use terminal status bundles, not text summaries or source-file guesses.

    Log-only runner sends a start (1) and success (0), or an ignored (-3)
    status. Keep complete method names, including parameter suffixes.
    """
    fields = {}
    starts = set()
    tests = set()
    counts = set()
    final = []
    for line in raw.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if sep and key in {"class", "test", "numtests"}:
                require(key not in fields, f"Repeated status field {key}")
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            require(code in {1, 0, -3, -4}, f"Discovery returned status {code}")
            require("class" in fields and "test" in fields, "Discovery status missing identity")
            item = identity(fields["class"], fields["test"])
            if "numtests" in fields:
                counts.add(int(fields["numtests"]))
            if code == 1:
                require(item not in starts and item not in tests, f"Duplicate discovery start: {item}")
                starts.add(item)
            else:
                require(item not in tests, f"Duplicate discovery identity: {item}")
                tests.add(item)
            fields.clear()
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final.append(int(line.removeprefix("INSTRUMENTATION_CODE: ")))
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:", "INSTRUMENTATION_RESULT: shortMsg=")):
            raise ValueError(f"Discovery failed: {line}")
    require(final == [-1] and not fields, "Discovery incomplete or unsuccessful")
    require(bool(tests) and counts == {len(tests)},
            f"Discovery count disagreement: declared {counts}, found {len(tests)}")
    require(starts <= tests, "Discovery start without terminal identity")
    return tests


def partition(tests):
    classes = defaultdict(set)
    for cls, method in tests:
        classes[cls].add(method)
    require(len(classes) >= SHARD_COUNT, "Too few runnable classes")
    bins = [set() for _ in range(SHARD_COUNT)]
    loads = [0] * SHARD_COUNT
    # Greedy largest class first; class name and shard index break ties.
    # Method count is a reproducible proxy for work, not a timing guarantee.
    for cls in sorted(classes, key=lambda c: (-len(classes[c]), c)):
        index = min(range(SHARD_COUNT), key=lambda i: (loads[i], i))
        bins[index].add(cls)
        loads[index] += len(classes[cls])
    require(all(bins) and set.union(*bins) == set(classes) and
            sum(map(len, bins)) == len(classes), "Invalid class partition")
    return bins


def xml_identities(root):
    paths = sorted(root.rglob("*.xml"))
    require(bool(paths), f"No suite XML in {root}")
    actual = Counter()
    for path in paths:
        doc = ET.parse(path).getroot()
        require(doc.tag in {"testsuite", "testsuites"}, f"Unexpected XML root in {path}")
        for test in doc.iter("testcase"):
            item = identity(test.attrib.get("classname", ""), test.attrib.get("name", ""))
            require(test.find("failure") is None and test.find("error") is None,
                    f"Failing test XML: {item}")
            actual[item] += 1
    require(bool(actual), f"Empty test XML in {root}")
    require(all(n == 1 for n in actual.values()), "Duplicate XML test identities")
    return set(actual)


def verify(root):
    original = None
    union = set()
    seen_classes = set()
    for index in range(SHARD_COUNT):
        shard = root / f"{PREFIX}{index}"
        evidence = shard / "ui-evidence"
        discovered = inventory((evidence / "suite-discovery.txt").read_text())
        if original is None:
            original = discovered
        require(discovered == original, f"Shard {index} unfiltered discovery differs")
        classes = partition(original)[index]
        selector = (evidence / "suite-selector.txt").read_text().strip()
        require(selector == ",".join(sorted(classes)), f"Shard {index} selector differs")
        expected = {item for item in original if item[0] in classes}
        actual = xml_identities(shard / "app/build/outputs/androidTest-results")
        require(actual == expected, f"Shard {index}: missing {sorted(expected - actual)}, unexpected {sorted(actual - expected)}")
        require(not union & actual and not seen_classes & classes, f"Shard {index} overlap")
        union.update(actual)
        seen_classes.update(classes)
        print(f"Shard {index}: {len(classes)} classes, {len(actual)} identities")
    require(union == original and seen_classes == {cls for cls, _ in original},
            "Incomplete unfiltered suite union")
    print(f"PASS: {len(seen_classes)} disjoint classes, {len(union)} unique test identities")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    select = sub.add_parser("select")
    select.add_argument("raw", type=Path)
    select.add_argument("index", type=int, choices=range(SHARD_COUNT))
    check = sub.add_parser("verify")
    check.add_argument("root", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "select":
            print(",".join(sorted(partition(inventory(args.raw.read_text()))[args.index])))
        else:
            verify(args.root)
    except (ValueError, OSError, ET.ParseError) as error:
        print(f"::error::Suite partition: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
