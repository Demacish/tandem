#!/usr/bin/env python3
"""Tandoor watch, step 3: the report and the issue text.

Inputs (from .github/workflows/tandoor-watch.yml):
  --discover   discover.py's JSON (matrix, tested and untested versions)
  --jobs       the run's jobs as `gh api .../jobs` returns them ({"jobs": [{"name", "conclusion"}]})
  --recordings the downloaded contract-recordings-<tag> artifacts
  --run-url    link to the workflow run

For every tag it compares the contract run's observed facts (observed-facts.json, Fas 0 and Fas 1)
with the 2.6.13 baseline OF THE SAME RUN, after removing what differs on every run (image UUIDs,
the throttle's remaining seconds, the tag and version themselves). Writes Markdown to --out and
`attention=true|false` plus `fingerprint=<hash>` to --github-output: attention means a red
contract run, a release newer than every tested version, or a changed fact that nobody reviewed.

--reviewed (contract-tests/watch/reviewed-changes.json) lists, per release line ("2.7"), the facts
that differ from 2.6.13 and were reviewed against the app when the line was added to
TandoorVersionTable.TESTED: key -> the expected new value (normalized JSON text as in the diff),
null for a fact that is gone, or "*" for a value that changes with every build (HTML pages with
hashed asset names). Such a difference is shown but needs no attention; any other one does.
"""

import argparse
import hashlib
import json
import os
import re

BASELINE = "2.6.13"
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
SECONDS = re.compile(r"available in \d+ seconds")
IGNORED_KEYS = {"tandoorTag", "serverSettings.version"}
NUMERIC_NOISE_KEYS = {"throttle.retryAfterHeader"}
MAX_DIFF_LINES = 80


def flatten(value, prefix=""):
    if isinstance(value, dict):
        for key, child in value.items():
            yield from flatten(child, f"{prefix}.{key}" if prefix else key)
    else:
        yield prefix, value


