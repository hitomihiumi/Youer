#!/usr/bin/env python3
"""
Look for Paper hunks the merge dropped.

Compares each added line of every Paper patch against our applied source,
matching on the line's "signature": its identifiers and literals with the
decompiler-generated names (p_NNNNN_, varN, and one/two letter locals) removed,
since those differ between the two toolchains by definition.

    python3 tools/test/audit_paper_hunks.py <paper>/paper-server/patches/sources

This is a lead generator, not a to-do list. On the file it was written for it
reported eighteen missing lines, of which three were real; the rest differed
only in a local name this script does not normalise away, or sat in code the
B3 chunk-system exclusion deliberately rewrote. Read every hit against the
actual file before believing it. It found the ChunkMap#addEntity guard whose
absence made the server unjoinable, so the noise is worth wading through when
something behaves oddly in a file Paper patches heavily.
"""
import os, re, sys, collections

PAPER = sys.argv[1]
OURS = "projects/youer/src/main/java"

token = re.compile(r'"[^"\n]*"|[A-Za-z_$][A-Za-z_$0-9]*|\d+')
generated = re.compile(r'^(p_\d+_|var\d+|[a-z]{1,2}\d*|i\d|j\d|k\d|d\d|f\d|s\d|flag\d?|list\d?|builder|entity|player|level|pos|chunkPos|holder|serverPlayer|entry|tag|data|out|output|it|e|ex)$')


def signature(line):
    toks = [t for t in token.findall(line) if not generated.match(t)]
    return tuple(toks)


def file_signatures(path):
    sigs = collections.Counter()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            sig = signature(line)
            if sig:
                sigs[sig] += 1
    return sigs


findings = []
for root, _dirs, files in os.walk(PAPER):
    for fn in files:
        if not fn.endswith(".java.patch"):
            continue
        rel = os.path.relpath(os.path.join(root, fn), PAPER)[: -len(".patch")]
        ours = os.path.join(OURS, rel)
        if not os.path.exists(ours):
            findings.append((rel, None, "file missing"))
            continue
        have = file_signatures(ours)
        missing = []
        with open(os.path.join(root, fn), encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.startswith("+") or line.startswith("+++"):
                    continue
                body = line[1:].strip()
                if not body or body.startswith("//") or body.startswith("*") or body.startswith("/*"):
                    continue
                sig = signature(body)
                if len(sig) < 3:
                    continue
                if have[sig] == 0:
                    missing.append(body)
        if missing:
            findings.append((rel, len(missing), missing))

findings.sort(key=lambda f: -(f[1] or 10 ** 6))
total = sum(f[1] or 0 for f in findings)
print(f"files with gaps: {len(findings)}   missing lines: {total}\n")
for rel, n, detail in findings[:40]:
    print(f"{n if n is not None else '-':>5}  {rel}")
