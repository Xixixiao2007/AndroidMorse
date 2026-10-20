#!/usr/bin/env python3
"""Summarise Gradle unit-test results and fail on anything that is not clean.

Why this exists: during the T4 work a test suite reported "green" while three
tests were silently **skipped** (a fixture path resolved to the module directory),
and during T6 a stale/partial run looked like a pass. A skipped test proves
nothing, so the skip count has to be visible in every evidence line.

Usage:
    python tools/verify-tests.py [root] [--strict]

    root      project root to scan (default: the repo containing this script)
    --strict  treat skipped tests as failures too

Exit codes: 0 clean, 1 failures/errors (or skips under --strict), 2 nothing found.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def find_results(root: Path) -> list[Path]:
    """Gradle writes one XML per test class under <module>/build/test-results/test/."""
    return sorted(root.glob("*/build/test-results/test/TEST-*.xml"))


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    strict = "--strict" in argv
    root = Path(args[0]).resolve() if args else Path(__file__).resolve().parent.parent

    results = find_results(root)
    if not results:
        print(f"no test results under {root}/*/build/test-results/test — run the tests first")
        return 2

    total = passed = skipped = failures = errors = 0
    rows: list[tuple[str, int, int, int, int, str]] = []

    for path in results:
        suite = ET.parse(path).getroot()
        t = int(suite.get("tests", 0))
        s = int(suite.get("skipped", 0))
        f = int(suite.get("failures", 0))
        e = int(suite.get("errors", 0))
        module = path.parts[-5] if len(path.parts) >= 5 else "?"
        rows.append((module, t, s, f, e, suite.get("name", path.stem)))
        total += t
        skipped += s
        failures += f
        errors += e
        passed += t - s - f - e

    module_width = max(len(r[0]) for r in rows)
    for module, t, s, f, e, name in rows:
        flag = "FAIL" if (f or e) else ("SKIP" if s else "ok  ")
        print(f"  {flag} {module:<{module_width}}  tests={t:<3} pass={t - s - f - e:<3} skip={s:<2} fail={f} err={e}  {name}")

    print()
    print(f"  suites={len(rows)} tests={total} passed={passed} skipped={skipped} "
          f"failures={failures} errors={errors}")
    if skipped:
        print(f"  NOTE: {skipped} test(s) skipped — a skipped test is not evidence; "
              f"say so in the ticket's evidence line.")

    if failures or errors:
        print("  RESULT: FAILED")
        return 1
    if strict and skipped:
        print("  RESULT: FAILED (--strict treats skips as failures)")
        return 1
    print("  RESULT: clean" + (" (with skips)" if skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
