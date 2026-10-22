#!/usr/bin/env python3
"""Tandoor watch, step 1: which vabene1111/recipes image tags to test tonight.

Reads the public Docker Hub tag list (no credentials) and the versions the app is tested
against (TandoorVersionTable.TESTED in :core:network), and prints a JSON object:

  {
    "tags":      ["2.6.13", "2.6.15", "2.7.1", "latest"],   # the contract matrix
    "tested":    ["2.6.0", ..., "2.6.15"],                  # from TandoorVersionTable.kt
    "newest":    {"2.6": "2.6.15", "2.7": "2.7.1"},         # newest release per minor line
    "untested":  ["2.7.0", "2.7.1"]                          # released, newer than every tested one
  }

The matrix is: the 2.6.13 baseline (its recordings are the committed fixtures and the
reference for the facts diff), 2.6.15 (plan §10: the watch must be green against it), the
newest release of every minor line from 2.6 on, every extra tag given with --extra, and
"latest". Used by .github/workflows/tandoor-watch.yml; docs/BYGGE.md "Tandoor-bevakning".
"""

import argparse
import json
import re
import sys
import urllib.request

HUB = "https://hub.docker.com/v2/namespaces/vabene1111/repositories/recipes/tags?page_size=100&ordering=last_updated"
RELEASE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
BASELINE = "2.6.13"
REQUIRED = "2.6.15"
FIRST_LINE = (2, 6)
MAX_TAGS = 7


def parse(tag):
    match = RELEASE.match(tag)
    return tuple(int(part) for part in match.groups()) if match else None


def show(version):
    return ".".join(str(part) for part in version)


def hub_releases(pages):
    url, found = HUB, []
    for _ in range(pages):
        request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "tandem-tandoor-watch"})
        with urllib.request.urlopen(request, timeout=30) as response:
            body = json.load(response)
        found += [result["name"] for result in body.get("results", [])]
        url = body.get("next")
        if not url:
            break
    return sorted({parse(tag) for tag in found if parse(tag)})


def tested_versions(path):
    """TandoorVersionTable.TESTED: `(a..b).map { TandoorVersion(x, y, it) }` and/or literals."""
    source = open(path, encoding="utf-8").read()
    line = next((l for l in source.splitlines() if re.search(r"\bval TESTED\b", l)), None)
    if line is None:
        sys.exit(f"::error::No `val TESTED` in {path}")
    versions = set()
    for first, last, major, minor in re.findall(r"\((\d+)\.\.(\d+)\)\.map \{ TandoorVersion\((\d+), (\d+), it\) \}", line):
        versions |= {(int(major), int(minor), patch) for patch in range(int(first), int(last) + 1)}
    for major, minor, patch in re.findall(r"TandoorVersion\((\d+), (\d+), (\d+)\)", line):
        versions.add((int(major), int(minor), int(patch)))
    if not versions:
        sys.exit(f"::error::Could not read the tested versions from: {line.strip()}")
    return sorted(versions)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-table", required=True)
    parser.add_argument("--extra", default="", help="comma-separated extra image tags")
    parser.add_argument("--pages", type=int, default=3)
    args = parser.parse_args()

    releases = hub_releases(args.pages)
    if not releases:
        sys.exit("::error::Docker Hub returned no release tags")
    tested = tested_versions(args.version_table)
    newest = {}
    for version in releases:
        if version[:2] >= FIRST_LINE:
            newest[version[:2]] = version  # releases are sorted, so the last one wins
    untested = [v for v in releases if v > max(tested)]

    tags = [BASELINE, REQUIRED]
    tags += [show(v) for _, v in sorted(newest.items())]
    tags += [t.strip() for t in args.extra.split(",") if t.strip()]
    tags = list(dict.fromkeys(tags))[: MAX_TAGS - 1] + ["latest"]

    json.dump(
        {
            "tags": tags,
            "tested": [show(v) for v in tested],
            "newest": {f"{line[0]}.{line[1]}": show(v) for line, v in sorted(newest.items())},
            "untested": [show(v) for v in untested],
        },
        sys.stdout,
    )
    print()


if __name__ == "__main__":
    main()
