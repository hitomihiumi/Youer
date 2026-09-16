#!/usr/bin/env python3
"""
Find upstream API hooks the merge dropped.

The sibling script, audit_paper_hunks.py, compares whole lines and drowns in
false positives because the two toolchains name locals differently. This one
only looks at names that cannot be renamed by a decompiler: event classes,
CraftEventFactory calls, the per-world config objects, and fully-qualified
Paper/Purpur/Bukkit references. If a patch introduces one and the applied file
never mentions it, something was lost.

    python3 tools/test/audit_api_hooks.py <upstream patches dir> [more dirs...]

Suppressing a hit needs a reason, not a shrug: either the hook moved somewhere
else in our tree (grep the whole of projects/youer/src before believing a
report), or it belongs to a subsystem this port excludes on purpose.
"""
import os
import re
import sys

OURS = "projects/youer/src/main/java"

INTERESTING = [
    re.compile(r'\b(?:com\.destroystokyo\.paper|io\.papermc\.paper|org\.purpurmc\.purpur|gg\.pufferfish)\.[A-Za-z0-9_.]*[A-Z][A-Za-z0-9_]*'),
    re.compile(r'\bCraftEventFactory\.[a-zA-Z0-9_]+'),
    re.compile(r'\b[A-Z][A-Za-z0-9_]*Event\b'),
    re.compile(r'\b(?:paperConfig\(\)|spigotConfig|purpurConfig|pufferfishConfig)\.[A-Za-z0-9_.]+'),
]

# Subsystems this port excludes on purpose; a hit inside one of these is expected.
EXCLUDED = re.compile(r'ca\.spottedleaf\.moonrise|moonrise|ChunkSystem')


def names(text):
    found = set()
    for pattern in INTERESTING:
        for match in pattern.findall(text):
            if not EXCLUDED.search(match):
                found.add(match)
    return found


def main(dirs):
    whole_tree = None
    findings = []
    for base in dirs:
        for root, _dirs, files in os.walk(base):
            for fn in files:
                if not fn.endswith(".java.patch"):
                    continue
                rel = os.path.relpath(os.path.join(root, fn), base)[: -len(".patch")]
                ours = os.path.join(OURS, rel)
                if not os.path.exists(ours):
                    continue
                added = "\n".join(
                    line[1:]
                    for line in open(os.path.join(root, fn), encoding="utf-8", errors="replace")
                    if line.startswith("+") and not line.startswith("+++")
                )
                have = open(ours, encoding="utf-8", errors="replace").read()
                missing = sorted(n for n in names(added) if n not in have)
                if missing:
                    findings.append((rel, missing))

    if findings:
        # A hook can legitimately have moved to another file, so check the tree once.
        whole_tree = read_tree()
    for rel, missing in sorted(findings, key=lambda f: -len(f[1])):
        elsewhere = [n for n in missing if n in whole_tree]
        here = [n for n in missing if n not in whole_tree]
        if here:
            print(f"{rel}")
            for n in here:
                print(f"    {n}")
            if elsewhere:
                print(f"    (elsewhere in the tree: {', '.join(elsewhere)})")


def read_tree():
    text = []
    for root, _dirs, files in os.walk(OURS):
        for fn in files:
            if fn.endswith(".java"):
                text.append(open(os.path.join(root, fn), encoding="utf-8", errors="replace").read())
    return "\n".join(text)


if __name__ == "__main__":
    main(sys.argv[1:] or [os.environ.get("PAPER_PATCHES", "")])