def normalized(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as file:
        facts = dict(flatten(json.load(file)))
    result = {}
    for key, value in facts.items():
        if key in IGNORED_KEYS:
            continue
        text = json.dumps(value, ensure_ascii=False, sort_keys=True)
        text = UUID.sub("<uuid>", text)
        text = SECONDS.sub("available in <n> seconds", text)
        if key in NUMERIC_NOISE_KEYS:
            text = re.sub(r"\d+", "<n>", text)
        result[key] = text
    return result


def facts_of(recordings, tag):
    """observed-facts.json of Fas 0 and Fas 1, keys prefixed with fas0./fas1."""
    base = os.path.join(recordings, f"contract-recordings-{tag}", tag)
    merged, found = {}, False
    for phase, path in (("fas0", os.path.join(base, "observed-facts.json")), ("fas1", os.path.join(base, "fas1", "observed-facts.json"))):
        facts = normalized(path)
        if facts is not None:
            found = True
            merged.update({f"{phase}.{key}": value for key, value in facts.items()})
    return merged if found else None


def server_version(recordings, tag):
    path = os.path.join(recordings, f"contract-recordings-{tag}", tag, "observed-facts.json")
    try:
        with open(path, encoding="utf-8") as file:
            return json.load(file).get("serverSettings.version") or "?"
    except (OSError, ValueError):
        return "?"


def diff(reference, observed):
    return [line for _, line in keyed_diff(reference, observed)]


def keyed_diff(reference, observed):
    """(key, diff line) for every fact that differs; keys may contain spaces (food names)."""
    lines = []
    for key in sorted(set(reference) | set(observed)):
        before, after = reference.get(key), observed.get(key)
        if before == after:
            continue
        if before is None:
            lines.append((key, f"+ {key} = {after}"))
        elif after is None:
            lines.append((key, f"- {key} (was {before})"))
        else:
            lines.append((key, f"~ {key}: {before} -> {after}"))
    return lines


def line_of(version):
    """`2.7.1` -> `2.7`; None for an unparseable version."""
    match = re.match(r"^(\d+)\.(\d+)", version or "")
    return f"{match.group(1)}.{match.group(2)}" if match else None


def split_reviewed(reference, observed, reviewed):
    """(new diff lines, reviewed diff lines) of one tag; [reviewed] is the line's key -> expected map."""
    new, accepted = [], []
    for key, line in keyed_diff(reference, observed):
        after = observed.get(key)
        if key in reviewed and (reviewed[key] == "*" or reviewed[key] == after):
            accepted.append(line)
        else:
            new.append(line)
    return new, accepted


def job_results(jobs):
    results = {}
    for job in jobs.get("jobs", []):
        match = re.search(r"Tandoor (\S+)$", job.get("name", ""))
        if match:
            results[match.group(1)] = job.get("conclusion") or job.get("status") or "unknown"
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--discover", required=True)
    parser.add_argument("--jobs", required=True)
    parser.add_argument("--recordings", required=True)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--github-output")
    parser.add_argument("--reviewed", help="reviewed-changes.json (facts reviewed per release line)")
    args = parser.parse_args()

    reviewed_lines = {}
    if args.reviewed:
        with open(args.reviewed, encoding="utf-8") as file:
            reviewed_lines = {line: entry.get("facts", {}) for line, entry in json.load(file).items() if not line.startswith("_")}

    with open(args.discover, encoding="utf-8") as file:
        discovered = json.load(file)
    with open(args.jobs, encoding="utf-8") as file:
        results = job_results(json.load(file))
    reference = facts_of(args.recordings, BASELINE)

    reasons, rows, details = [], [], []
    for tag in discovered["tags"]:
        result = results.get(tag, "missing")
        observed = facts_of(args.recordings, tag)
        version = server_version(args.recordings, tag)
        accepted = []
        if tag == BASELINE:
            changes = []
        elif reference is None or observed is None:
            changes = ["(no observed facts to compare)"]
        else:
            changes, accepted = split_reviewed(reference, observed, reviewed_lines.get(line_of(version), {}))
        if tag == BASELINE:
            counted = "reference"
        elif accepted:
            counted = f"{len(changes)} (+{len(accepted)} reviewed for {line_of(version)})"
        else:
            counted = str(len(changes))
        rows.append(f"| `{tag}` | {version} | {'✅' if result == 'success' else '❌ ' + result} | {counted} |")
        if result != "success":
            reasons.append(f"contract tests {result} against {tag}")
        if accepted:
            shown = accepted[:MAX_DIFF_LINES]
            more = f"\n… and {len(accepted) - MAX_DIFF_LINES} more" if len(accepted) > MAX_DIFF_LINES else ""
            details.append(
                f"<details><summary><code>{tag}</code>: {len(accepted)} differences reviewed for {line_of(version)} "
                "(contract-tests/watch/reviewed-changes.json)</summary>\n\n```diff\n" + "\n".join(shown) + more + "\n```\n</details>\n"
            )
        if changes:
            reasons.append(f"observed facts changed in {tag} ({len(changes)})")
            shown = changes[:MAX_DIFF_LINES]
            more = f"\n… and {len(changes) - MAX_DIFF_LINES} more" if len(changes) > MAX_DIFF_LINES else ""
            details.append(f"### `{tag}` compared with {BASELINE}\n\n```diff\n" + "\n".join(shown) + more + "\n```\n")
    if discovered.get("untested"):
        reasons.append("released but not in TandoorVersionTable.TESTED: " + ", ".join(discovered["untested"]))

    attention = bool(reasons)
    tested = discovered.get("tested", [])
    lines = [
        "<!-- tandoor-watch -->",
        "## Tandoor watch",
        "",
        f"Run: {args.run_url}",
        "",
        f"Tested in the app (`TandoorVersionTable.TESTED`): {tested[0]} – {tested[-1]}" if tested else "",
        "Newest release per line: " + ", ".join(f"{line}: `{v}`" for line, v in discovered.get("newest", {}).items()),
        "",
        "| Image tag | Server version | Contract tests | Changed facts vs 2.6.13 |",
        "|---|---|---|---|",
        *rows,
        "",
    ]
    if attention:
        lines += ["### Needs attention", "", *[f"- {reason}" for reason in reasons], ""]
        lines += [
            "A new release that is green with no changed facts can be added to `TandoorVersionTable.TESTED`",
            "(`:core:network`) in the next app version. A red run or a changed fact needs a look at",
            "the contract-recordings artifact of the run before anyone upgrades a server; once the app",
            "handles a new line, its reviewed differences go into contract-tests/watch/reviewed-changes.json.",
            "",
        ]
    else:
        lines += ["Everything is green, every release is tested and no observed fact changed without a review.", ""]
    lines += details
    report = "\n".join(lines)
    with open(args.out, "w", encoding="utf-8") as file:
        file.write(report)

    fingerprint = hashlib.sha256("\n".join(sorted(reasons) + details).encode()).hexdigest()[:16]
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as file:
            file.write(f"attention={'true' if attention else 'false'}\n")
            file.write(f"fingerprint={fingerprint}\n")
    print(report)


if __name__ == "__main__":
    main()
