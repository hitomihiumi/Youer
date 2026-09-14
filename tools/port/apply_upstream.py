#!/usr/bin/env python3
"""Apply an upstream (Paper or Purpur) patch set to the patched Minecraft sources.

Usage:
    python3 tools/port/apply_upstream.py <patch-root> [--dry-run] [--bukkit]

  <patch-root>  a directory of upstream *.java.patch files, e.g.
                  paper/paper-server/patches/sources
                  purpur/purpur-server/minecraft-patches/sources
                  purpur/purpur-server/paper-patches/files   (with --bukkit)
  --bukkit      patches target src/main/java (the Bukkit/Paper layer) and are
                rooted at src/main/java/ inside the patch tree, rather than
                projects/youer/src/main/java

Run `./gradlew setup` first so the target tree exists, and
`./gradlew :youer:genPatches` afterwards to write the result back to patches/.
Applying is idempotent: a hunk already present in the target is skipped.
"""
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smartpatch import apply_patch

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    flags = {a for a in argv[1:] if a.startswith("--")}
    if not args:
        print(__doc__)
        return 2
    root = args[0]
    bukkit = "--bukkit" in flags
    dry = "--dry-run" in flags
    src = os.path.join(REPO, "src/main/java" if bukkit else "projects/youer/src/main/java")
    prefix = "src/main/java/" if bukkit else ""

    totals = collections.Counter()
    rejects = []
    for dirpath, _, files in os.walk(root):
        for fn in sorted(files):
            if not fn.endswith(".patch"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), root)
            if prefix and not rel.startswith(prefix):
                totals["skipped"] += 1
                continue
            jf = rel[len(prefix):-len(".patch")]
            target = os.path.join(src, jf)
            if not os.path.exists(target):
                totals["no-target"] += 1
                continue
            text = open(os.path.join(dirpath, fn), encoding="utf-8").read()
            lines, results, _ = apply_patch(target, text)
            for r, h in results:
                totals[r] += 1
                if r == "REJECT":
                    rejects.append((jf, h["start"], h["oldn"]))
            if not dry and any(r.startswith("OK") for r, _ in results):
                open(target, "w", encoding="utf-8").write("\n".join(lines))

    print(dict(totals))
    if rejects:
        print(f"\n{len(rejects)} unapplied hunks:")
        for jf, start, n in rejects:
            print(f"  {jf} @@ -{start},{n}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
